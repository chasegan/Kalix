package com.kalix.ide.windows;

import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The derived series the Run Manager holds, and the rules about them that do not need a
 * window: ids, names, creation order and dependencies. Swing-free, so the invariants the
 * controller relies on can be tested headless.
 *
 * <p>Creation order is dependency order: a derived series can only use inputs that existed
 * when it was made, ids only grow, and a rename does not move an entry. So iterating
 * {@link #all()} or {@link #of(SourceRef)} visits every input before anything made from it,
 * which is what a recompute of Last needs.</p>
 */
final class DerivedSeriesStore {

    /** The dataset identifier rule: a part of a dotted series name, a CSV header, a Pixie field. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final Map<Long, DerivedSeriesInfo> byId = new LinkedHashMap<>();
    private long nextId = 1;

    /** The id the next derived series will take. */
    long nextId() {
        return nextId++;
    }

    void add(DerivedSeriesInfo info) {
        byId.put(info.id, info);
    }

    DerivedSeriesInfo remove(long id) {
        return byId.remove(id);
    }

    DerivedSeriesInfo get(long id) {
        return byId.get(id);
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }

    int size() {
        return byId.size();
    }

    /** Every derived series, in creation order. */
    Collection<DerivedSeriesInfo> all() {
        return byId.values();
    }

    /** The derived series of {@code origin}, in creation order. */
    List<DerivedSeriesInfo> of(SourceRef origin) {
        return byId.values().stream().filter(a -> a.origin.equals(origin)).toList();
    }

    boolean hasAnyOf(SourceRef origin) {
        return byId.values().stream().anyMatch(a -> a.origin.equals(origin));
    }

    /** The derived series not in {@code toDelete} that have one of them as an input. */
    List<DerivedSeriesInfo> dependentsOf(Collection<DerivedSeriesInfo> toDelete) {
        Set<DerivedSeriesInfo.Input> deleted = new HashSet<>();
        for (DerivedSeriesInfo info : toDelete) {
            deleted.add(new DerivedSeriesInfo.DerivedSeriesInput(info.id));
        }
        List<DerivedSeriesInfo> dependents = new ArrayList<>();
        for (DerivedSeriesInfo a : byId.values()) {
            if (!deleted.contains(new DerivedSeriesInfo.DerivedSeriesInput(a.id))
                    && a.inputs.stream().anyMatch(deleted::contains)) {
                dependents.add(a);
            }
        }
        return dependents;
    }

    /**
     * Why {@code name} can't be used for a derived series of each of {@code origins}, or
     * {@code null}: it must be non-empty, letters, digits and underscores only, and not
     * already in use by a derived series of the same origin. The taken-name case returns
     * the clashing series so the caller can name its origin.
     */
    NameProblem nameProblem(String name, List<SourceRef> origins) {
        if (name.isEmpty()) {
            return new NameProblem("Enter a name.", null);
        }
        if (!NAME.matcher(name).matches()) {
            return new NameProblem("A derived series name can have letters, digits and underscores only.", null);
        }
        for (DerivedSeriesInfo existing : byId.values()) {
            if (existing.name().equals(name) && origins.contains(existing.origin)) {
                return new NameProblem(" already has a derived series named \"" + name + "\".", existing);
            }
        }
        return null;
    }

    /** A rejected name: {@code message} alone, or the origin's label followed by it when {@code taken} is set. */
    record NameProblem(String message, DerivedSeriesInfo taken) {
    }

    /** The first of {@code sum_1}, {@code sum_2}, … free for every one of {@code origins}. */
    String suggestName(List<SourceRef> origins) {
        int n = 1;
        while (nameProblem("sum_" + n, origins) != null) {
            n++;
        }
        return "sum_" + n;
    }

    /** Point counts of the derived series made from Pixie data that hold values, for the Pixie budget. */
    Map<SeriesRef, Integer> pixieBackedPoints() {
        Map<SeriesRef, Integer> points = new HashMap<>();
        for (DerivedSeriesInfo info : byId.values()) {
            if (info.pixieBacked && info.values() != null) {
                points.put(info.ref(), info.values().getPointCount());
            }
        }
        return points;
    }
}
