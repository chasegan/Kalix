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
     * Cancelling stops delivery from that point on.
     *
     * <p>Note what is <em>not</em> claimed: that a handle cancelled from another thread
     * delivers nothing at all. {@code deliver} tests the flag on the worker and again on the
     * EDT, so a batch already past both checks still arrives — cancelling from the test
     * thread races the worker and cannot win reliably. An earlier version of this test
     * asserted the absolute and duly failed on a loaded CI machine roughly one run in ten.
     *
     * <p>So the cancel happens <em>on the EDT, inside the first batch</em>. The EDT is
     * single-threaded, so every later delivery must run after it and must see the flag —
     * which makes this deterministic rather than merely probable. Enough files to force
     * several batches (BATCH_SIZE is 128) guarantee there are later deliveries to suppress.
     */
    @Test
    void cancellingStopsFurtherDeliveries(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 500; i++) {
            Files.writeString(dir.resolve("model" + i + ".ini"), "x");
        }
        DirectoryLister lister = new DirectoryLister();
        try {
            AtomicInteger batches = new AtomicInteger();
            AtomicInteger completions = new AtomicInteger();
            CountDownLatch firstBatch = new CountDownLatch(1);
            AtomicReference<DirectoryLister.Handle> handle = new AtomicReference<>();

            handle.set(lister.list(dir,
                batch -> {
                    batches.incrementAndGet();
                    handle.get().cancel();   // on the EDT: every later delivery sees this
                    firstBatch.countDown();
                },
                e -> completions.incrementAndGet()));

            assertTrue(firstBatch.await(10, TimeUnit.SECONDS), "the first batch must arrive");
            int seenAtCancel = batches.get();

            // Let the worker finish and drain anything it queued behind the cancel.
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> { });

            assertEquals(seenAtCancel, batches.get(),
                "no batch may be delivered after the handle was cancelled");
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
