package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the relative-path -> Kalix trailhead-path transformation: strip the leading run of
 * "./" and "../" segments and prepend "^/" (result always starts with "^/").
 */
class TrailheadPathTest {

    @Test
    void descendantGetsTrailheadPrefix() {
        assertEquals("^/data/flow.csv", ProjectTree.toTrailhead("data/flow.csv"));
    }

    @Test
    void leadingDotSlashStripped() {
        assertEquals("^/data/x", ProjectTree.toTrailhead("./data/x"));
    }

    @Test
    void singleParentStripped() {
        assertEquals("^/c/x.csv", ProjectTree.toTrailhead("../c/x.csv"));
    }

    @Test
    void multipleParentsStripped() {
        assertEquals("^/d/y.csv", ProjectTree.toTrailhead("../../d/y.csv"));
    }

    @Test
    void mixedLeadingSegmentsStripped() {
        assertEquals("^/x", ProjectTree.toTrailhead("./../x"));
    }

    @Test
    void siblingFile() {
        assertEquals("^/model.ini", ProjectTree.toTrailhead("model.ini"));
    }

    @Test
    void onlyParentSegments() {
        assertEquals("^/", ProjectTree.toTrailhead("../../"));
    }

    @Test
    void empty() {
        assertEquals("^/", ProjectTree.toTrailhead(""));
    }
}
