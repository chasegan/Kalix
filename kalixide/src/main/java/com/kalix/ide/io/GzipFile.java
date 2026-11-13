package com.kalix.ide.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Single-file gzip on disk: the file tree's Gzip and Gunzip. Pure I/O, no Swing.
 * Byte copies, so the CSV is never decoded and no memory limit applies. Output is written
 * reproducibly (the JDK's gzip header carries MTIME 0 and no file name), through a
 * {@link StagedFile}, so it appears only once complete.
 */
public final class GzipFile {

    private static final String EXTENSION = ".gz";
    private static final String CSV = ".csv";

    private GzipFile() {
    }

    /** Whether {@code file}'s name ends {@code .gz} (any case) with something before it. */
    public static boolean isGzip(File file) {
        String name = file.getName();
        return name.length() > EXTENSION.length() && name.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /**
     * Where Gzip writes {@code csv}: {@code flows.csv.gz} beside it, or {@code flows (1).csv.gz}
     * and so on when taken. The name keeps its own {@code .csv} spelling, so Gunzip gives the
     * original name back ({@code FLOWS.CSV} makes {@code FLOWS.CSV.gz}).
     */
    public static File compressedTarget(File csv) {
        String name = csv.getName();
        int stemLength = name.toLowerCase(Locale.ROOT).endsWith(CSV) ? name.length() - CSV.length() : name.length();
        return FileNames.free(csv.getParentFile(), name.substring(0, stemLength),
            name.substring(stemLength) + EXTENSION);
    }

    /** Where Gunzip writes {@code gz}: its name minus {@code .gz}, beside it. */
    public static File decompressedTarget(File gz) {
        String name = gz.getName();
        return new File(gz.getParentFile(), name.substring(0, name.length() - EXTENSION.length()));
    }

    /** Gzips {@code src} to {@code dst}. An existing {@code dst} is refused. */
    public static void compress(File src, File dst) throws IOException {
        StagedFile.write(dst.toPath(), false, out -> {
            try (InputStream in = Files.newInputStream(src.toPath());
                 OutputStream gz = new GZIPOutputStream(out, CompressedCsvFormat.GZIP_BUFFER_BYTES)) {
                in.transferTo(gz);
            }
        });
    }

    /**
     * Gunzips {@code src} to {@code dst}, replacing an existing file at {@code dst} only once the
     * whole stream has decompressed, so a corrupt archive never costs the file it would replace.
     * A folder at {@code dst} is refused. Concatenated members are read as one stream, as gzip does.
     */
    public static void decompress(File src, File dst) throws IOException {
        StagedFile.write(dst.toPath(), true, out -> {
            try (InputStream in = CompressedCsvFormat.gunzip(Files.newInputStream(src.toPath()))) {
                in.transferTo(out);
            }
        });
    }
}
