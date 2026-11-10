package com.kalix.ide.interaction;

/**
 * What one scroll-wheel event does to the map or plot view.
 *
 * <p>The mapping is a pure function of the modifiers and the user's navigation mode, kept
 * here so it can be tested without a display. Plots always pass mouse mode: the trackpad
 * preference is the map's alone.
 *
 * <ul>
 *   <li><b>Ctrl or Cmd</b> always zooms. A Windows trackpad pinch arrives as Ctrl+wheel
 *       (in whole-notch steps), and Cmd+scroll is the macOS zoom idiom (Figma, Miro,
 *       Lucidchart), so this keeps zoom reachable in both modes on every platform. Java
 *       receives no pinch at all on macOS or Linux.</li>
 *   <li><b>Shift</b> pans horizontally. On Windows and macOS, Java delivers a sideways
 *       trackpad swipe or a tilt wheel as Shift+wheel, so this is what makes sideways
 *       gestures pan rather than zoom there.</li>
 *   <li><b>Plain</b> scroll zooms with a mouse (the GIS and node-editor convention) and
 *       pans vertically in trackpad mode, where two-finger scrolling is the pan gesture.</li>
 * </ul>
 *
 * <p>There is deliberately no Alt/Option binding: on Windows a bare Alt release
 * activates the menu bar, and Option+scroll has no common meaning on macOS. Mouse users
 * pan freely by dragging empty canvas or middle-dragging.
 */
public enum WheelNavigation {
    ZOOM,
    PAN_HORIZONTAL,
    PAN_VERTICAL;

    /**
     * Resolves a wheel event to the view change it makes.
     *
     * @param shiftDown       Shift held, or a horizontal scroll (reported as Shift on Windows and macOS)
     * @param zoomModifier    Ctrl or Cmd held, or a Windows trackpad pinch
     * @param trackpadMode    the user's trackpad navigation preference
     */
    public static WheelNavigation of(boolean shiftDown, boolean zoomModifier, boolean trackpadMode) {
        if (zoomModifier) {
            return ZOOM;
        }
        if (shiftDown) {
            return PAN_HORIZONTAL;
        }
        return trackpadMode ? PAN_VERTICAL : ZOOM;
    }
}
