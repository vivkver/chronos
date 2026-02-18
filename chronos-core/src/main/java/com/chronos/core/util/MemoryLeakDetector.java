package com.chronos.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically logs Direct Memory usage to help detect leaks or unexpected
 * growth.
 * Usage: Call {@code start()} at application startup.
 */
public final class MemoryLeakDetector {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryLeakDetector.class);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "memory-monitor");
        t.setDaemon(true);
        return t;
    });

    private MemoryLeakDetector() {
    }

    public static void start(long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(MemoryLeakDetector::logMemoryUsage, 0, period, unit);
    }

    public static void stop() {
        scheduler.shutdown();
    }

    private static void logMemoryUsage() {
        try {
            List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            for (BufferPoolMXBean pool : pools) {
                if ("direct".equals(pool.getName())) {
                    long count = pool.getCount();
                    long capacity = pool.getTotalCapacity();
                    long memoryUsed = pool.getMemoryUsed();

                    LOG.info("Direct Memory: Used={} MB, Capacity={} MB, Count={}",
                            memoryUsed / 1024 / 1024,
                            capacity / 1024 / 1024,
                            count);

                    // Simple heuristic trigger
                    if (memoryUsed > 1024 * 1024 * 1024) { // > 1GB
                        LOG.warn("High Direct Memory Usage detected!");
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to read memory usage", e);
        }
    }
}
