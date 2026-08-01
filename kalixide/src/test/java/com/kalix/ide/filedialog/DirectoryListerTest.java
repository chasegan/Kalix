package com.kalix.ide.filedialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file dialogs' background enumeration: attributes captured in the single listing pass,
 * results sorted per the tree's deliberate ordering (dirs first, hidden first, natural), and
 * delivery on the EDT. Pure I/O plus the EDT; no display required.
 */
class DirectoryListerTest {

    @Test
    void listsWithAttributesSortedAndOnTheEdt(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("catchments"));
        Files.createDirectory(dir.resolve(".config"));
        Files.writeString(dir.resolve("model2.ini"), "x");
        Files.writeString(dir.resolve("model10.ini"), "yy");
        Files.writeString(dir.resolve(".hidden.txt"), "z");

        DirectoryLister lister = new DirectoryLister();
        try {
            AtomicReference<List<FsEntry>> got = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>("unset");
            AtomicReference<Boolean> onEdt = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            lister.list(dir, got::set, e -> {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                error.set(e);
                done.countDown();
            });

            assertTrue(done.await(10, TimeUnit.SECONDS), "listing did not complete");
            assertNull(error.get());
            assertEquals(Boolean.TRUE, onEdt.get(), "completion must arrive on the EDT");

            List<FsEntry> entries = got.get();
            assertNotNull(entries);
            // Dirs first, hidden first within group, natural name order (model2 < model10).
            assertEquals(List.of(".config", "catchments", ".hidden.txt", "model2.ini", "model10.ini"),
                entries.stream().map(FsEntry::name).toList());

            FsEntry model2 = entries.stream().filter(e -> e.name().equals("model2.ini")).findFirst().orElseThrow();
            assertEquals(1, model2.size(), "size must come from the enumeration attributes");
            assertTrue(entries.stream().filter(e -> e.name().equals("catchments")).findFirst().orElseThrow().directory());
            assertTrue(model2.lastModified() > 0);
        } finally {
            lister.dispose();
        }
    }

    @Test
    void reportsUnreadableDirectoryAsError(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("nope");
        DirectoryLister lister = new DirectoryLister();
        try {
            AtomicReference<String> error = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            lister.list(missing, batch -> { }, e -> {
                error.set(e);
                done.countDown();
            });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNotNull(error.get(), "a missing directory must surface an error message");
        } finally {
            lister.dispose();
        }
    }

    /**
     * Cancelling suppresses every later delivery, batches and completion alike.
     *
     * <p>Two details make this deterministic rather than merely usually true, and both are
     * load-bearing — an earlier version of this test had neither and could not detect the
     * loss of the check it was written to cover.
     *
     * <p><b>The cancel happens on the EDT</b>, inside the first batch. Deliveries are
     * dispatched with {@code invokeLater}, so the EDT processes them in order: cancelling
     * inside the first guarantees every later one observes the flag, whatever the worker
     * was doing meanwhile. Cancelling from the test thread instead would merely race it.
     *
     * <p><b>A second listing is the drain barrier</b>, in place of sleeping. The lister's
     * executor is single-threaded and FIFO, so this listing cannot begin until the first
     * walk has finished, and its completion is therefore queued on the EDT behind every
     * delivery the first walk made. Awaiting it drains them exactly — no timing assumption,
     * nothing to tune, and it cannot pass vacuously by being too slow to notice a failure.
     */
    @Test
    void cancellingStopsFurtherDeliveries(@TempDir Path dir) throws Exception {
        // Comfortably past two batches (BATCH_SIZE is 128), so deliveries certainly follow
        // the one that cancels — otherwise there would be nothing to prove suppressed.
        Path listing = Files.createDirectory(dir.resolve("listing"));
        for (int i = 0; i < 300; i++) {
            Files.writeString(listing.resolve("model" + i + ".ini"), "x");
        }
        Path sentinelDir = Files.createDirectory(dir.resolve("sentinel"));

        DirectoryLister lister = new DirectoryLister();
        try {
            AtomicInteger batches = new AtomicInteger();
            AtomicInteger completions = new AtomicInteger();
            AtomicReference<DirectoryLister.Handle> handle = new AtomicReference<>();

            // Started on the EDT so the handle is published before any callback can read
            // it. Assigning from the test thread races the first batch, and the resulting
            // NPE would surface as a timeout blaming the listing instead.
            SwingUtilities.invokeAndWait(() -> handle.set(lister.list(listing,
                batch -> {
                    batches.incrementAndGet();
                    handle.get().cancel();
                },
                e -> completions.incrementAndGet())));

            CountDownLatch drained = new CountDownLatch(1);
            lister.list(sentinelDir, batch -> { }, e -> drained.countDown());
            assertTrue(drained.await(10, TimeUnit.SECONDS), "the sentinel listing must complete");

            assertEquals(1, batches.get(),
                "only the batch that cancelled may be delivered");
            assertEquals(0, completions.get(),
                "completion must not fire for a cancelled listing");
        } finally {
            lister.dispose();
        }
    }

    @Test
    void filterAcceptsBySuffixAndAlwaysAcceptsDirectories(@TempDir Path dir) {
        FileDialogFilter ini = FileDialogFilter.of("Models", "ini");
        FsEntry model = new FsEntry(dir.resolve("m.INI"), "m.INI", false, 1, 1);
        FsEntry csv = new FsEntry(dir.resolve("d.csv"), "d.csv", false, 1, 1);
        FsEntry folder = new FsEntry(dir.resolve("f"), "f", true, 0, 1);
        assertTrue(ini.accepts(model));
        assertTrue(!ini.accepts(csv));
        assertTrue(ini.accepts(folder));
        assertTrue(FileDialogFilter.ALL_FILES.accepts(csv));

        FileDialogFilter res = FileDialogFilter.of("Source results", "res.csv");
        FsEntry resFile = new FsEntry(dir.resolve("r.res.csv"), "r.res.csv", false, 1, 1);
        assertTrue(res.accepts(resFile));
        assertTrue(!res.accepts(csv));
    }
}
