package com.kalix.ide.windows;

import com.kalix.ide.flowviz.data.DatasetSource;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.RunSource;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the rules the controller relies on, without a window. */
class DerivedSeriesStoreTest {

    private static final SourceRef RUN_1 = new RunSource(1);
    private static final SourceRef RUN_2 = new RunSource(2);
    private static final SourceRef LAST = new LastSource();

    private final DerivedSeriesStore store = new DerivedSeriesStore();

    private DerivedSeriesInfo add(SourceRef origin, String name, DerivedSeriesInfo.Input... inputs) {
        DerivedSeriesInfo info = new DerivedSeriesInfo(store.nextId(), origin, name, List.of(inputs), false,
            new TimeSeriesData(new long[]{0}, new double[]{1.0}));
        store.add(info);
        return info;
    }

    private static DerivedSeriesInfo.Input series(String name) {
        return new DerivedSeriesInfo.SeriesInput(name);
    }

    private static DerivedSeriesInfo.Input derived(DerivedSeriesInfo info) {
        return new DerivedSeriesInfo.DerivedSeriesInput(info.id);
    }

    @Test
    void namesAreLettersDigitsAndUnderscoresOnly() {
        assertNull(store.nameProblem("sum_1", List.of(RUN_1)));
        assertNull(store.nameProblem("Total2024", List.of(RUN_1)));
        assertEquals("Enter a name.", store.nameProblem("", List.of(RUN_1)).message());
        for (String bad : List.of("a.b", "a b", "a,b", "say\"hi", "inflow*", "x/y", "!a", "Σ")) {
            DerivedSeriesStore.NameProblem problem = store.nameProblem(bad, List.of(RUN_1));
            assertNotNull(problem, bad);
            assertTrue(problem.message().contains("letters, digits and underscores"), bad);
        }
    }

    @Test
    void aNameIsUniquePerOriginAndTheClashNamesTheSeries() {
        DerivedSeriesInfo first = add(RUN_1, "sum_1");
        assertNull(store.nameProblem("sum_1", List.of(RUN_2)), "another origin may reuse the name");
        DerivedSeriesStore.NameProblem taken = store.nameProblem("sum_1", List.of(RUN_1, RUN_2));
        assertNotNull(taken);
        assertSame(first, taken.taken());
        assertTrue(taken.message().contains("\"sum_1\""));
    }

    @Test
    void suggestedNameSkipsNamesTakenInAnyOfTheOrigins() {
        assertEquals("sum_1", store.suggestName(List.of(RUN_1, RUN_2)));
        add(RUN_1, "sum_1");
        add(RUN_2, "sum_2");
        assertEquals("sum_3", store.suggestName(List.of(RUN_1, RUN_2)));
        assertEquals("sum_2", store.suggestName(List.of(RUN_1)));
    }

    @Test
    void creationOrderSurvivesRenameAndDeleteSoInputsComeBeforeDependents() {
        DerivedSeriesInfo a = add(LAST, "a", series("node.a1.ds_1"), series("node.a2.ds_1"));
        DerivedSeriesInfo b = add(LAST, "b", series("node.b1.ds_1"));
        DerivedSeriesInfo other = add(RUN_1, "x", series("node.a1.ds_1"));
        DerivedSeriesInfo system = add(LAST, "system", derived(a), derived(b));

        // A rename sorts nothing: ids, not names, order the store.
        a.rename("zz_reach_a");
        assertEquals(List.of(a, b, system), store.of(LAST));
        assertEquals(List.of(a, b, other, system), List.copyOf(store.all()));

        // Deleting an entry in the middle keeps the rest in order.
        store.remove(b.id);
        assertEquals(List.of(a, system), store.of(LAST));

        // Every input of system comes before system.
        List<DerivedSeriesInfo> last = store.of(LAST);
        for (DerivedSeriesInfo.Input input : system.inputs) {
            if (input instanceof DerivedSeriesInfo.DerivedSeriesInput d && store.get(d.derivedId()) != null) {
                assertTrue(last.indexOf(store.get(d.derivedId())) < last.indexOf(system));
            }
        }
    }

    @Test
    void dependentsAreTheSeriesMadeFromWhatIsDeleted() {
        DerivedSeriesInfo a = add(LAST, "a", series("node.a1.ds_1"));
        DerivedSeriesInfo b = add(LAST, "b", series("node.b1.ds_1"));
        DerivedSeriesInfo ab = add(LAST, "ab", derived(a), derived(b));
        DerivedSeriesInfo abc = add(LAST, "abc", derived(ab), series("node.c.ds_1"));

        assertEquals(List.of(ab), store.dependentsOf(List.of(a)), "one level: abc uses ab, not a");
        assertEquals(List.of(abc), store.dependentsOf(List.of(ab)));
        assertEquals(List.of(abc), store.dependentsOf(List.of(a, ab)), "ab is itself being deleted");
        assertTrue(store.dependentsOf(List.of(abc)).isEmpty());
    }

    @Test
    void pixieBackedPointsCountOnlySeriesWithValues() {
        DerivedSeriesInfo pixie = new DerivedSeriesInfo(store.nextId(), new DatasetSource("/d/a.pxt"), "p",
            List.of(series("x")), true, new TimeSeriesData(new long[]{0, 1, 2}, new double[]{1, 2, 3}));
        DerivedSeriesInfo plain = new DerivedSeriesInfo(store.nextId(), RUN_1, "q",
            List.of(series("x")), false, new TimeSeriesData(new long[]{0, 1, 2}, new double[]{1, 2, 3}));
        DerivedSeriesInfo cleared = new DerivedSeriesInfo(store.nextId(), new DatasetSource("/d/a.pxt"), "r",
            List.of(series("x")), true, null);
        store.add(pixie);
        store.add(plain);
        store.add(cleared);
        assertEquals(1, store.pixieBackedPoints().size());
        assertEquals(3, store.pixieBackedPoints().get(pixie.ref()));
    }
}
