package com.kalix.ide.flowviz.data;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultLabelResolverTest {

    private final Map<Long, String> runNames = new HashMap<>();
    private final Map<Long, DerivedSeriesLabel> derivedSeries = new HashMap<>();
    private final DefaultLabelResolver resolver =
        new DefaultLabelResolver(runNames::get, derivedSeries::get);

    @Test
    void existingVariantsKeepTheirBaseNameAsName() {
        runNames.put(3L, "Run_3");
        assertEquals("node.x.ds_1 [Run_3]", resolver.labelFor(new RunSeries(3, "node.x.ds_1")));
        assertEquals("node.x.ds_1 [Last]", resolver.labelFor(new LastSeries("node.x.ds_1")));
        String path = new File("data", "flows.csv").getAbsolutePath();
        assertEquals("flow [flows.csv]", resolver.labelFor(new DatasetSeries(path, "flow")));
    }

    @Test
    void derivedSeriesNameIsProjectedNotTakenFromBaseName() {
        runNames.put(1L, "Run_1");
        derivedSeries.put(7L, new DerivedSeriesLabel("sum_1", new RunSource(1)));
        DerivedSeries ref = new DerivedSeries(7);

        assertEquals("derived.sum_1", resolver.nameFor(ref));
        assertEquals("derived.sum_1 [Run_1]", resolver.labelFor(ref));
        assertFalse(resolver.labelFor(ref).contains(ref.baseName()));
    }

    @Test
    void derivedSeriesRenameChangesLabelButNotIdentity() {
        derivedSeries.put(7L, new DerivedSeriesLabel("sum_1", new LastSource()));
        DerivedSeries ref = new DerivedSeries(7);
        String keyBefore = ref.baseName();

        derivedSeries.put(7L, new DerivedSeriesLabel("inflows", new LastSource()));

        assertEquals("derived.inflows [Last]", resolver.labelFor(ref));
        assertEquals(keyBefore, new DerivedSeries(7).baseName());
    }

    @Test
    void liveOriginFollowsRunRename() {
        runNames.put(1L, "Run_1");
        derivedSeries.put(7L, new DerivedSeriesLabel("sum_1", new RunSource(1)));
        runNames.put(1L, "baseline");
        assertEquals("baseline", resolver.sourceLabel(new DerivedSeries(7)));
    }

    @Test
    void unknownDerivedSeriesFallsBackToQuestionMark() {
        assertEquals("derived.? [?]", resolver.labelFor(new DerivedSeries(99)));
    }

    @Test
    void derivedSeriesOriginThatIsItselfDerivedIsShownAsUnknown() {
        // Creation never builds one; the resolver's contract is to make a missing identity
        // obvious, never to throw inside a tree cell.
        derivedSeries.put(7L, new DerivedSeriesLabel("sum_1", new DerivedSeriesSource(6)));
        assertEquals("?", resolver.sourceLabel(new DerivedSeries(7)));
    }

    @Test
    void singleArgumentResolverHasNoDerivedSeries() {
        DefaultLabelResolver runsOnly = new DefaultLabelResolver(id -> null);
        assertEquals("derived.? [?]", runsOnly.labelFor(new DerivedSeries(1)));
    }
}
