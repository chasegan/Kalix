package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's cluster index follows the text through both parse paths. The incremental
 * path is the one the IDE uses, and its diff compares nodes by name, so with no links
 * to compare it would miss a change of order on its own.
 */
class HydrologicalModelClusterTest {

    private static String section(String name, String cluster) {
        return (cluster == null ? "" : "#@cluster = " + cluster + "\n")
                + "[node." + name + "]\ntype = storage\nloc = 1, 2\n";
    }

    @Test
    void aNewModelHasNoClusters() {
        assertTrue(new HydrologicalModel().getClusters().isEmpty());
    }

    @Test
    void aFullParseBuildsTheIndex() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniText(section("a", "zebra") + section("b", "apple"));
        assertEquals(List.of("zebra", "apple"), model.getClusters().names());
    }

    @Test
    void theFirstIncrementalParseBuildsTheIndex() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "zebra") + section("b", "apple"));
        assertEquals(List.of("zebra", "apple"), model.getClusters().names());
    }

    @Test
    void anIncrementalParseSeesAChangeToOnlyAClusterLine() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "farm"));
        model.parseFromIniTextIncremental(section("a", "dam"));
        assertEquals(List.of("dam"), model.getClusters().names());
        assertEquals("dam", model.getNode("a").getCluster());
    }

    @Test
    void removingTheLastMemberRemovesTheCluster() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "farm"));
        model.parseFromIniTextIncremental(section("a", null));
        assertTrue(model.getClusters().isEmpty());
    }

    @Test
    void anIncrementalParseSeesSectionsReorderedWithNoNodeChanges() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "zebra") + section("b", "apple"));
        model.parseFromIniTextIncremental(section("b", "apple") + section("a", "zebra"));
        assertEquals(List.of("apple", "zebra"), model.getClusters().names());
    }

    @Test
    void aReorderThatChangesClusterOrderNotifiesListeners() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "zebra") + section("b", "apple"));
        List<ModelChangeEvent> events = new ArrayList<>();
        model.addChangeListener(events::add);
        model.parseFromIniTextIncremental(section("b", "apple") + section("a", "zebra"));
        assertEquals(1, events.size(), "the drawing order changed, so the map has to hear about it");
    }

    @Test
    void aReorderOfSectionsWithLinksIsSeenToo() {
        // Links are compared in order, so this goes through the diff's change path.
        String a = section("a", "zebra") + "ds_1 = b\n";
        String b = section("b", "apple") + "ds_1 = a\n";
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(a + b);
        List<ModelChangeEvent> events = new ArrayList<>();
        model.addChangeListener(events::add);
        model.parseFromIniTextIncremental(b + a);
        assertEquals(List.of("apple", "zebra"), model.getClusters().names());
        assertEquals(1, events.size());
    }

    @Test
    void aSwapOfTwoMembersNotifiesListeners() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "farm") + section("b", "farm"));
        List<ModelChangeEvent> events = new ArrayList<>();
        model.addChangeListener(events::add);
        model.parseFromIniTextIncremental(section("b", "farm") + section("a", "farm"));
        assertEquals(List.of("b", "a"), model.getClusters().members("farm"));
        assertEquals(1, events.size());
    }

    @Test
    void movingANodeKeepsItsAkaAndCluster() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental("#@aka = Farm Dam\n" + section("a", "farm"));
        model.updateNodePosition("a", 5, 6);
        ModelNode moved = model.getNode("a");
        assertEquals(5, moved.getX());
        assertEquals("Farm Dam", moved.getAka());
        assertEquals("farm", moved.getCluster());
    }

    @Test
    void anUnchangedParseStaysSilent() {
        HydrologicalModel model = new HydrologicalModel();
        String text = section("a", "zebra") + section("b", "apple");
        model.parseFromIniTextIncremental(text);
        List<ModelChangeEvent> events = new ArrayList<>();
        model.addChangeListener(events::add);
        model.parseFromIniTextIncremental(text);
        assertTrue(events.isEmpty());
    }

    @Test
    void aDuplicateNodeNameCountsAtItsLastSection() {
        HydrologicalModel model = new HydrologicalModel();
        model.parseFromIniTextIncremental(section("a", "first") + section("a", "second"));
        assertEquals(List.of("second"), model.getClusters().names());
    }
}
