package com.kalix.ide.flowviz;

/**
 * The plot's axes: time (or whatever the plot type puts along the bottom), the left Y
 * axis, and the right (y2) Y axis, which exists only while a series is drawn against it.
 */
public enum Axis {
    X, Y, Y2;

    /** True for a value axis, {@link #Y} or {@link #Y2}. */
    public boolean isValueAxis() {
        return this != X;
    }

    /**
     * The axis' name as the menus, the Key and the dialog spell it. The one Y axis is "Y
     * axis"; once there is a right one, the two are told apart as left and right, which
     * is what the eye sees (ADR-0002 §2.3: no word the plot does not already supply).
     */
    public String label(boolean rightAxisInUse) {
        return switch (this) {
            case X -> "X axis";
            case Y -> rightAxisInUse ? "Left Y axis" : "Y axis";
            case Y2 -> "Right Y axis";
        };
    }
}
