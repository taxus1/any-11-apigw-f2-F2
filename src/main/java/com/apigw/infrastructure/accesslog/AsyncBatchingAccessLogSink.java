package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.domain.accesslog.AccessLogSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问流水异步攒批落库的出口。转发主路径与库之间的唯一缓冲。
 *
 * <b>怎么保证不拖慢转发：</b>
 * 请求线程（Netty 事件循环）只做一次 {@link BlockingQueue#offer}（非阻塞、不等待），
 * 攒批、批量写库全部在一条独立守护线程上。队列满时直接丢这一条并累计计数、按节奏告警，
 * <b>绝不反压、绝不抛异常</b>——转发是主职责，流水是旁路，宁可丢日志也不能拖垮上游。
 *
 * <b>攒批的三条边界：</b>
 * <ol>
 *   <li>攒够 {@code batchSize} 条立刻落（吞吐上限，一批一条 SQL，库扛得住）；</li>
 *   <li>没攒够时最多等 {@code flushInterval} 必落一次（低峰期数据不会压在内存里迟迟不可查）；</li>
 *   <li>应用正常退出（{@link #stop}）：先停接收新流量（Web 容器先停，phase 较晚），
 *       再唤醒写线程把队列里剩余的全部按批 drain 完，等待上限 {@code shutdownAwait}；
 *       超时不再等，保证进程一定退得掉——退出期间库挂了这种极端情况才会丢，且日志里有计数。</li>
 * </ol>
 *
 * <b>写失败的处置：</b>整批在一个事务里（见 {@link JdbcAccessLogRepository}），
 * 要么整批提交要么整批回滚，不存在「半条记录」；失败（库抖动/连不上）只丢这一批 +
 * 记 warn 日志和计数，异常不出写线程，转发完全无感。
 */
@Slf4j
public class AsyncBatchingAccessLogSink implements AccessLogSink, SmartLifecycle {

    private final AccessLogRepository repository;
    private final int batchSize;
    private final long flushIntervalMillis;
    private final long shutdownAwaitMillis;

    private final BlockingQueue<AccessLogEntry> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile boolean running = false;
    /** 关停标志：置位后入口不再收新行，写线程只做 drain。 */
    private volatile boolean draining = false;
    private Thread worker;

    public AsyncBatchingAccessLogSink(AccessLogRepository repository, AccessLogProperties props) {
        this.repository = repository;
        this.batchSize = props.batchSize();
        this.flushIntervalMillis = props.flushInterval().toMillis();
        this.shutdownAwaitMillis = props.shutdownAwait().toMillis();
        this.queue = new ArrayBlockingQueue<>(props.queueCapacity());
    }

    @Override
    public void record(AccessLogEntry entry) {
        if (draining) {
            // 关停途中还收得到的：只计数不进队，避免和 drain 抢、也避免停不掉
            countDrop(dropped, entry, "关停中");
            return;
        }
        // offer 立即返回：队列满 → 丢弃 + 计数告警，绝不在请求线程上等库/等队列
        if (!queue.offer(entry)) {
            countDrop(dropped, entry, "队列满");
        }
    }

    private void countDrop(AtomicLong counter, AccessLogEntry entry, String reason) {
        long n = counter.incrementAndGet();
        if (n == 1 || n % 1000 == 0) {
            log.warn("访问流水丢弃 {} 条（{}），最新一条 requestId={} —— 转发不受影响", n, reason,
                    entry.requestId());
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "access-log-db-writer");
        t.setDaemon(true); // 兜底：即便 stop 流程没走到，守护线程也不会阻止 JVM 退出
        worker = t;
        t.start();
        log.info("访问流水异步攒批落库已启动：batchSize={} flushInterval={}ms queueCapacity={}",
                batchSize, flushIntervalMillis, queue.size() + queue.remainingCapacity());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        draining = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt(); // 唤醒可能正阻塞在 poll 上的写线程，立刻进入 drain
            try {
                t.join(shutdownAwaitMillis);
                if (t.isAlive()) {
                    log.warn("访问流水关停等待 {}ms 超时，队列仍剩约 {} 条未落（进程继续退出）",
                            shutdownAwaitMillis, queue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 在 Web 容器之后停：默认 phase 的 SmartLifecycle/容器（Netty 端口）先停，不再有新请求进来，
     * 本组件再 drain，关停期间不会一边收新行一边收尾。
     */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    /**
     * 写线程主循环。不变量：任意一行从入队到落库的等待不超过一个 flushInterval 窗口。
     * 做法：空队列时阻塞等第一行；一旦拿到第一行，就以它为窗口起点补满到 batchSize 或等满窗口。
     * 关键细节——阻塞等待超时返回的同一刻若恰好有行入队，先把它取进来再继续，
     * 否则这行会白白多等一个窗口（最坏延迟翻倍）。
     * 收到关停中断后立刻退出循环，统一在 finally 里 drain 剩余队列。
     */
    private void loop() {
        List<AccessLogEntry> batch = new ArrayList<>(batchSize);
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                AccessLogEntry first = draining
                        ? queue.poll()
                        : queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (draining || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    // 一个窗口内空转。超时返回和入队可能同时发生，先非阻塞捞一下再继续
                    first = queue.poll();
                    if (first == null) {
                        continue;
                    }
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - batch.size());

                long deadline = System.currentTimeMillis() + flushIntervalMillis;
                while (batch.size() < batchSize && !draining) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        break;
                    }
                    AccessLogEntry more = queue.poll(remain, TimeUnit.MILLISECONDS);
                    if (more != null) {
                        batch.add(more);
                        queue.drainTo(batch, batchSize - batch.size());
                    }
                }
                flush(batch);
                batch.clear();
            }
        } catch (InterruptedException e) {
            // stop 唤醒：交 finally 的 drainRemaining 收尾
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 写线程绝不能因任何意外死掉，否则后续流水全丢且无人知晓
            log.error("访问流水写线程发生未预期异常，尝试继续工作", t);
        } finally {
            drainRemaining();
            log.info("访问流水写线程退出，累计丢弃={} 累计写失败批次条目数={}", dropped.get(), failed.get());
        }
    }

    /** 关停时把队列里剩余记录按批全部落完，能落多少落多少，直到 flush 失败或队列空。 */
    private void drainRemaining() {
        List<AccessLogEntry> batch = new ArrayList<>(batchSize);
        while (true) {
            queue.drainTo(batch, batchSize);
            if (batch.isEmpty()) {
                // drainTo 可能在多生产者收尾瞬间漏一个，再兜底 poll 一次
                AccessLogEntry last = queue.poll();
                if (last == null) {
                    return;
                }
                batch.add(last);
                queue.drainTo(batch, batchSize - batch.size());
            }
            try {
                repository.saveBatch(batch);
            } catch (Exception e) {
                failed.addAndGet(batch.size());
                log.warn("关停 drain 时写库失败，剩余 {} 条将丢失：{}", batch.size(), e.toString());
                return; // 库已不可用，继续重试只会拖死退出
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<AccessLogEntry> batch) {
        try {
            repository.saveBatch(batch);
        } catch (Exception e) {
            // 库一时抖动：整批已随事务回滚，不存在半条；只记日志和计数，绝不影响转发
            failed.addAndGet(batch.size());
            log.warn("访问流水批量写库失败，本批 {} 条未落（整批回滚，不影响转发）：{}",
                    batch.size(), e.toString());
        }
    }
}
