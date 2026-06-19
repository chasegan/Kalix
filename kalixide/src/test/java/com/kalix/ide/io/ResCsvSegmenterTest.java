package com.kalix.ide.io;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResCsvSegmenterTest {

    @Test
    void gaugeUsesFeatureSiteElement() {
        // WaterFeatureType + Site + (single, @-free) Structure.
        List<String> path = ResCsvSegmenter.segment(
            "Gauge: 1002 PI Pimpama Creek EOS: Downstream Flow",
            "Gauge", "1002 PI Pimpama Creek EOS", "Downstream Flow");
        assertEquals(List.of("Gauge", "1002 PI Pimpama Creek EOS", "Downstream Flow"), path);
    }

    @Test
    void functionCollapsesRedundantPrefixAndSplitsStructure() {
        // WaterFeatureType=Site=Functions (redundant) + @-delimited Structure.
        List<String> path = ResCsvSegmenter.segment(
            "Functions@Functions@Storages@$f_040196_mlake_plus_seep",
            "Functions", "Functions", "Functions@Storages@$f_040196_mlake_plus_seep");
        assertEquals(List.of("Functions", "Storages", "$f_040196_mlake_plus_seep"), path);
    }

    @Test
    void structureSuffixKeepsSiblingsDistinct() {
        // Two storage spill outlets that differ only by Structure suffix must not collide.
        List<String> a = ResCsvSegmenter.segment("x", "Storage", "3007 NE Little Nerang Dam",
            "Spill Volume@lnd_downstream_outlet_link");
        List<String> b = ResCsvSegmenter.segment("x", "Storage", "3007 NE Little Nerang Dam",
            "Spill Volume@lnd_on_pond_outlet_link");
        assertEquals(List.of("Storage", "3007 NE Little Nerang Dam", "Spill Volume",
            "lnd_downstream_outlet_link"), a);
        assertEquals(List.of("Storage", "3007 NE Little Nerang Dam", "Spill Volume",
            "lnd_on_pond_outlet_link"), b);
    }

    @Test
    void blankAttributesFallBackToName() {
        assertEquals(List.of("My Series"), ResCsvSegmenter.segment("My Series", "", "  ", null));
    }
}
