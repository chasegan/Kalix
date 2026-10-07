package com.kalix.ide.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.HeadlessException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogUtilsTest {

    @AfterEach
    void clearSink() {
        DialogUtils.setErrorSink(null);
    }

    @Test
    void showErrorRecordsTheMessageBeforeOpeningTheDialog() {
        List<String> recorded = new ArrayList<>();
        DialogUtils.setErrorSink(recorded::add);

        try {
            DialogUtils.showError(null, "Could not read the file", "Compare");
        } catch (HeadlessException expected) {
            // tests run headless: there is no dialog to show, but the sink was reached first
        }

        assertEquals(List.of("Could not read the file"), recorded);
    }
}
