package com.kalix.ide.document;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single resolver that projects a {@link OpenModel} to the string shown for it.
 *
 * <p>Per {@code manifestos/identity-and-labels.md} §2.3, display strings come only from
 * here — never hand-built at a call site. Every surface that names an open model (the
 * editor tab strip, the Optimiser's target selector) goes through this, so the same two
 * files can never be told apart one way in one place and another way somewhere else.</p>
 *
 * <p>The label depends on <em>context</em>: two open models both called
 * {@code model.ini} must be distinguishable, so a label is a function of the source
 * <b>and</b> the set it is shown alongside. That is precisely why it must never be
 * stored or used as a key — the same model's label changes as others open and close
 * around it.</p>
 */
public final class DocumentLabels {

    private DocumentLabels() {
    }

    /**
     * Labels for every model in {@code all}, in the same order.
     *
     * <p>A name that is unique is shown bare. Names that collide are qualified by
     * prefixing progressively more of each file's path — nearest ancestor first — until
     * the colliding group is unique, e.g. {@code upper/model.ini} and
     * {@code lower/model.ini}. Only the colliding group is qualified; unrelated models
     * keep their bare names.</p>
     *
     * @param all         the models being shown together
     * @param projectRoot the open project folder, above which paths are not walked;
     *                    {@code null} to walk as far as needed
     */
    public static List<String> labelsFor(List<? extends OpenModel> all, File projectRoot) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(all.size());
        for (OpenModel source : all) {
            labels.add(source.getDisplayName());
        }

        Map<String, List<Integer>> collisions = new HashMap<>();
        for (int index = 0; index < labels.size(); index++) {
            collisions.computeIfAbsent(labels.get(index), key -> new ArrayList<>()).add(index);
        }
        for (List<Integer> group : collisions.values()) {
            if (group.size() > 1) {
                disambiguate(all, labels, group, projectRoot);
            }
        }
        return labels;
    }

    /**
     * The label for {@code source} when displayed alongside {@code all}.
     *
     * <p>Defined in terms of {@link #labelsFor} — including {@code source} in the set if
     * it is not already there — so a single model's label can never disagree with the
     * label the same model gets in a bulk render. A source outside the set is the
     * ordinary case for an optimisation's target after its tab has been closed.</p>
     *
     * @param source      the model to label
     * @param all         the models it is being shown alongside (may include {@code source})
     * @param projectRoot the open project folder, or {@code null}
     * @return the display label, never null
     */
    public static String labelFor(OpenModel source, List<? extends OpenModel> all,
                                  File projectRoot) {
        if (source == null) {
            return "";
        }
        List<OpenModel> combined = new ArrayList<>();
        if (all != null) {
            combined.addAll(all);
        }
        int position = indexOfIdentity(combined, source);
        if (position < 0) {
            position = combined.size();
            combined.add(source);
        }
        return labelsFor(combined, projectRoot).get(position);
    }

    /**
     * The label for a model that is no longer open, derived from the file captured when
     * it was bound. The set it was once disambiguated against is gone, so this is the
     * bare file name.
     *
     * @param file the captured backing file, may be {@code null}
     */
    public static String labelForClosed(File file) {
        return file != null ? file.getName() : "Untitled";
    }

    /**
     * Resolves one group of colliding names in place, walking up each file's path until
     * the group is unique — or until the project root (or an unsaved model, which has no
     * path to walk) stops further progress.
     */
    private static void disambiguate(List<? extends OpenModel> sources, List<String> labels,
                                     List<Integer> group, File projectRoot) {
        Map<Integer, List<String>> ancestors = new HashMap<>();
        int maxDepth = 0;
        for (int index : group) {
            List<String> segments = ancestorSegments(sources.get(index).getFile(), projectRoot);
            ancestors.put(index, segments);
            maxDepth = Math.max(maxDepth, segments.size());
        }

        int depth = 0;
        while (true) {
            Map<String, Integer> counts = new HashMap<>();
            for (int index : group) {
                counts.merge(qualified(sources.get(index), ancestors.get(index), depth),
                        1, Integer::sum);
            }
            boolean allUnique = counts.values().stream().allMatch(count -> count == 1);
            if (allUnique || depth >= maxDepth) {
                for (int index : group) {
                    labels.set(index, qualified(sources.get(index), ancestors.get(index), depth));
                }
                return;
            }
            depth++;
        }
    }

    /** Ancestor directory names of {@code file}, nearest first, stopping before {@code root}. */
    private static List<String> ancestorSegments(File file, File root) {
        List<String> segments = new ArrayList<>();
        if (file == null) {
            return segments;  // An unsaved model has no path to disambiguate with.
        }
        File parent = file.getParentFile();
        while (parent != null && !parent.equals(root)) {
            String name = parent.getName();
            segments.add(name.isEmpty() ? parent.getPath() : name);
            parent = parent.getParentFile();
        }
        return segments;
    }

    /** The name prefixed with up to {@code depth} ancestor segments, outermost first. */
    private static String qualified(OpenModel source, List<String> segments, int depth) {
        String base = source.getDisplayName();
        int use = Math.min(depth, segments.size());
        if (use == 0) {
            return base;
        }
        StringBuilder name = new StringBuilder();
        for (int i = use - 1; i >= 0; i--) {
            name.append(segments.get(i)).append('/');
        }
        return name.append(base).toString();
    }

    /** Position of {@code source} by identity — never by equality, which a value-like
     * {@code OpenModel} could satisfy for a genuinely different model. */
    private static int indexOfIdentity(List<? extends OpenModel> sources, OpenModel source) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) == source) {
                return i;
            }
        }
        return -1;
    }
}
