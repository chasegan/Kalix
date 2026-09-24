package com.kalix.ide.flowviz.data;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultLabelResolverTest {

    private final Map<Long, String> runNames = new HashMap<>();
    private final Map<Long, AggregateLabel> aggregates = new HashMap<>();
    private final DefaultLabelResolver resolver =
        new DefaultLabelResolver(runNames::get, aggregates::get);

    @Test
    void existingVariantsKeepTheirBaseNameAsName() {
        runNames.put(3L, "Run_3");
        assertEquals("node.x.ds_1 [Run_3]", resolver.labelFor(new RunSeries(3, "node.x.ds_1")));
        assertEquals("node.x.ds_1 [Last]", resolver.labelFor(new LastSeries("node.x.ds_1")));
        String path = new File("data", "flows.csv").getAbsolutePath();
        assertEquals("flow [flows.csv]", resolver.labelFor(new DatasetSeries(path, "flow")));
    }

    @Test
    void aggregateNameIsProjectedNotTakenFromBaseName() {
        runNames.put(1L, "Run_1");
        aggregates.put(7L, new AggregateLabel("sum_1", new RunSource(1), null));
        AggregateSeries ref = new AggregateSeries(7);

        assertEquals("aggregate.sum_1", resolver.nameFor(ref));
        assertEquals("aggregate.sum_1 [Run_1]", resolver.labelFor(ref));
        assertFalse(resolver.labelFor(ref).contains(ref.baseName()));
    }

    @Test
    void aggregateRenameChangesLabelButNotIdentity() {
        aggregates.put(7L, new AggregateLabel("sum_1", new LastSource(), null));
        AggregateSeries ref = new AggregateSeries(7);
        String keyBefore = ref.baseName();

        aggregates.put(7L, new AggregateLabel("inflows", new LastSource(), null));

        assertEquals("aggregate.inflows [Last]", resolver.labelFor(ref));
        assertEquals(keyBefore, new AggregateSeries(7).baseName());
    }

    @Test
    void liveOriginFollowsRunRename() {
        runNames.put(1L, "Run_1");
        aggregates.put(7L, new AggregateLabel("sum_1", new RunSource(1), null));
        runNames.put(1L, "baseline");
        assertEquals("baseline", resolver.sourceLabel(new AggregateSeries(7)));
    }

    @Test
    void removedOriginShowsFrozenNameMarkedRemoved() {
        String path = new File("data", "flows.csv").getAbsolutePath();
        aggregates.put(7L, new AggregateLabel("sum_1", new DatasetSource(path), "flows.csv"));
        assertEquals("flows.csv (removed)", resolver.sourceLabel(new AggregateSeries(7)));

        // Frozen means frozen: a run of the same id reappearing in the lookup is ignored.
        runNames.put(1L, "Run_1");
        aggregates.put(8L, new AggregateLabel("sum_2", new RunSource(1), "old_name"));
        assertEquals("old_name (removed)", resolver.sourceLabel(new AggregateSeries(8)));
    }

    @Test
    void unknownAggregateFallsBackToQuestionMark() {
        assertEquals("aggregate.? [?]", resolver.labelFor(new AggregateSeries(99)));
    }

    @Test
    void aggregateOfAggregateIsRejected() {
        aggregates.put(7L, new AggregateLabel("sum_1", new AggregateSource(6), null));
        assertThrows(IllegalStateException.class, () -> resolver.sourceLabel(new AggregateSeries(7)));
    }

    @Test
    void singleArgumentResolverHasNoAggregates() {
        DefaultLabelResolver runsOnly = new DefaultLabelResolver(id -> null);
        assertEquals("aggregate.? [?]", runsOnly.labelFor(new AggregateSeries(1)));
    }
}
