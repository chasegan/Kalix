package com.kalix.ide.filedialog;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the shared CSV filters every data dialog offers. */
class FileDialogFilterTest {

    @Test
    void csvThenListsPlainThenEachCompressedFormatThenTheRest() {
        FileDialogFilter pixie = FileDialogFilter.of("Pixie Files (*.pxt)", "pxt");
        List<String> labels = Arrays.stream(FileDialogFilter.csvThen(pixie)).map(FileDialogFilter::label).toList();
        assertEquals(List.of("CSV Files (*.csv)", "Zipped CSV (*.csv.zip)", "Gzipped CSV (*.csv.gz)",
            "Pixie Files (*.pxt)"), labels);
    }

    @Test
    void allSupportedLeavesOutExtensionsAlreadyCovered() {
        FileDialogFilter[] filters = FileDialogFilter.withAllSupported(FileDialogFilter.csvThen(
            FileDialogFilter.of("Source Result CSV (*.res.csv)", "res.csv"),
            FileDialogFilter.of("Pixie Files (*.pxt)", "pxt")));
        assertEquals("All Supported (*.csv, *.csv.zip, *.csv.gz, *.pxt)", filters[0].label());
        assertEquals(List.of("csv", "csv.zip", "csv.gz", "pxt"), filters[0].extensions());
        assertEquals(6, filters.length);
    }
}
