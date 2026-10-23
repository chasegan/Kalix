package com.kalix.ide.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip archives on disk: the file tree's Zip and Unzip, and the {@code .csv.zip} entry check.
 * Pure I/O, no Swing. Entry names use '/' on every platform, and a folder goes in under its
 * path from the folder shared by every target, so that unzipping there gives it back.
 */
public final class ZipArchive {

    private ZipArchive() {
    }

    /**
     * Writes {@code zip} holding the targets, named relative to the nearest folder that holds
     * them all: {@code x.csv} when every target sits in one folder, {@code a/x.csv} and
     * {@code b/x.csv} when they do not. A folder goes in recursively, empty subfolders included
     * and symbolic links followed; a link whose target is missing fails the archive. Targets
     * must not overlap or repeat, or two entries share a name and the archive fails. The
     * archive being written is skipped if it lies inside a target. An existing file at
     * {@code zip} is refused, and on failure no archive is left behind.
     */
    public static void create(File zip, List<File> targets) throws IOException {
        Path zipPath = zip.toPath().toAbsolutePath().normalize();
        List<Path> paths = targets.stream().map(t -> t.toPath().toAbsolutePath().normalize()).toList();
        Path base = commonParent(paths);
        // Opened before the try so that an existing file fails here and is not deleted below.
        OutputStream file = Files.newOutputStream(zipPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(file))) {
            for (Path path : paths) {
                if (Files.isDirectory(path)) {
                    addFolder(out, base, path, zipPath);
                } else {
                    addFile(out, path, entryName(base, path), Files.getLastModifiedTime(path));
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(zipPath);
            throw e;
        }
    }

    /** The deepest folder containing every path. */
    private static Path commonParent(List<Path> paths) throws IOException {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Nothing to archive");
        }
        Path common = paths.get(0).getParent();
        for (Path path : paths) {
            Path parent = path.getParent();
            while (common != null && (parent == null || !parent.startsWith(common))) {
                common = common.getParent();
            }
        }
        if (common == null) {
            throw new IOException("The files share no folder, so cannot share one archive");
        }
        return common;
    }

    private static void addFolder(ZipOutputStream out, Path base, Path folder, Path skip) throws IOException {
        Files.walkFileTree(folder, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
            new SimpleFileVisitor<>() {
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
                    if (attrs.isSymbolicLink()) {
                        // Links are followed, so this is only true when the target is missing.
                        throw new NoSuchFileException(file.toString(), null, "the symbolic link's target does not exist");
                    }
                    if (!file.equals(skip)) {
                        addFile(out, file, entryName(base, file), attrs.lastModifiedTime());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    if (exc instanceof FileSystemLoopException) {
                        return FileVisitResult.CONTINUE; // a link back into the tree: already archived
                    }
                    throw exc;
                }
            });
    }

    private static void addFile(ZipOutputStream out, Path file, String name, FileTime modified) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(modified);
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
