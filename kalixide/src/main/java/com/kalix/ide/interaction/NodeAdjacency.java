package com.kalix.ide.interaction;

import com.kalix.ide.model.ModelLink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
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
            String us = link.getUpstreamTerminus();
            String ds = link.getDownstreamTerminus();

            upstreamOf.computeIfAbsent(ds, k -> new ArrayList<>()).add(us);
            downstreamOf.computeIfAbsent(us, k -> new ArrayList<>()).add(ds);
            adjacent.computeIfAbsent(us, k -> new ArrayList<>()).add(ds);
            adjacent.computeIfAbsent(ds, k -> new ArrayList<>()).add(us);
        }
    }

    /** The nodes and everything upstream of them. */
    Set<String> upstream(Set<String> nodes) {
        return reach(nodes, upstreamOf, Set.of());
    }

    /** The nodes and everything downstream of them. */
    Set<String> downstream(Set<String> nodes) {
        return reach(nodes, downstreamOf, Set.of());
    }

    /**
     * Everything joined to the nodes, in either direction, without passing through the
     * river below them. In a tree this is {@link #upstream}; in a braided network it also
     * takes the branches that leave the upstream reach, and whatever joins those branches.
     */
    Set<String> connectedUpstream(Set<String> nodes) {
        return reach(nodes, adjacent, below(nodes));
    }

    /**
     * Everything joined to the nodes, in either direction, without passing through the
     * river above them. In a tree this is {@link #downstream} plus the tributaries that
     * join it; in a braided network it also takes whatever leaves those tributaries.
     */
    Set<String> connectedDownstream(Set<String> nodes) {
        return reach(nodes, adjacent, above(nodes));
    }

    /** The nodes and everything linked to them in either direction. */
    Set<String> connected(Set<String> nodes) {
        return reach(nodes, adjacent, Set.of());
    }

    /** The river below the nodes: downstream of them and not also upstream of them. */
    private Set<String> below(Set<String> nodes) {
        Set<String> below = reach(nodes, downstreamOf, Set.of());
        below.removeAll(reach(nodes, upstreamOf, Set.of()));
        return below;
    }

    /** The river above the nodes: upstream of them and not also downstream of them. */
    private Set<String> above(Set<String> nodes) {
        Set<String> above = reach(nodes, upstreamOf, Set.of());
        above.removeAll(reach(nodes, downstreamOf, Set.of()));
        return above;
    }

    /**
     * The nodes and everything reachable from them by following {@code adjacency},
     * never stepping onto a node in {@code blocked}.
     */
    private static Set<String> reach(Set<String> nodes, Map<String, List<String>> adjacency,
                                     Set<String> blocked) {
        Deque<String> stack = new ArrayDeque<>(nodes);
        Set<String> visited = new HashSet<>(nodes);
        while (!stack.isEmpty()) {
            String node = stack.pop();
            for (String next : adjacency.getOrDefault(node, List.of())) {
                if (!blocked.contains(next) && visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return visited;
    }
}
