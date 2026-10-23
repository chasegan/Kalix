package com.kalix.ide.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip archives on disk: the file tree's Zip and Unzip, and the {@code .csv.zip} entry check.
 * Pure I/O, no Swing. Entry names use '/' on every platform, and a folder goes in under its
 * own name so that unzipping gives the folder back.
 */
public final class ZipArchive {

    private ZipArchive() {
    }

    /**
     * Writes {@code zip} holding each target: a file as a top-level entry under its own name, a
     * folder recursively under its name, empty subfolders included. The archive being written
     * is skipped if it lies inside a target folder.
     */
    public static void create(File zip, List<File> targets) throws IOException {
        Path zipPath = zip.toPath().toAbsolutePath().normalize();
        try (ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            for (File target : targets) {
                Path path = target.toPath().toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    addFolder(out, path, zipPath);
                } else {
                    addFile(out, path, path.getFileName().toString());
                }
            }
        }
    }

    private static void addFolder(ZipOutputStream out, Path folder, Path skip) throws IOException {
        Path base = folder.getParent();
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                ZipEntry entry = new ZipEntry(entryName(base, dir) + "/");
                entry.setLastModifiedTime(attrs.lastModifiedTime());
                out.putNextEntry(entry);
                out.closeEntry();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.equals(skip)) {
                    addFile(out, file, entryName(base, file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void addFile(ZipOutputStream out, Path file, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(Files.getLastModifiedTime(file));
        out.putNextEntry(entry);
        Files.copy(file, out);
        out.closeEntry();
    }

    private static String entryName(Path base, Path path) {
        return base.relativize(path).toString().replace(File.separatorChar, '/');
    }

    /**
     * Names of the archive's file entries, directories skipped. Reads only the central
     * directory, so it is quick even for a large archive.
     */
    public static List<String> fileEntryNames(File zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    /**
     * Extracts every entry into {@code targetDir}, overwriting files already there and keeping
     * the entries' modification times. An entry whose name would land outside
     * {@code targetDir} ("../x", an absolute path) is refused, and extraction stops there.
     */
    public static void extractAll(File zip, File targetDir) throws IOException {
        Path target = targetDir.toPath().toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zip.toPath())))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path dest = target.resolve(entry.getName()).normalize();
                if (!dest.startsWith(target)) {
                    throw new IOException(String.format(
                        "'%s': entry '%s' would land outside %s",
                        zip.getName(), entry.getName(), targetDir.getName()));
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                FileTime time = entry.getLastModifiedTime();
                if (time != null) {
                    Files.setLastModifiedTime(dest, time);
                }
            }
        }
    }
}
