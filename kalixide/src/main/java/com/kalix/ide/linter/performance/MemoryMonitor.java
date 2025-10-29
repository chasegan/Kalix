package com.kalix.ide.linter.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors memory usage during validation operations to detect memory leaks
 * and provide performance insights.
 */
public class MemoryMonitor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryMonitor.class);

    private final MemoryMXBean memoryBean;
    private final ScheduledExecutorService monitorExecutor;

    // Memory tracking
    private final AtomicLong peakHeapUsed = new AtomicLong(0);
    private final AtomicLong peakNonHeapUsed = new AtomicLong(0);
    private volatile long validationStartMemory = 0;
    private volatile boolean monitoring = false;

    // Configuration
    private final long monitoringIntervalMs;
    private final long memoryWarningThresholdMB;

    public MemoryMonitor() {
        this(5000, 512); // Monitor every 5 seconds, warn at 512MB
    }

    public MemoryMonitor(long monitoringIntervalMs, long memoryWarningThresholdMB) {
        this.monitoringIntervalMs = monitoringIntervalMs;
        this.memoryWarningThresholdMB = memoryWarningThresholdMB;
        this.memoryBean = ManagementFactory.getMemoryMXBean();

        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start monitoring memory usage.
     */
    public void startMonitoring() {
        if (monitoring) {
            return;
        }

        monitoring = true;
        monitorExecutor.scheduleAtFixedRate(this::checkMemoryUsage,
                                          monitoringIntervalMs,
                                          monitoringIntervalMs,
                                          TimeUnit.MILLISECONDS);    }

    /**
     * Stop monitoring memory usage.
     */
    public void stopMonitoring() {
        monitoring = false;
    }

    /**
     * Mark the start of a validation operation for memory tracking.
     */
    public void startValidationMemoryTracking() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        validationStartMemory = heapUsage.getUsed();    }

    /**
     * Mark the end of a validation operation and report memory delta.
     */
    public void endValidationMemoryTracking() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long currentMemory = heapUsage.getUsed();
        long memoryDelta = currentMemory - validationStartMemory;

        if (memoryDelta > 0) {        } else {        }

        // Update peak tracking
        peakHeapUsed.updateAndGet(current -> Math.max(current, currentMemory));
    }

    /**
     * Get current memory statistics.
     */
    public MemoryStats getCurrentMemoryStats() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        return new MemoryStats(
            bytesToMB(heapUsage.getUsed()),
            bytesToMB(heapUsage.getMax()),
            bytesToMB(nonHeapUsage.getUsed()),
            bytesToMB(peakHeapUsed.get()),
            bytesToMB(peakNonHeapUsed.get()),
            getMemoryPressure()
        );
    }

    /**
     * Trigger garbage collection and report memory reclaimed.
     */
    public void forceGarbageCollection() {
        MemoryUsage beforeGC = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeGC.getUsed();

        System.gc();

        // Wait a bit for GC to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        MemoryUsage afterGC = memoryBean.getHeapMemoryUsage();
        long afterUsed = afterGC.getUsed();
        long reclaimed = beforeUsed - afterUsed;    }

    /**
     * Reset peak memory tracking.
     */
    public void resetPeakTracking() {
        peakHeapUsed.set(0);
        peakNonHeapUsed.set(0);
    }

    public static class MemoryStats {
        public final long heapUsedMB;
        public final long heapMaxMB;
        public final long nonHeapUsedMB;
        public final long peakHeapUsedMB;
        public final long peakNonHeapUsedMB;
        public final double memoryPressure; // 0.0 to 1.0

        public MemoryStats(long heapUsedMB, long heapMaxMB, long nonHeapUsedMB,
                          long peakHeapUsedMB, long peakNonHeapUsedMB, double memoryPressure) {
            this.heapUsedMB = heapUsedMB;
            this.heapMaxMB = heapMaxMB;
            this.nonHeapUsedMB = nonHeapUsedMB;
            this.peakHeapUsedMB = peakHeapUsedMB;
            this.peakNonHeapUsedMB = peakNonHeapUsedMB;
            this.memoryPressure = memoryPressure;
        }

        @Override
        public String toString() {
            return String.format("MemoryStats{heap=%d/%dMB (%.1f%%), nonHeap=%dMB, peaks=[%d,%d]MB, pressure=%.2f}",
                               heapUsedMB, heapMaxMB, (double) heapUsedMB / heapMaxMB * 100,
                               nonHeapUsedMB, peakHeapUsedMB, peakNonHeapUsedMB, memoryPressure);
        }
    }

    private void checkMemoryUsage() {
        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

            long heapUsedMB = bytesToMB(heapUsage.getUsed());
            long nonHeapUsedMB = bytesToMB(nonHeapUsage.getUsed());

            // Update peaks
            peakHeapUsed.updateAndGet(current -> Math.max(current, heapUsage.getUsed()));
            peakNonHeapUsed.updateAndGet(current -> Math.max(current, nonHeapUsage.getUsed()));

            // Check for memory warnings
            if (heapUsedMB > memoryWarningThresholdMB) {
                double pressure = getMemoryPressure();
                logger.warn("High memory usage detected: {} MB heap (pressure: {:.2f})", heapUsedMB, pressure);

                // Suggest GC if pressure is very high
                if (pressure > 0.9) {
                    logger.warn("Memory pressure critical (>{:.1f}%) - consider reducing validation frequency", pressure * 100);
                }
            }

            logger.trace("Memory check - heap: {} MB, non-heap: {} MB", heapUsedMB, nonHeapUsedMB);

        } catch (Exception e) {
            logger.error("Error during memory monitoring", e);
        }
    }

    private double getMemoryPressure() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();

        if (max <= 0) {
            return 0.0; // Max not defined
        }

        return Math.min(1.0, (double) used / max);
    }

    private long bytesToMB(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * Shutdown the memory monitor.
     */
    public void shutdown() {
        stopMonitoring();
        monitorExecutor.shutdown();
        try {
            if (!monitorExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}