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
}
