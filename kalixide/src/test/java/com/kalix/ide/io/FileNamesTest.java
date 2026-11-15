package com.kalix.ide.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileNamesTest {

    @TempDir
    File dir;

    @Test
    void aCompressedCsvOrSourceResultHasOneExtension() {
        assertEquals(".csv.gz", FileNames.suffixOf("flows.csv.gz"));
        assertEquals(".CSV.ZIP", FileNames.suffixOf("FLOWS.CSV.ZIP"), "the name's own case");
        assertEquals(".res.csv", FileNames.suffixOf("results.res.csv"));
        assertEquals(".csv", FileNames.suffixOf("flows.csv"));
        assertEquals(".gz", FileNames.suffixOf("archive.tar.gz"));
        assertEquals(".zip", FileNames.suffixOf("results.res.csv.zip"), "not a compressed CSV");
        assertEquals("", FileNames.suffixOf("README"));
        assertEquals("", FileNames.suffixOf(".gitignore"));
        assertEquals("", FileNames.suffixOf(".csv.gz"), "a name that is only the extension");
    }

    @Test
    void theCounterGoesBeforeTheWholeSuffix() throws IOException {
        assertEquals("flows.csv.gz", FileNames.free(dir, "flows", ".csv.gz").getName());
        new File(dir, "flows.csv.gz").createNewFile();
        assertEquals("flows (1).csv.gz", FileNames.free(dir, "flows", ".csv.gz").getName());
    }
}
