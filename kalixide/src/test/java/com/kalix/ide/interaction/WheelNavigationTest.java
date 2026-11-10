package com.kalix.ide.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelNavigationTest {

    @Test
    void plainScrollZoomsWithAMouseAndPansVerticallyOnATrackpad() {
        assertEquals(WheelNavigation.ZOOM, WheelNavigation.of(false, false, false));
        assertEquals(WheelNavigation.PAN_VERTICAL, WheelNavigation.of(false, false, true));
    }

    /** A sideways swipe or tilt wheel arrives as Shift+wheel; it must never zoom. */
    @Test
    void shiftScrollPansHorizontallyInBothModes() {
        assertEquals(WheelNavigation.PAN_HORIZONTAL, WheelNavigation.of(true, false, false));
        assertEquals(WheelNavigation.PAN_HORIZONTAL, WheelNavigation.of(true, false, true));
    }

    /** Pinch (Ctrl+wheel) and Cmd+scroll keep zoom reachable when plain scroll pans. */
    @Test
    void zoomModifierAlwaysZoomsEvenWithShift() {
        assertEquals(WheelNavigation.ZOOM, WheelNavigation.of(false, true, false));
        assertEquals(WheelNavigation.ZOOM, WheelNavigation.of(false, true, true));
        assertEquals(WheelNavigation.ZOOM, WheelNavigation.of(true, true, false));
        assertEquals(WheelNavigation.ZOOM, WheelNavigation.of(true, true, true));
    }
}
