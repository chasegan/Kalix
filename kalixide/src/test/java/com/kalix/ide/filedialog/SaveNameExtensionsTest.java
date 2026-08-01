package com.kalix.ide.filedialog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save dialog's extension rules. The cases that matter are the multi-part suffixes
 * ({@code .res.csv}) and the boundary where the dialog must stop editing the modeller's
 * name — those are what a "cut at the last dot" implementation gets wrong.
 */
class SaveNameExtensionsTest {

    /** The plot's Save Data types, the richest set in the IDE. */
    private static final List<String> PLOT_TYPES = List.of("csv", "res.csv", "pxt");

    @Nested
    @DisplayName("retarget: the type combo changed")
    class Retarget {

        @Test
        void swapsASimpleExtension() {
            assertEquals("data.pxt", SaveNameExtensions.retarget("data.csv", "pxt", PLOT_TYPES));
        }

        @Test
        @DisplayName("prefers the longest matching suffix, so .res.csv is not read as .csv")
        void stripsTheLongestMatchingSuffix() {
            assertEquals("data.pxt", SaveNameExtensions.retarget("data.res.csv", "pxt", PLOT_TYPES));
        }

        @Test
        @DisplayName("csv -> res.csv -> pxt -> csv round-trips without accumulating")
        void roundTripsAcrossEveryType() {
            String name = "timeseries_data.csv";
            name = SaveNameExtensions.retarget(name, "res.csv", PLOT_TYPES);
            assertEquals("timeseries_data.res.csv", name);
            name = SaveNameExtensions.retarget(name, "pxt", PLOT_TYPES);
            assertEquals("timeseries_data.pxt", name);
            name = SaveNameExtensions.retarget(name, "csv", PLOT_TYPES);
            assertEquals("timeseries_data.csv", name);
        }

        @Test
        @DisplayName("completes a bare name rather than leaving it extensionless")
        void completesANameWithNoExtension() {
            assertEquals("results.csv", SaveNameExtensions.retarget("results", "csv", PLOT_TYPES));
        }

        @Test
        @DisplayName("leaves an extension we do not own alone — the modeller meant it")
        void doesNotOverruleAForeignExtension() {
            assertEquals("notes.txt", SaveNameExtensions.retarget("notes.txt", "csv", PLOT_TYPES));
        }

        @Test
        void matchesExtensionsCaseInsensitively() {
            assertEquals("DATA.pxt", SaveNameExtensions.retarget("DATA.CSV", "pxt", PLOT_TYPES));
        }

        @Test
        void leavesDottedStemsIntact() {
            assertEquals("run.2024.03.pxt",
                SaveNameExtensions.retarget("run.2024.03.csv", "pxt", PLOT_TYPES));
        }

        @Test
        void isANoOpForAnEmptyNameOrAnExtensionlessType() {
            assertEquals("", SaveNameExtensions.retarget("", "csv", PLOT_TYPES));
            assertEquals("data.csv", SaveNameExtensions.retarget("data.csv", null, PLOT_TYPES));
        }
    }

    @Nested
    @DisplayName("complete: accepting the dialog")
    class Complete {

        @Test
        void addsTheActiveTypeToABareName() {
            assertEquals("statistics.csv", SaveNameExtensions.complete("statistics", "csv"));
        }

        @Test
        @DisplayName("never touches a name that already carries any extension")
        void honoursAnyTypedExtension() {
            assertEquals("data.pxt", SaveNameExtensions.complete("data.pxt", "csv"));
            assertEquals("notes.txt", SaveNameExtensions.complete("notes.txt", "csv"));
            assertEquals("data.res.csv", SaveNameExtensions.complete("data.res.csv", "pxt"));
        }

        @Test
        void isANoOpWhenTheTypeHasNoExtension() {
            assertEquals("anything", SaveNameExtensions.complete("anything", null));
        }

        @Test
        @DisplayName("completes a pasted path using its final segment")
        void handlesPastedPaths() {
            assertEquals("/tmp/out/results.csv",
                SaveNameExtensions.complete("/tmp/out/results", "csv"));
            assertEquals("/tmp/out/results.pxt",
                SaveNameExtensions.complete("/tmp/out/results.pxt", "csv"));
        }
    }

    @Nested
    class HasExtension {

        @Test
        void recognisesARealExtension() {
            assertTrue(SaveNameExtensions.hasExtension("model.ini"));
            assertTrue(SaveNameExtensions.hasExtension("data.res.csv"));
        }

        @Test
        void rejectsBareNames() {
            assertFalse(SaveNameExtensions.hasExtension("model"));
            assertFalse(SaveNameExtensions.hasExtension(""));
        }

        @Test
        @DisplayName("a dotfile is a name, not an extension")
        void treatsALeadingDotAsPartOfTheName() {
            assertFalse(SaveNameExtensions.hasExtension(".gitignore"));
        }

        @Test
        @DisplayName("a dot in a parent folder is not the file's extension")
        void ignoresDotsBeforeTheFinalSeparator() {
            assertFalse(SaveNameExtensions.hasExtension("/tmp/v1.2/results"));
            assertTrue(SaveNameExtensions.hasExtension("/tmp/v1.2/results.csv"));
        }
    }
}
