package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 攒批出口测试用的仓储替身：记录每次 saveBatch 的批次，并可注入故障/阻塞。
 */
class FakeBatchRepository implements AccessLogRepository {

    final List<List<AccessLogEntry>> batches = new CopyOnWriteArrayList<>();
    final List<AccessLogEntry> all = new CopyOnWriteArrayList<>();
    volatile RuntimeException failWith;
    volatile CountDownLatch blockSaving;

    @Override
    public synchronized void saveBatch(List<AccessLogEntry> entries) {
        if (blockSaving != null) {
            try {
                blockSaving.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failWith != null) {
            throw failWith;
        }
        batches.add(List.copyOf(entries));
        all.addAll(entries);
    }

    @Override
    public AccessLogPage page(AccessLogQuery query, long offset, int limit) {
        return new AccessLogPage(List.of(), 0);
    }

    static AccessLogEntry entry(String id) {
        return new AccessLogEntry(id, "r", "app", "127.0.0.1", "GET", "/" + id,
                200, 5, Instant.now());
    }

    static AccessLogProperties props(int batchSize, Duration interval,
                                     int queueCapacity, Duration shutdownAwait) {
        return new AccessLogProperties(true, batchSize, interval, queueCapacity, shutdownAwait);
    }

    static List<AccessLogEntry> entries(int n) {
        List<AccessLogEntry> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(entry(String.format("id-%05d", i)));
        }
        return list;
    }
}
