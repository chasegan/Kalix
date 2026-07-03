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
     * Cleanses a name (file name or column name) into the engine's {@code data.*}
     * reference form. Trims whitespace (as the engine's CSV reader does for
     * headers, {@code src/io/csv_io.rs}) and then applies the engine's
     * sanitisation rule via {@link com.kalix.ide.utils.EngineNames#sanitize}.
     */
    static String cleanseName(String name) {
        return com.kalix.ide.utils.EngineNames.sanitize(name.trim());
    }
}
