package com.kalix.ide.interaction;

import com.kalix.ide.utils.EngineNames;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The one place a menu label that names a cluster is made (ADR-0003 §2.3), so the
 * suffix rule below can't be applied at one call site and missed at another.
 *
 * <p>A cluster whose name is also a node's name, anywhere in the model, is labelled
 * with {@code (cluster)} after the name and outside any quotes: {@code Rename "farm_3"
 * (cluster)…}. Otherwise one menu could hold the node's {@code Rename "farm_3"} and the
 * cluster's beside it, reading the same. The suffix isn't part of the name, so it stays
 * outside the quotes. Compared against the whole model, not the selection, so a
 * cluster's label doesn't change with what is selected. Node names match ignoring case
 * (ADR-0006 amendment, 2026-10-29), so the comparison does too.
 */
final class ClusterLabels {

    private static final String SUFFIX = " (cluster)";

    private final Set<String> nodeKeys = new HashSet<>();

    /** Labels for a model whose nodes are named {@code nodeNames}. */
    ClusterLabels(Collection<String> nodeNames) {
        for (String name : nodeNames) {
            nodeKeys.add(EngineNames.nodeKey(name));
        }
    }

    /** The bare name, as a child of {@code Add to cluster ▸}. */
    String name(String cluster) {
        return cluster + suffix(cluster);
    }

    /** {@code Rename "x"…}: the ellipsis because it asks for the new name (ADR-0002 §2.4). */
    String rename(String cluster) {
        return "Rename " + quoted(cluster) + "…";
    }

    /** {@code Add to "x"}, for a single target (ADR-0002 §5). */
    String addTo(String cluster) {
        return "Add to " + quoted(cluster);
    }

    /** {@code Disband cluster}, or {@code Disband clusters} for more than one. */
    static String disband(int clusters) {
        return counted("Disband", clusters);
    }

    /** {@code Collapse cluster}, or {@code Collapse clusters} for more than one. */
    static String collapse(int clusters) {
        return counted("Collapse", clusters);
    }

    /** {@code Expand cluster}, or {@code Expand clusters} for more than one. */
    static String expand(int clusters) {
        return counted("Expand", clusters);
    }

    private String quoted(String cluster) {
        return "\"" + cluster + "\"" + suffix(cluster);
    }

    private String suffix(String cluster) {
        return nodeKeys.contains(EngineNames.nodeKey(cluster)) ? SUFFIX : "";
    }

    private static String counted(String verb, int clusters) {
        return verb + (clusters == 1 ? " cluster" : " clusters");
    }
}
