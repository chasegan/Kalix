package com.kalix.ide.interaction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every menu label that names a cluster. A cluster that shares its name with a node
 * anywhere in the model takes a {@code (cluster)} suffix outside the quotes, so two items
 * in one menu never read the same; node names match ignoring case, so the suffix does too.
 */
class ClusterLabelsTest {

    private static ClusterLabels labels(String... nodeNames) {
        return new ClusterLabels(List.of(nodeNames));
    }

    @Test
    void namesAClusterPlainlyWhenNoNodeSharesItsName() {
        ClusterLabels labels = labels("teemburra_dam", "teemburra_offtake");

        assertEquals("Rename \"teemburra\"…", labels.rename("teemburra"));
        assertEquals("Add to \"teemburra\"", labels.addTo("teemburra"));
        assertEquals("teemburra", labels.name("teemburra"));
    }

    @Test
    void addsTheSuffixOutsideTheQuotesWhenANodeSharesTheName() {
        ClusterLabels labels = labels("farm_3", "farm_4");

        assertEquals("Rename \"farm_3\" (cluster)…", labels.rename("farm_3"));
        assertEquals("Add to \"farm_3\" (cluster)", labels.addTo("farm_3"));
        assertEquals("farm_3 (cluster)", labels.name("farm_3"));
    }

    @Test
    void matchesNodeNamesIgnoringCase() {
        assertEquals("teemburra (cluster)", labels("Teemburra").name("teemburra"));
        assertEquals("Teemburra (cluster)", labels("teemburra").name("Teemburra"));
    }

    @Test
    void namesEveryClusterPlainlyInAModelWithNoNodes() {
        assertEquals("farm_3", labels().name("farm_3"));
    }

    @Test
    void countedLabelsAreSingularForOneAndPluralForMore() {
        assertEquals("Disband cluster", ClusterLabels.disband(1));
        assertEquals("Disband clusters", ClusterLabels.disband(2));
        assertEquals("Collapse cluster", ClusterLabels.collapse(1));
        assertEquals("Collapse clusters", ClusterLabels.collapse(3));
        assertEquals("Expand cluster", ClusterLabels.expand(1));
        assertEquals("Expand clusters", ClusterLabels.expand(2));
    }
}
