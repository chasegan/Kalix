package com.kalix.ide.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DocumentLabels}, the resolver that projects a
 * {@link OpenModel} to the string shown for it.
 *
 * <p>The behaviour that matters: two open models sharing a basename must be
 * distinguishable. Without qualification the Optimiser's model list shows two
 * identical rows and the user cannot tell which one they are targeting.</p>
 */
class DocumentLabelsTest {

    /** A stand-in model source; identity is the object, as for a real KalixDocument. */
    private record FakeSource(String name, File folder) implements OpenModel {
        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public File getFile() {
            return folder != null ? new File(folder, name) : null;
        }

        @Override
        public File getWorkingDirectory() {
            return folder;
        }

        @Override
        public String getText() {
            return "";
        }
    }

    private static FakeSource saved(String name, String folder) {
        return new FakeSource(name, new File(folder));
    }

    private static FakeSource unsaved(String name) {
        return new FakeSource(name, null);
    }

    @Test
    @DisplayName("A unique name is shown bare")
    void testUniqueNameIsUnqualified() {
        FakeSource a = saved("catchment.ini", "/models/upper");
        FakeSource b = saved("river.ini", "/models/lower");

        assertEquals("catchment.ini", DocumentLabels.labelFor(a, List.of(a, b), null));
        assertEquals("river.ini", DocumentLabels.labelFor(b, List.of(a, b), null));
    }

    @Test
    @DisplayName("A duplicated name is qualified by its folder")
    void testDuplicateNameIsQualifiedByFolder() {
        FakeSource a = saved("model.ini", "/models/upper");
        FakeSource b = saved("model.ini", "/models/lower");
        List<FakeSource> all = List.of(a, b);

        assertEquals("upper/model.ini", DocumentLabels.labelFor(a, all, null));
        assertEquals("lower/model.ini", DocumentLabels.labelFor(b, all, null));
        assertNotEquals(DocumentLabels.labelFor(a, all, null), DocumentLabels.labelFor(b, all, null));
    }

    @Test
    @DisplayName("Labelling is contextual, so the same model reads differently in a different set")
    void testLabelDependsOnTheSetItIsShownIn() {
        FakeSource a = saved("model.ini", "/models/upper");
        FakeSource b = saved("model.ini", "/models/lower");

        // Alone it needs no qualification; alongside its twin it does. This is exactly
        // why a label must never be stored or used as a key.
        assertEquals("model.ini", DocumentLabels.labelFor(a, List.of(a), null));
        assertEquals("upper/model.ini", DocumentLabels.labelFor(a, List.of(a, b), null));
    }

    @Test
    @DisplayName("An unsaved model has no folder to qualify with")
    void testUnsavedDuplicatesFallBackToTheBareName() {
        FakeSource a = unsaved("Untitled");
        FakeSource b = unsaved("Untitled");

        // Genuinely indistinguishable — but both are unusable as targets anyway.
        assertEquals("Untitled", DocumentLabels.labelFor(a, List.of(a, b), null));
        assertFalse(a.isOptimisable());
    }

    @Test
    @DisplayName("A saved duplicate is still qualified when its twin is unsaved")
    void testMixedSavedAndUnsavedDuplicates() {
        FakeSource saved = saved("model.ini", "/models/upper");
        FakeSource unsaved = unsaved("model.ini");
        List<FakeSource> all = List.of(saved, unsaved);

        assertEquals("upper/model.ini", DocumentLabels.labelFor(saved, all, null));
        assertEquals("model.ini", DocumentLabels.labelFor(unsaved, all, null));
    }

    @Test
    @DisplayName("labelsFor matches labelFor, positionally")
    void testBulkLabelsMatchIndividualLabels() {
        List<FakeSource> all = List.of(
                saved("model.ini", "/models/upper"),
                saved("model.ini", "/models/lower"),
                saved("river.ini", "/models/lower"),
                unsaved("Untitled"));

        List<String> bulk = DocumentLabels.labelsFor(all, null);

        assertEquals(all.size(), bulk.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(DocumentLabels.labelFor(all.get(i), all, null), bulk.get(i),
                    "bulk and individual labels disagree at index " + i);
        }
        assertEquals(List.of("upper/model.ini", "lower/model.ini", "river.ini", "Untitled"), bulk);
    }

    @Test
    @DisplayName("A source outside the set is still qualified against a same-named member")
    void testSourceNotInTheSetIsStillQualified() {
        // The closed target of an optimisation, labelled against the models still open.
        // Counting occurrences including self would see only one match and wrongly leave
        // both this and the open model reading identically.
        FakeSource closed = saved("model.ini", "/models/upper");
        FakeSource stillOpen = saved("model.ini", "/models/lower");

        assertEquals("upper/model.ini", DocumentLabels.labelFor(closed, List.of(stillOpen), null));
    }

    @Test
    @DisplayName("Qualification walks up until the colliding group is unique")
    void testQualificationWalksUpUntilUnique() {
        // A single folder level is not always enough — both live in a "run" folder.
        FakeSource a = saved("model.ini", "/projects/alpha/run");
        FakeSource b = saved("model.ini", "/projects/beta/run");

        assertEquals(List.of("alpha/run/model.ini", "beta/run/model.ini"),
                DocumentLabels.labelsFor(List.of(a, b), null));
    }

    @Test
    @DisplayName("Walking stops at the project root")
    void testQualificationStopsAtProjectRoot() {
        FakeSource a = saved("model.ini", "/projects/alpha/run");
        FakeSource b = saved("model.ini", "/projects/beta/run");

        // Bounded at /projects/alpha, `a` has only "run" to offer and cannot go further,
        // so the group settles for what it has rather than looping forever.
        List<String> labels = DocumentLabels.labelsFor(List.of(a, b), new File("/projects/alpha"));
        assertEquals(2, labels.size());
        assertTrue(labels.get(0).endsWith("model.ini"));
        assertTrue(labels.get(1).endsWith("model.ini"));
    }

    @Test
    @DisplayName("Only the colliding group is qualified")
    void testUnrelatedNamesStayBare() {
        FakeSource a = saved("model.ini", "/models/upper");
        FakeSource b = saved("model.ini", "/models/lower");
        FakeSource other = saved("river.ini", "/models/lower");

        assertEquals(List.of("upper/model.ini", "lower/model.ini", "river.ini"),
                DocumentLabels.labelsFor(List.of(a, b, other), null));
    }

    @Test
    @DisplayName("Three-way collisions are all disambiguated")
    void testThreeWayCollision() {
        FakeSource a = saved("model.ini", "/models/a");
        FakeSource b = saved("model.ini", "/models/b");
        FakeSource c = saved("model.ini", "/models/c");

        List<String> labels = DocumentLabels.labelsFor(List.of(a, b, c), null);
        assertEquals(List.of("a/model.ini", "b/model.ini", "c/model.ini"), labels);
        assertEquals(3, labels.stream().distinct().count());
    }

    @Test
    @DisplayName("A closed model is named from the file captured when it was bound")
    void testLabelForClosedUsesTheCapturedFile() {
        assertEquals("model.ini",
                DocumentLabels.labelForClosed(new File("/models/upper/model.ini")));
        assertEquals("Untitled", DocumentLabels.labelForClosed(null));
    }

    @Test
    @DisplayName("Null and empty inputs are handled without qualification")
    void testNullAndEmptyInputs() {
        FakeSource a = saved("model.ini", "/models/upper");

        assertEquals("", DocumentLabels.labelFor(null, List.of(a), null));
        assertEquals("model.ini", DocumentLabels.labelFor(a, null, null));
        assertEquals("model.ini", DocumentLabels.labelFor(a, List.of(), null));
        assertTrue(DocumentLabels.labelsFor(null, null).isEmpty());
        assertTrue(DocumentLabels.labelsFor(List.of(), null).isEmpty());
    }

    @Test
    @DisplayName("A model is optimisable only once it has a folder")
    void testOptimisableRequiresAFolder() {
        assertTrue(saved("model.ini", "/models/upper").isOptimisable());
        assertFalse(unsaved("Untitled").isOptimisable());
    }
}
