package com.kalix.ide.flowviz.data;

/**
 * {@link SourceRef} variant for the "Last run" alias. Like {@link LastSeries}, it
 * carries no run identity of its own: a tab that has Last checked tracks whichever
 * run is currently the most recent, not the run that happened to be "last" when the
 * tab recorded it. It deliberately survives the Last node being cleared — when a new
 * run completes, tabs holding this ref pick the new Last up again on switch.
 */
public record LastSource() implements SourceRef {
}
