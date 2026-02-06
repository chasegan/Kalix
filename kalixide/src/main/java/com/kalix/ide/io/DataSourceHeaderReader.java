package com.kalix.ide.io;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Interface for extracting series names from different data file types.
 * Implementations read only the header/metadata to determine available series names,
 * without loading the full data content.
 */
public interface DataSourceHeaderReader {

    /**
     * Returns true if this reader can handle the given file name (based on extension).
     */
    boolean canRead(String fileName);

    /**
     * Reads the series names from the file's header or metadata.
     *
     * @param file the data file to read
     * @return list of cleansed series names
     * @throws IOException if the file cannot be read
     */
    List<String> readSeriesNames(File file) throws IOException;

    /**
     * Cleanses a name (file name or column name) for use as a data reference.
     * Trims whitespace and replaces all non-alphanumeric characters with underscores.
     */
    static String cleanseName(String name) {
        return name.trim().replaceAll("[^a-zA-Z0-9]", "_");
    }
}
