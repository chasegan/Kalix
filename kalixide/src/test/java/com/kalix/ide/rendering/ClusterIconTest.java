package com.kalix.ide.rendering;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A collapsed cluster's icon, as agreed from the demo: a 34 × 24 face, a back card 3 px
 * down and right, the pair centred on the members' centroid, in fixed greys. The sizes are
 * written as numbers, not computed from the constants they check.
 */
class ClusterIconTest {

    @Test
    void theFaceIsThirtyFourByTwentyFourUpAndLeftOfTheBackCard() {
        assertEquals(new Rectangle2D.Double(81.5, 36.5, 34, 24), ClusterIcon.face(100, 50));
        assertEquals(new Rectangle2D.Double(84.5, 39.5, 34, 24), ClusterIcon.back(100, 50));
    }

    @Test
    void theTwoCardsTogetherAreCentredOnTheCentroid() {
        Rectangle2D both = ClusterIcon.face(100, 50).createUnion(ClusterIcon.back(100, 50));
        assertEquals(100, both.getCenterX(), 1e-9);
        assertEquals(50, both.getCenterY(), 1e-9);
    }

    @Test
    void theCornersAndTheNameSitAsAgreed() {
        assertEquals(4, ClusterIcon.CORNER_RADIUS);
        // The cards reach 13.5 px below the centre, a node 10: the name drops by 4.
        assertEquals(4, ClusterIcon.LABEL_DROP);
    }

    @Test
    void theColoursAreTheDemosGreys() {
        assertEquals(new Color(0xbd, 0xbd, 0xbd), ClusterIcon.BACK);
        assertEquals(new Color(0xd9, 0xd9, 0xd9), ClusterIcon.FACE);
        assertEquals(new Color(0x55, 0x55, 0x55), ClusterIcon.BORDER);
        assertEquals(new Color(0x33, 0x33, 0x33), ClusterIcon.COUNT);
    }
}
