package com.kalix.ide.filedialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void cancelledHandleStaysSilent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.ini"), "x");
        DirectoryLister lister = new DirectoryLister();
        try {
            CountDownLatch spoke = new CountDownLatch(1);
            DirectoryLister.Handle handle = lister.list(dir,
                batch -> spoke.countDown(), e -> spoke.countDown());
            handle.cancel();
            // Give the worker and EDT queue ample time to (not) deliver.
            assertTrue(!spoke.await(500, TimeUnit.MILLISECONDS),
                "cancelled listing must not deliver callbacks");
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
