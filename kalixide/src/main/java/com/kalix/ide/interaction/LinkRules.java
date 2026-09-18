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
 * Whether a new link between two existing nodes may be drawn on the map.
 *
 * <p>Refuses only links that can never make sense: a node linked to itself, a second
 * link between the same pair, and a link that closes a loop. Anything the modeller might
 * mean and then fix in the text — an outlet the node type does not support, or a target
 * defined above its source (ADR-0005 §2.1) — is allowed and left to the linter.</p>
 */
public final class LinkRules {

    private LinkRules() {
    }

    /**
     * Returns why {@code upstream -> downstream} may not be linked, or null if it may.
     * The reason is short enough to show beside the cursor.
     *
     * @param links      the model's current links
     * @param upstream   the node the link flows from
     * @param downstream the node the link flows to
     */
    public static String refusal(Collection<ModelLink> links, String upstream, String downstream) {
        if (upstream.equals(downstream)) {
            return "Cannot link a node to itself";
        }
        Map<String, List<String>> downstreamOf = new HashMap<>();
        for (ModelLink link : links) {
            if (link.getUpstreamTerminus().equals(upstream)
                    && link.getDownstreamTerminus().equals(downstream)) {
                return "Already linked";
            }
            downstreamOf.computeIfAbsent(link.getUpstreamTerminus(), k -> new ArrayList<>())
                .add(link.getDownstreamTerminus());
        }
        if (reaches(downstreamOf, downstream, upstream)) {
            return "Would create a loop";
        }
        return null;
    }

    /** Whether {@code to} is reachable from {@code from} by following links downstream. */
    private static boolean reaches(Map<String, List<String>> downstreamOf, String from, String to) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(from);
        while (!pending.isEmpty()) {
            String node = pending.pop();
            if (node.equals(to)) {
                return true;
            }
            if (seen.add(node)) {
                for (String next : downstreamOf.getOrDefault(node, List.of())) {
                    pending.push(next);
                }
            }
        }
        return false;
    }
}
