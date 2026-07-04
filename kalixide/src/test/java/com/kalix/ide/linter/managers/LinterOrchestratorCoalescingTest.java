package com.kalix.ide.linter.managers;

import com.kalix.ide.linter.ModelLinter;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.model.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies superseded-validation skipping in LinterOrchestrator (review #48):
 * validations queued behind a slow one must be coalesced - only the newest
 * generation runs; stale generations return immediately. Without this, slow
 * validations (FileValidator disk stats) build an unbounded backlog.
 */
class LinterOrchestratorCoalescingTest {

    /** SchemaManager stub that reports linting enabled without touching preferences. */
    private static final class EnabledSchemaManager extends SchemaManager {
        @Override
        public boolean isLintingEnabled() {
            return true;
        }
    }

    private LinterOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.dispose();
        }
    }

    @Test
    void queuedStaleValidationsAreSkipped() throws Exception {
        SchemaManager schemaManager = new EnabledSchemaManager();

        AtomicInteger validateCalls = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        ModelLinter slowLinter = new ModelLinter(schemaManager) {
            @Override
            public ValidationResult validate(String content, File baseDirectory) {
                int call = validateCalls.incrementAndGet();
                if (call == 1) {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ValidationResult();
            }
        };

        orchestrator = new LinterOrchestrator(schemaManager, slowLinter);

        CountDownLatch twoCompletions = new CountDownLatch(2);
        orchestrator.setValidationResultHandler(result -> twoCompletions.countDown());

        // First validation starts and blocks inside validate()
        orchestrator.performValidation("v1", null);
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "first validation must start");

        // Five more requests pile up while the first is running
        for (int i = 2; i <= 6; i++) {
            orchestrator.performValidation("v" + i, null);
        }
        releaseFirst.countDown();

        // Two completions reach the handler: the in-flight first validation and
        // the newest queued one. Generations 2-5 must be skipped, not validated.
        assertTrue(twoCompletions.await(10, TimeUnit.SECONDS),
                "in-flight and newest validations must complete");
        assertEquals(2, validateCalls.get(),
                "stale queued validations must be skipped (only in-flight + newest run)");
    }

    @Test
    void resultIsVisibleAfterCompletion() throws Exception {
        SchemaManager schemaManager = new EnabledSchemaManager();
        ModelLinter linter = new ModelLinter(schemaManager) {
            @Override
            public ValidationResult validate(String content, File baseDirectory) {
                return new ValidationResult();
            }
        };
        orchestrator = new LinterOrchestrator(schemaManager, linter);

        CountDownLatch completed = new CountDownLatch(1);
        orchestrator.setValidationResultHandler(result -> completed.countDown());

        orchestrator.performValidation("model", null);
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertTrue(orchestrator.getCurrentValidationResult().isEmpty(),
                "completed result must be visible from another thread");
    }
}
