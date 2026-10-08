package com.kalix.ide.interaction;

import com.kalix.ide.model.ModelLink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model's links as a directed graph, answering which nodes lie upstream of,
 * downstream of, or connected to a given set of nodes.
 *
 * <p>Every result includes the nodes it was asked about. Loops in the links are
 * tolerated: a half-edited model may hold one.</p>
 */
final class NodeAdjacency {

    // upstreamOf.get(x) lists the nodes immediately upstream of x; downstreamOf likewise.
    private final Map<String, List<String>> upstreamOf = new HashMap<>();
    private final Map<String, List<String>> downstreamOf = new HashMap<>();
    private final Map<String, List<String>> adjacent = new HashMap<>();

    NodeAdjacency(Collection<ModelLink> links) {
        for (ModelLink link : links) {
            var us = link.getUpstreamTerminus();
            var ds = link.getDownstreamTerminus();

            upstreamOf.computeIfAbsent(ds, k -> new ArrayList<>()).add(us);
            downstreamOf.computeIfAbsent(us, k -> new ArrayList<>()).add(ds);
            adjacent.computeIfAbsent(us, k -> new ArrayList<>()).add(ds);
            adjacent.computeIfAbsent(ds, k -> new ArrayList<>()).add(us);
        }
    }

    /** The nodes and everything upstream of them. */
    Set<String> upstream(Set<String> nodes) {
        return reach(nodes, upstreamOf);
    }

    /** The nodes and everything downstream of them. */
    Set<String> downstream(Set<String> nodes) {
        return reach(nodes, downstreamOf);
    }

    /**
     * {@link #upstream} plus the branches that leave it, each followed down to its
     * terminus or to where it rejoins the flow below the nodes.
     */
    Set<String> upstreamWithDistributaries(Set<String> nodes) {
        var up = reach(nodes, upstreamOf);
        var down = reach(nodes, downstreamOf);

        // Everything below the upstream nodes, less what lies below the nodes themselves.
        var result = reach(up, downstreamOf);
        result.removeAll(down);
        result.addAll(up);
        return result;
    }

    /**
     * {@link #downstream} plus the tributaries that join it, each followed up to its
     * headwaters.
     */
    Set<String> downstreamWithTributaries(Set<String> nodes) {
        var up = reach(nodes, upstreamOf);
        var down = reach(nodes, downstreamOf);

        // Everything above the downstream nodes, less what lies above the nodes themselves.
        var result = reach(down, upstreamOf);
        result.removeAll(up);
        result.addAll(down);
        return result;
    }

    /** The nodes and everything linked to them in either direction. */
    Set<String> connected(Set<String> nodes) {
        return reach(nodes, adjacent);
    }

    /** The nodes and everything reachable from them by following {@code adjacency}. */
    private static Set<String> reach(Set<String> nodes, Map<String, List<String>> adjacency) {
        var stack = new ArrayDeque<>(nodes);
        var visited = new HashSet<>(nodes);
        while (!stack.isEmpty()) {
            var node = stack.pop();
            for (var next : adjacency.getOrDefault(node, List.of())) {
                if (visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return visited;
    }
}
