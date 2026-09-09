package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the file tree's gz compression: target naming (the zip collision rule,
 * suffix stripping), the round trip, type detection for the Compress /
 * Decompress menu items, and multi-member gzip (a decompress must read every
 * member, like gunzip and the engine).
 */
class GzCompressionTest {

    @TempDir
    Path tempDir;

    private File write(String name, String content) throws IOException {
        File f = tempDir.resolve(name).toFile();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void gzipTargetAppendsTheSuffixAndDodgesCollisions() throws IOException {
        File src = write("flows.csv", "x");
        assertEquals("flows.csv.gz", TreeFileOperations.gzipTarget(src).getName());

        write("flows.csv.gz", "occupied");
        assertEquals("flows.csv (1).gz", TreeFileOperations.gzipTarget(src).getName(),
            "the zip collision rule: (n) before the new extension");
    }

    @Test
    void gunzipTargetStripsTheSuffix() {
        File dir = tempDir.toFile();
        assertEquals("flows.csv",
            TreeFileOperations.gunzipTarget(new File(dir, "flows.csv.gz")).getName());
        assertEquals("flows.csv",
            TreeFileOperations.gunzipTarget(new File(dir, "flows.csv.GZ")).getName(),
            "suffix detection is case-insensitive, so stripping must be too");
        assertEquals("decompressed",
            TreeFileOperations.gunzipTarget(new File(dir, ".gz")).getName(),
            "a file named nothing but the suffix gets a visible name, not \"\"");
    }

    @Test
    void gzipThenGunzipRoundTripsContent() throws IOException {
        String content = "Date,flow\n2020-01-01,1.5\n";
        File src = write("flows.csv", content);
        File gz = TreeFileOperations.gzipTarget(src);
        TreeFileOperations.gzipTo(src, gz);
        assertTrue(gz.exists());

        File back = tempDir.resolve("back.csv").toFile();
        TreeFileOperations.gunzipTo(gz, back);
        assertEquals(content, Files.readString(back.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void multiMemberGzipDecompressesEveryMember() throws IOException {
        // cat a.gz b.gz is one valid gzip file (RFC 1952); a single-member
        // decode would silently truncate it.
        File a = write("a.txt", "first ");
        File b = write("b.txt", "second");
        File ga = tempDir.resolve("a.txt.gz").toFile();
        File gb = tempDir.resolve("b.txt.gz").toFile();
        TreeFileOperations.gzipTo(a, ga);
        TreeFileOperations.gzipTo(b, gb);

        File multi = tempDir.resolve("multi.txt.gz").toFile();
        byte[] joined = new byte[(int) (ga.length() + gb.length())];
        System.arraycopy(Files.readAllBytes(ga.toPath()), 0, joined, 0, (int) ga.length());
        System.arraycopy(Files.readAllBytes(gb.toPath()), 0, joined, (int) ga.length(), (int) gb.length());
        Files.write(multi.toPath(), joined);

        File out = tempDir.resolve("multi.txt").toFile();
        TreeFileOperations.gunzipTo(multi, out);
        assertEquals("first second", Files.readString(out.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void typeDetectionForTheMenuItems() {
        File dir = tempDir.toFile();
        assertTrue(TreeFileOperations.isGz(new File(dir, "flows.csv.gz")));
        assertTrue(TreeFileOperations.isGz(new File(dir, "FLOWS.CSV.GZ")));
        assertFalse(TreeFileOperations.isGz(new File(dir, "flows.csv")));
        assertTrue(TreeFileOperations.isCompressed(new File(dir, "archive.zip")));
        assertTrue(TreeFileOperations.isCompressed(new File(dir, "flows.csv.gz")));
        assertFalse(TreeFileOperations.isCompressed(new File(dir, "flows.csv")));
    }
}
