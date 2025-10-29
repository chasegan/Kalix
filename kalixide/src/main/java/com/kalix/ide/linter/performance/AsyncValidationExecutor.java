package com.kalix.ide.linter.performance;

import com.kalix.ide.linter.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance async validation executor with cancellation support.
 * Handles validation queueing, cancellation, and result delivery.
 */
public class AsyncValidationExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AsyncValidationExecutor.class);

    private final ExecutorService validationExecutor;
    private final ScheduledExecutorService schedulerExecutor;
    private final AtomicReference<Future<?>> currentValidation = new AtomicReference<>();

    // Performance configuration
    private static final int MAX_VALIDATION_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final long VALIDATION_TIMEOUT_MS = 5000; // 5 second timeout

    public interface ValidationTask {
        ValidationResult validate(String content) throws Exception;
    }

    public interface ValidationCallback {
        void onValidationCompleted(ValidationResult result);
        void onValidationCancelled();
        void onValidationError(Exception error);
    }

    public AsyncValidationExecutor() {
        // Create thread pool optimized for CPU-bound validation tasks
        this.validationExecutor = new ThreadPoolExecutor(
            1, MAX_VALIDATION_THREADS,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1), // Small queue to prevent backup
            r -> {
                Thread t = new Thread(r, "AsyncValidation-" + System.currentTimeMillis());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy() // Drop old validations if queue full
        );

        this.schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AsyncValidation-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit validation task with automatic cancellation of previous validation.
     */
    public void submitValidation(String content, ValidationTask task, ValidationCallback callback) {
        // Cancel any existing validation
        cancelCurrentValidation();

        // Create new validation future
        CompletableFuture<ValidationResult> future = CompletableFuture
            .supplyAsync(() -> {
                try {
                    return task.validate(content);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, validationExecutor)
            .orTimeout(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Store reference for potential cancellation
        currentValidation.set(future);

        // Handle completion
        future.whenComplete((result, throwable) -> {
            // Clear current validation reference if this was the active one
            currentValidation.compareAndSet(future, null);

            // Deliver result on EDT
            SwingUtilities.invokeLater(() -> {
                if (throwable != null) {
                    if (throwable instanceof CancellationException || throwable instanceof TimeoutException) {
                        callback.onValidationCancelled();
                    } else {
                        Throwable cause = throwable.getCause();
                        Exception error = (cause instanceof Exception) ? (Exception) cause : new RuntimeException(throwable);
                        callback.onValidationError(error);
                    }
                } else {
                    callback.onValidationCompleted(result);
                }
            });
        });
    }

    /**
     * Submit validation with debouncing - only validate after delay with no new requests.
     */
    public void submitValidationWithDebounce(String content, ValidationTask task,
                                           ValidationCallback callback, long delayMs) {
        // Cancel any existing validation
        cancelCurrentValidation();

        // Schedule validation after delay
        ScheduledFuture<?> scheduledTask = schedulerExecutor.schedule(() -> {
            submitValidation(content, task, callback);
        }, delayMs, TimeUnit.MILLISECONDS);

        // Store scheduled task for cancellation
        currentValidation.set(scheduledTask);
    }

    /**
     * Cancel the currently running or scheduled validation.
     */
    public void cancelCurrentValidation() {
        Future<?> current = currentValidation.getAndSet(null);
        if (current != null && !current.isDone()) {
            current.cancel(true);
        }
    }

    /**
     * Check if validation is currently running.
     */
    public boolean isValidationRunning() {
        Future<?> current = currentValidation.get();
        return current != null && !current.isDone();
    }

    /**
     * Get current queue size (for monitoring).
     */
    public int getQueueSize() {
        if (validationExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) validationExecutor).getQueue().size();
        }
        return 0;
    }

    /**
     * Get current active thread count (for monitoring).
     */
    public int getActiveThreadCount() {
        if (validationExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) validationExecutor).getActiveCount();
        }
        return 0;
    }

    /**
     * Shutdown the executor and wait for completion.
     */
    public void shutdown() {
        cancelCurrentValidation();

        validationExecutor.shutdown();
        schedulerExecutor.shutdown();

        try {
            if (!validationExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                validationExecutor.shutdownNow();
            }
            if (!schedulerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                schedulerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            validationExecutor.shutdownNow();
            schedulerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

    }
}