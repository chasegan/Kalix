package com.kalix.ide.linter.performance;

import com.kalix.ide.linter.*;
import com.kalix.ide.linter.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;

/**
 * High-performance version of LinterOrchestrator with async validation,
 * caching, memory monitoring, and optimized parsing.
 */
public class PerformanceLinterOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceLinterOrchestrator.class);

    private final SchemaManager schemaManager;
    private final ModelLinter linter;
    private final IncrementalValidator incrementalValidator;

    // Performance components
    private final AsyncValidationExecutor validationExecutor;
    private final ValidationResultCache resultCache;
    private final MemoryMonitor memoryMonitor;

    private ValidationResult currentValidationResult;
    private boolean validationEnabled = true;

    // Performance statistics
    private volatile long totalValidations = 0;
    private volatile long cacheHits = 0;
    private volatile long averageValidationTimeMs = 0;

    // Callback interface for validation completion
    public interface ValidationResultHandler {
        void onValidationCompleted(ValidationResult result);
        void onValidationCancelled();
        void onValidationError(Exception error);
    }

    private ValidationResultHandler resultHandler;

    public PerformanceLinterOrchestrator(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        this.linter = new ModelLinter(schemaManager);
        this.incrementalValidator = new IncrementalValidator(linter);

        // Initialize performance components
        this.validationExecutor = new AsyncValidationExecutor();
        this.resultCache = ValidationResultCache.createDefault();
        this.memoryMonitor = new MemoryMonitor();

        // Start memory monitoring
        memoryMonitor.startMonitoring();

        logger.info("PerformanceLinterOrchestrator initialized with caching and monitoring");
    }

    public void setValidationResultHandler(ValidationResultHandler handler) {
        this.resultHandler = handler;
    }

    /**
     * Perform high-performance validation with caching and async execution.
     */
    public void performValidation(String content) {
        if (!validationEnabled || !schemaManager.isLintingEnabled()) {
            return;
        }

        // Check cache first
        ValidationResult cachedResult = resultCache.get(content);
        if (cachedResult != null) {
            cacheHits++;
            currentValidationResult = cachedResult;
            notifyValidationCompleted(cachedResult);
            logger.debug("Validation served from cache (hit rate: {:.2f}%)",
                        resultCache.getHitRatio() * 100);
            return;
        }

        // Submit async validation with performance tracking
        validationExecutor.submitValidation(content, this::performValidationInternal, new AsyncValidationExecutor.ValidationCallback() {
            @Override
            public void onValidationCompleted(ValidationResult result) {
                currentValidationResult = result;
                totalValidations++;

                // Cache the result
                resultCache.put(content, result);

                notifyValidationCompleted(result);

                // Log performance stats periodically
                if (totalValidations % 10 == 0) {
                    logPerformanceStats();
                }
            }

            @Override
            public void onValidationCancelled() {
                logger.debug("Validation cancelled");
                if (resultHandler != null) {
                    resultHandler.onValidationCancelled();
                }
            }

            @Override
            public void onValidationError(Exception error) {
                logger.error("Validation error", error);
                if (resultHandler != null) {
                    resultHandler.onValidationError(error);
                }
            }
        });
    }

    /**
     * Perform validation with debouncing for better performance during rapid typing.
     */
    public void performValidationWithDebounce(String content, long delayMs) {
        if (!validationEnabled || !schemaManager.isLintingEnabled()) {
            return;
        }

        // Check cache first for immediate feedback
        ValidationResult cachedResult = resultCache.get(content);
        if (cachedResult != null) {
            cacheHits++;
            currentValidationResult = cachedResult;
            notifyValidationCompleted(cachedResult);
            return;
        }

        // Submit debounced validation
        validationExecutor.submitValidationWithDebounce(content, this::performValidationInternal, new AsyncValidationExecutor.ValidationCallback() {
            @Override
            public void onValidationCompleted(ValidationResult result) {
                currentValidationResult = result;
                totalValidations++;
                resultCache.put(content, result);
                notifyValidationCompleted(result);
            }

            @Override
            public void onValidationCancelled() {
                logger.debug("Debounced validation cancelled");
                if (resultHandler != null) {
                    resultHandler.onValidationCancelled();
                }
            }

            @Override
            public void onValidationError(Exception error) {
                logger.error("Debounced validation error", error);
                if (resultHandler != null) {
                    resultHandler.onValidationError(error);
                }
            }
        }, delayMs);
    }

    /**
     * Internal validation method with performance monitoring.
     */
    private ValidationResult performValidationInternal(String content) throws Exception {
        long startTime = System.nanoTime();

        // Start memory tracking
        memoryMonitor.startValidationMemoryTracking();

        try {
            ValidationResult result;

            // Use optimized parsing for large files
            if (OptimizedParser.shouldUseOptimizedParsing(content)) {
                logger.debug("Using optimized parsing for large content");
                var parsedModel = OptimizedParser.parseOptimized(content);
                result = linter.validateParsedModel(parsedModel);
            } else {
                // Use incremental validation for smaller files
                result = incrementalValidator.validateIncremental(content);
            }

            return result;

        } finally {
            // End memory tracking
            memoryMonitor.endValidationMemoryTracking();

            // Update performance metrics
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            updateAverageValidationTime(durationMs);

            logger.debug("Validation completed in {} ms", durationMs);
        }
    }

    /**
     * Clear all validation state and caches.
     */
    public void clearValidation() {
        validationExecutor.cancelCurrentValidation();
        resultCache.clear();
        incrementalValidator.clearCache();

        currentValidationResult = new ValidationResult();
        notifyValidationCompleted(currentValidationResult);
    }

    /**
     * Get current validation result.
     */
    public ValidationResult getCurrentValidationResult() {
        return currentValidationResult;
    }

    /**
     * Enable or disable validation.
     */
    public void setValidationEnabled(boolean enabled) {
        this.validationEnabled = enabled;
        if (!enabled) {
            clearValidation();
        }
    }

    /**
     * Check if validation is enabled.
     */
    public boolean isValidationEnabled() {
        return validationEnabled;
    }

    /**
     * Check if validation is currently running.
     */
    public boolean isValidationRunning() {
        return validationExecutor.isValidationRunning();
    }

    /**
     * Get comprehensive performance statistics.
     */
    public PerformanceStats getPerformanceStats() {
        var cacheStats = resultCache.getStats();
        var memoryStats = memoryMonitor.getCurrentMemoryStats();

        return new PerformanceStats(
            totalValidations,
            cacheStats.hits,
            cacheStats.misses,
            cacheStats.hitRatio,
            averageValidationTimeMs,
            validationExecutor.getQueueSize(),
            validationExecutor.getActiveThreadCount(),
            memoryStats
        );
    }

    public static class PerformanceStats {
        public final long totalValidations;
        public final long cacheHits;
        public final long cacheMisses;
        public final double cacheHitRatio;
        public final long averageValidationTimeMs;
        public final int queueSize;
        public final int activeThreads;
        public final MemoryMonitor.MemoryStats memoryStats;

        public PerformanceStats(long totalValidations, long cacheHits, long cacheMisses,
                              double cacheHitRatio, long averageValidationTimeMs,
                              int queueSize, int activeThreads,
                              MemoryMonitor.MemoryStats memoryStats) {
            this.totalValidations = totalValidations;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.cacheHitRatio = cacheHitRatio;
            this.averageValidationTimeMs = averageValidationTimeMs;
            this.queueSize = queueSize;
            this.activeThreads = activeThreads;
            this.memoryStats = memoryStats;
        }

        @Override
        public String toString() {
            return String.format("PerformanceStats{validations=%d, cache=%.1f%% (%d hits), avgTime=%dms, queue=%d, threads=%d, %s}",
                               totalValidations, cacheHitRatio * 100, cacheHits,
                               averageValidationTimeMs, queueSize, activeThreads, memoryStats);
        }
    }

    /**
     * Force garbage collection if memory pressure is high.
     */
    public void optimizeMemoryUsage() {
        var memoryStats = memoryMonitor.getCurrentMemoryStats();
        if (memoryStats.memoryPressure > 0.8) {
            logger.info("High memory pressure detected ({}%), forcing GC", memoryStats.memoryPressure * 100);
            memoryMonitor.forceGarbageCollection();

            // Also clear some cache entries to free memory
            resultCache.clear();
        }
    }

    private void notifyValidationCompleted(ValidationResult result) {
        if (resultHandler != null) {
            SwingUtilities.invokeLater(() -> resultHandler.onValidationCompleted(result));
        }
    }

    private void updateAverageValidationTime(long durationMs) {
        // Simple moving average
        averageValidationTimeMs = (averageValidationTimeMs + durationMs) / 2;
    }

    private void logPerformanceStats() {
        PerformanceStats stats = getPerformanceStats();
        logger.info("Performance: {}", stats);
    }

    /**
     * Clean up all resources.
     */
    public void dispose() {
        validationExecutor.shutdown();
        resultCache.shutdown();
        memoryMonitor.shutdown();
        logger.info("PerformanceLinterOrchestrator disposed");
    }
}