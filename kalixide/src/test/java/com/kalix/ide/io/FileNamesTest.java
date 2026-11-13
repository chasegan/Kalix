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
    void theCounterGoesBeforeTheWholeSuffix() throws IOException {
        assertEquals("flows.csv.gz", FileNames.free(dir, "flows", ".csv.gz").getName());
        new File(dir, "flows.csv.gz").createNewFile();
        assertEquals("flows (1).csv.gz", FileNames.free(dir, "flows", ".csv.gz").getName());
    }
}
