package com.kalix.ide.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** The model files in this repository, for tests that hold two readings of one grammar together. */
public final class RepositoryModels {

    private RepositoryModels() {
    }

    /** Every {@code .ini} under the repository, excluding build output, in path order. */
    public static List<Path> all() throws IOException {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(repo)) {
            return paths.filter(p -> p.toString().endsWith(".ini"))
                        .filter(p -> !p.toString().contains("/target/")
                                  && !p.toString().contains("/build/")
                                  && !p.toString().contains("/site/"))
                        .sorted()
                        .toList();
        }
    }
}
