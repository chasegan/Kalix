package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusReporterTest {

    @Test
    void statusAndErrorGoToTheirOwnChannels() {
        List<String> status = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        StatusReporter reporter = StatusReporter.of(status::add, errors::add);

        reporter.accept("opened");
        reporter.error("failed");

        assertEquals(List.of("opened"), status);
        assertEquals(List.of("failed"), errors);
    }

    @Test
    void statusOnlyShowsErrorsAsStatus() {
        List<String> status = new ArrayList<>();
        StatusReporter reporter = StatusReporter.statusOnly(status::add);

        reporter.accept("opened");
        reporter.error("failed");

        assertEquals(List.of("opened", "failed"), status);
    }
}
