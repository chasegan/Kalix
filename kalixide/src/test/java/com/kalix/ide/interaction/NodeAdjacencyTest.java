package com.kalix.ide.interaction;

import com.kalix.ide.model.ModelLink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeAdjacencyTest {

    private static NodeAdjacency of(String... pairs) {
        ModelLink[] links = new ModelLink[pairs.length / 2];
        for (int i = 0; i < links.length; i++) {
            links[i] = new ModelLink(pairs[2 * i], pairs[2 * i + 1], true);
        }
        return new NodeAdjacency(List.of(links));
    }

    // a -> b -> c -> d
    private static final NodeAdjacency CHAIN = of("a", "b", "b", "c", "c", "d");

    @Test
    void upstreamFollowsTheChainToItsHead() {
        assertEquals(Set.of("a", "b", "c"), CHAIN.upstream(Set.of("c")));
    }

    @Test
    void downstreamFollowsTheChainToItsTerminus() {
        assertEquals(Set.of("b", "c", "d"), CHAIN.downstream(Set.of("b")));
    }

    @Test
    void upstreamTakesEveryTributary() {
        // a -> c, b -> c, c -> d
        NodeAdjacency confluence = of("a", "c", "b", "c", "c", "d");

        assertEquals(Set.of("a", "b", "c"), confluence.upstream(Set.of("c")));
    }

    @Test
    void connectedUpstreamStopsAtTheRiverBelow() {
        // u -> s -> j, with a bypass u -> b -> j
        NodeAdjacency bypass = of("u", "s", "s", "j", "u", "b", "b", "j");

        assertEquals(Set.of("u", "s", "b"), bypass.connectedUpstream(Set.of("s")));
    }

    @Test
    void connectedUpstreamTakesWhatJoinsADistributary() {
        // u -> s -> j, with u -> b -> e leaving for good, and t joining b
        NodeAdjacency effluent = of("u", "s", "s", "j", "u", "b", "b", "e", "t", "b");

        assertEquals(Set.of("u", "s", "b", "e", "t"), effluent.connectedUpstream(Set.of("s")),
            "t reaches the selection's network without passing through the river below it");
    }

    @Test
    void connectedUpstreamLeavesATributaryThatJoinsBelow() {
        // u -> s -> j, with t -> j
        NodeAdjacency tree = of("u", "s", "s", "j", "t", "j");

        assertEquals(Set.of("u", "s"), tree.connectedUpstream(Set.of("s")),
            "t is joined to the selection only through j, which lies below it");
    }

    @Test
    void connectedDownstreamTakesTributariesToTheirHeadwaters() {
        // a -> s -> j -> k, with t2 -> t -> j
        NodeAdjacency network = of("a", "s", "s", "j", "j", "k", "t2", "t", "t", "j");

        assertEquals(Set.of("s", "j", "k", "t", "t2"), network.connectedDownstream(Set.of("s")),
            "a is upstream of the selection, not a tributary below it");
    }

    @Test
    void connectedDownstreamStopsAtTheRiverAbove() {
        // u -> s -> j, with a bypass u -> b -> j
        NodeAdjacency bypass = of("u", "s", "s", "j", "u", "b", "b", "j");

        assertEquals(Set.of("s", "j", "b"), bypass.connectedDownstream(Set.of("s")),
            "b joins the river below, but u is the river above");
    }

    @Test
    void connectedDownstreamTakesWhatLeavesATributary() {
        // a -> s -> j, with t -> j and t -> e
        NodeAdjacency split = of("a", "s", "s", "j", "t", "j", "t", "e");

        assertEquals(Set.of("s", "j", "t", "e"), split.connectedDownstream(Set.of("s")));
    }

    @Test
    void nodesBetweenTwoSelectedNodesAreKept() {
        // h -> s1 -> m -> s2 -> t, with m -> x
        NodeAdjacency network = of("h", "s1", "s1", "m", "m", "s2", "s2", "t", "m", "x");

        assertEquals(Set.of("h", "s1", "m", "s2"), network.connectedUpstream(Set.of("s1", "s2")),
            "m is below s1 but above s2, so it is not the river below the selection; x is");
        assertEquals(Set.of("s1", "m", "s2", "t", "x"), network.connectedDownstream(Set.of("s1", "s2")));
    }

    @Test
    void selectedNodesOwnBranchesAreTheRiverBelowOrAbove() {
        // t -> s, s -> o1, s -> o2
        NodeAdjacency splitter = of("t", "s", "s", "o1", "s", "o2");

        assertEquals(Set.of("t", "s"), splitter.connectedUpstream(Set.of("s")));
        assertEquals(Set.of("s", "o1", "o2"), splitter.connectedDownstream(Set.of("s")));
    }

    @Test
    void twoSelectedNodesOnParallelBranches() {
        // h -> a1 -> j -> k and h -> a2 -> j
        NodeAdjacency parallel = of("h", "a1", "h", "a2", "a1", "j", "a2", "j", "j", "k");

        assertEquals(Set.of("h", "a1", "a2"), parallel.connectedUpstream(Set.of("a1", "a2")));
        assertEquals(Set.of("a1", "a2", "j", "k"), parallel.connectedDownstream(Set.of("a1", "a2")));
    }

    @Test
    void inATreeTheConnectedSetsMatchTheNarrowOnes() {
        assertEquals(CHAIN.upstream(Set.of("c")), CHAIN.connectedUpstream(Set.of("c")));
        assertEquals(CHAIN.downstream(Set.of("b")), CHAIN.connectedDownstream(Set.of("b")));
    }

    @Test
    void connectedStaysWithinTheSelectedComponent() {
        // a -> b, and separately c -> d
        NodeAdjacency twoNetworks = of("a", "b", "c", "d");

        assertEquals(Set.of("a", "b"), twoNetworks.connected(Set.of("b")));
        assertEquals(Set.of("a", "b", "c", "d"), twoNetworks.connected(Set.of("a", "d")));
    }

    @Test
    void connectedCrossesConfluencesInBothDirections() {
        // a -> c, b -> c: b is neither upstream nor downstream of a
        NodeAdjacency confluence = of("a", "c", "b", "c");

        assertEquals(Set.of("a", "b", "c"), confluence.connected(Set.of("a")));
    }

    @Test
    void loopsTerminate() {
        // a -> b -> c -> a, with c -> d
        NodeAdjacency loop = of("a", "b", "b", "c", "c", "a", "c", "d");

        assertEquals(Set.of("a", "b", "c", "d"), loop.upstream(Set.of("d")));
        assertEquals(Set.of("a", "b", "c", "d"), loop.downstream(Set.of("a")));
        assertEquals(Set.of("a", "b", "c", "d"), loop.connected(Set.of("b")));
        assertEquals(Set.of("a", "b", "c"), loop.connectedUpstream(Set.of("b")),
            "the loop is both above and below b, so only d is the river below");
        assertEquals(Set.of("a", "b", "c", "d"), loop.connectedDownstream(Set.of("b")));
    }

    @Test
    void selfLoopsAndDuplicateLinksTerminate() {
        // a -> a, a -> b twice, b -> c
        NodeAdjacency odd = of("a", "a", "a", "b", "a", "b", "b", "c");

        assertEquals(Set.of("a", "b"), odd.upstream(Set.of("b")));
        assertEquals(Set.of("a", "b", "c"), odd.connectedDownstream(Set.of("a")));
    }

    @Test
    void nodeWithoutLinksReturnsItself() {
        NodeAdjacency empty = of();

        assertEquals(Set.of("x"), empty.upstream(Set.of("x")));
        assertEquals(Set.of("x"), empty.downstream(Set.of("x")));
        assertEquals(Set.of("x"), empty.connectedUpstream(Set.of("x")));
        assertEquals(Set.of("x"), empty.connectedDownstream(Set.of("x")));
        assertEquals(Set.of("x"), empty.connected(Set.of("x")));
    }

    @Test
    void noNodesGiveNoResult() {
        assertEquals(Set.of(), CHAIN.upstream(Set.of()));
        assertEquals(Set.of(), CHAIN.connectedUpstream(Set.of()));
        assertEquals(Set.of(), CHAIN.connected(Set.of()));
    }
}
