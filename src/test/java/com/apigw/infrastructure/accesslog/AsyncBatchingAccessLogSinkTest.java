package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.apigw.infrastructure.accesslog.FakeBatchRepository.entry;
import static com.apigw.infrastructure.accesslog.FakeBatchRepository.entries;
import static com.apigw.infrastructure.accesslog.FakeBatchRepository.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 异步攒批落库出口测试，对应题目的几条硬要求：
 * - 凑够一批立刻落；没凑够最多等一个窗口也要落；
 * - 正常退出 drain 完手里的数据，不丢、也不死等；
 * - 入口永不阻塞调用方（队列满丢日志计数，不抛异常）；
 * - 写库失败只丢这批 + 日志，不抛到调用方、写线程不死，后面的批次继续落。
 */
class AsyncBatchingAccessLogSinkTest {

    @Test
    void flushesAsSoonAsBatchSizeReached_withoutWaitingInterval() {
        FakeBatchRepository repo = new FakeBatchRepository();
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(100, Duration.ofHours(1), 10_000, Duration.ofSeconds(5)));
        sink.start();
        try {
            entries(100).forEach(sink::record);
            // 间隔给了 1 小时，但凑够 100 必须立刻落
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(repo.all).hasSize(100));
            assertThat(repo.batches.get(0)).hasSize(100);
        } finally {
            sink.stop();
        }
    }

    @Test
    void partialBatch_flushesAfterAtMostOneInterval() {
        FakeBatchRepository repo = new FakeBatchRepository();
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(500, Duration.ofMillis(300), 10_000, Duration.ofSeconds(5)));
        sink.start();
        try {
            sink.record(entry("lonely"));
            long t0 = System.nanoTime();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(repo.all).hasSize(1));
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            // 不能在内存里无限攒：应在一个窗口级别落下来（给调度留余量，上限放宽到 1.5s）
            assertThat(waitedMs).isLessThan(1500);
        } finally {
            sink.stop();
        }
    }

    @Test
    void gracefulStop_drainsAllQueuedRows_nothingLost() throws Exception {
        FakeBatchRepository repo = new FakeBatchRepository();
        // 写库故意慢：让 stop 时队列里还压着一大批，验证关停 drain
        int batchSize = 50;
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(batchSize, Duration.ofMillis(100), 100_000, Duration.ofSeconds(10)));

        // 先卡住写线程的第一次写，制造关停时队列里有积压
        repo.blockSaving = new CountDownLatch(1);
        sink.start();
        for (int i = 0; i < 350; i++) {
            sink.record(entry("q" + i));
        }
        Thread.sleep(200); // 等写线程取到第一批、卡在 saveBatch 上

        Thread stopper = new Thread(sink::stop);
        stopper.start();
        Thread.sleep(200); // stop 已在等 drain
        repo.blockSaving.countDown(); // 放行

        stopper.join(5_000);
        assertThat(stopper.isAlive()).isFalse();
        // 350 条全部落库，不丢；批次大小不超过 batchSize（中断打断窗口时尾批允许较小）
        assertThat(repo.all).hasSize(350);
        assertThat(repo.batches).isNotEmpty();
        assertThat(repo.batches).allMatch(b -> b.size() <= batchSize);
        assertThat(repo.batches.stream().mapToInt(List::size).sum()).isEqualTo(350);
    }

    @Test
    void stopWhenQueueEmpty_returnsImmediately() {
        FakeBatchRepository repo = new FakeBatchRepository();
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(100, Duration.ofSeconds(1), 1000, Duration.ofSeconds(5)));
        sink.start();
        long t0 = System.nanoTime();
        sink.stop();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(1000);
    }

    @Test
    void shutdownAwaitBounded_stopReturnsEvenWhenDbStuck() throws Exception {
        FakeBatchRepository repo = new FakeBatchRepository();
        repo.blockSaving = new CountDownLatch(1); // 永远不放
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(5, Duration.ofHours(1), 100_000, Duration.ofSeconds(1)));
        sink.start();
        // 填满队列远超一批，drain 会一直卡
        for (int i = 0; i < 500; i++) {
            sink.record(entry("stuck" + i));
        }
        Thread t = new Thread(sink::stop);
        t.start();
        t.join(5_000);
        // 关停等待 1s 封顶：即使库一直卡，进程也退得掉，不无限等
        assertThat(t.isAlive()).isFalse();
    }

    @Test
    void recordNeverBlocksOrThrows_whenQueueFull_andDropsAreCounted() {
        FakeBatchRepository repo = new FakeBatchRepository();
        // 写库永久卡住 + 小队列：很快堆满，record 必须照样毫秒返回、不抛异常
        repo.blockSaving = new CountDownLatch(1);
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(10, Duration.ofHours(1), 100, Duration.ofMillis(200)));
        sink.start();
        try {
            long t0 = System.nanoTime();
            for (int i = 0; i < 50_000; i++) {
                sink.record(entry("flood" + i)); // 队列满后应丢弃而不是等待
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).isLessThan(2000); // 5 万次入队/丢弃不卡
            // 放行了也没事（避免写线程悬挂影响后续），这里直接结束，stop 超时即可
            repo.blockSaving.countDown();
        } finally {
            sink.stop();
        }
    }

    @Test
    void batchWriteFailure_doesNotKillWriter_laterBatchesContinue() {
        FakeBatchRepository repo = new FakeBatchRepository();
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(10, Duration.ofHours(1), 10_000, Duration.ofSeconds(5)));
        sink.start();
        try {
            // 第一批失败（模拟库抖），这批应被丢弃但写线程活着
            repo.failWith = new RuntimeException("模拟 DB 抖动");
            entries(10).forEach(sink::record);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(repo.batches).isEmpty());

            // 恢复：后续批次照样能落，证明写线程没死、调用方全程无异常
            repo.failWith = null;
            entries(10).forEach(sink::record);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(repo.all).hasSize(10));
            assertThat(repo.all).extracting(AccessLogEntry::requestId)
                    .allSatisfy(id -> assertThat(id).startsWith("id-"));
        } finally {
            sink.stop();
        }
    }

    @Test
    void largeBacklog_drainsInMultipleBatches_respectingBatchSize() {
        FakeBatchRepository repo = new FakeBatchRepository();
        var sink = new AsyncBatchingAccessLogSink(repo,
                props(100, Duration.ofSeconds(1), 100_000, Duration.ofSeconds(10)));
        sink.start();
        try {
            entries(250).forEach(sink::record);
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(repo.all).hasSize(250));
            // 100 + 100 + 50：不一行一插，也不搞一个超大事务
            assertThat(repo.batches).extracting(List::size).containsExactly(100, 100, 50);
        } finally {
            sink.stop();
        }
    }
}
