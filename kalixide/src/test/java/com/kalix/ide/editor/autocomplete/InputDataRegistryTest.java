package com.kalix.ide.editor.autocomplete;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InputDataRegistry} caching behaviour: header reads populate
 * the cache, missing files are negative-cached (so completion popups stop
 * resubmitting reads), and entries removed from {@code [data]} never
 * reappear via a stale pending read.
 */
class InputDataRegistryTest {

    @TempDir
    Path baseDir;

    private InputDataRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.dispose();
        }
    }

    private InputDataRegistry newRegistry() {
        registry = new InputDataRegistry(() -> baseDir.toFile());
        return registry;
    }

    /** Polls until the condition holds or a generous timeout elapses. */
    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return;  // let the caller's assertion report the failure
            }
            Thread.sleep(10);
        }
    }

    @Test
    void readsHeadersOfAnExistingCsv() throws IOException, InterruptedException {
        Files.writeString(baseDir.resolve("flows.csv"), "date,gauge_a,gauge_b\n2020-01-01,1,2\n");
        InputDataRegistry reg = newRegistry();

        reg.refresh(List.of("flows.csv"));
        awaitTrue(() -> reg.getDataSources().containsKey("flows.csv"));

        InputDataRegistry.CachedDataSource cached = reg.getDataSources().get("flows.csv");
        assertNotNull(cached);
        assertEquals(List.of("gauge_a", "gauge_b"), cached.getSeriesNames());
    }

    @Test
    void missingFileIsNegativeCachedInsteadOfResubmittedForever() throws InterruptedException {
        InputDataRegistry reg = newRegistry();

        reg.refresh(List.of("no_such.csv"));
        awaitTrue(() -> reg.getDataSources().containsKey("no_such.csv"));

        InputDataRegistry.CachedDataSource cached = reg.getDataSources().get("no_such.csv");
        assertNotNull(cached, "a missing file must be negative-cached");
        assertTrue(cached.getSeriesNames().isEmpty());

        // A same-list refresh must keep the negative entry (timestamp 0 == 0);
        // before negative caching this resubmitted the read on every popup.
        reg.refresh(List.of("no_such.csv"));
        Thread.sleep(50);
        assertTrue(reg.getDataSources().containsKey("no_such.csv"));
    }

    @Test
    void negativeEntryIsRereadWhenTheFileAppears() throws IOException, InterruptedException {
        InputDataRegistry reg = newRegistry();

        reg.refresh(List.of("late.csv"));
        awaitTrue(() -> reg.getDataSources().containsKey("late.csv"));

        Files.writeString(baseDir.resolve("late.csv"), "date,series_x\n2020-01-01,1\n");
        reg.refresh(List.of("late.csv"));  // timestamp changed: 0 -> real mtime
        awaitTrue(() -> {
            InputDataRegistry.CachedDataSource c = reg.getDataSources().get("late.csv");
            return c != null && !c.getSeriesNames().isEmpty();
        });

        assertEquals(List.of("series_x"), reg.getDataSources().get("late.csv").getSeriesNames());
    }

    @Test
    void fileRemovedFromInputsDoesNotReappearFromAPendingRead() throws IOException, InterruptedException {
        Files.writeString(baseDir.resolve("gone.csv"), "date,s1\n2020-01-01,1\n");
        InputDataRegistry reg = newRegistry();

        // Queue the read, then immediately drop the file from the list. The
        // pending read must not re-insert the entry after the removal.
        reg.refresh(List.of("gone.csv"));
        reg.refresh(List.of());

        Thread.sleep(200);  // let any queued read run to completion
        assertNull(reg.getDataSources().get("gone.csv"),
            "a stale pending read must not resurrect a removed [data] entry");
    }
}
