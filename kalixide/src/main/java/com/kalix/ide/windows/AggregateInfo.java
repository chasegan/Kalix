package com.kalix.ide.windows;

import com.kalix.ide.flowviz.data.AggregateSeries;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.util.List;

/**
 * One user-created aggregate: its identity, its recipe, and its values. The source-tree
 * user object for an aggregate, as {@code LoadedDatasetInfo} is for a dataset. EDT-only.
 */
final class AggregateInfo {

    /** One input to an aggregate: a series of its origin, or another aggregate of it. */
    sealed interface Input {
    }

    /** A series of the origin, by name. */
    record SeriesInput(String name) implements Input {
    }

    /** Another aggregate of the same origin, by id, so a rename doesn't break the recipe. */
    record AggregateInput(long aggregateId) implements Input {
    }

    final long id;
    /** The run, Last alias, or dataset the inputs were summed from. */
    final SourceRef origin;
    /** The recipe: what was summed, as resolved at creation. */
    final List<Input> inputs;

    private String name;
    private TimeSeriesData values;
    private String unavailableReason;

    AggregateInfo(long id, SourceRef origin, String name, List<Input> inputs,
                  TimeSeriesData values) {
        this.id = id;
        this.origin = origin;
        this.name = name;
        this.inputs = List.copyOf(inputs);
        this.values = values;
    }

    AggregateSeries ref() {
        return new AggregateSeries(id);
    }

    String name() {
        return name;
    }

    void rename(String newName) {
        this.name = newName;
    }

    /** The summed values, or {@code null} while unavailable (see {@link #unavailableReason}). */
    TimeSeriesData values() {
        return values;
    }

    /** Why {@link #values} is {@code null}, e.g. a failed recompute on a new Last run. */
    String unavailableReason() {
        return unavailableReason;
    }

    void setValues(TimeSeriesData values) {
        this.values = values;
        this.unavailableReason = null;
    }

    void setUnavailable(String reason) {
        this.values = null;
        this.unavailableReason = reason;
    }

    @Override
    public String toString() {
        return name;
    }
}
