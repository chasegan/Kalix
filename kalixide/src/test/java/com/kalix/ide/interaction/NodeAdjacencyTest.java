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
    void distributaryStopsWhereItRejoinsBelowTheSelection() {
        // u -> s -> j, with a bypass u -> b -> j
        NodeAdjacency bypass = of("u", "s", "s", "j", "u", "b", "b", "j");

        assertEquals(Set.of("u", "s", "b"), bypass.upstreamWithDistributaries(Set.of("s")));
    }

    @Test
    void distributaryThatNeverRejoinsRunsToItsTerminus() {
        // u -> s -> j, with u -> b -> e leaving for good, and t joining b
        NodeAdjacency effluent = of("u", "s", "s", "j", "u", "b", "b", "e", "t", "b");

        assertEquals(Set.of("u", "s", "b", "e"), effluent.upstreamWithDistributaries(Set.of("s")),
            "the distributary's own tributary t is not part of it");
    }

    @Test
    void tributariesJoiningBelowTheSelectionAreFollowedToTheirHeadwaters() {
        // a -> s -> j -> k, with t2 -> t -> j
        NodeAdjacency network = of("a", "s", "s", "j", "j", "k", "t2", "t", "t", "j");

        assertEquals(Set.of("s", "j", "k", "t", "t2"), network.downstreamWithTributaries(Set.of("s")),
            "a is upstream of the selection, not a tributary below it");
    }

    @Test
    void nodesBetweenTwoSelectedNodesAreKept() {
        // h -> s1 -> m -> s2 -> t, with m -> x
        NodeAdjacency network = of("h", "s1", "s1", "m", "m", "s2", "s2", "t", "m", "x");

        assertEquals(Set.of("h", "s1", "m", "s2"),
            network.upstreamWithDistributaries(Set.of("s1", "s2")),
            "x lies below s1, so it is downstream of the selection, not a distributary");
        assertEquals(Set.of("s1", "m", "s2", "t", "x"),
            network.downstreamWithTributaries(Set.of("s1", "s2")));
    }

    @Test
    void withoutBranchesTheWiderSetsMatchTheNarrowOnes() {
        assertEquals(CHAIN.upstream(Set.of("c")), CHAIN.upstreamWithDistributaries(Set.of("c")));
        assertEquals(CHAIN.downstream(Set.of("b")), CHAIN.downstreamWithTributaries(Set.of("b")));
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
    }

    @Test
    void nodeWithoutLinksReturnsItself() {
        NodeAdjacency empty = of();

        assertEquals(Set.of("x"), empty.upstream(Set.of("x")));
        assertEquals(Set.of("x"), empty.downstream(Set.of("x")));
        assertEquals(Set.of("x"), empty.upstreamWithDistributaries(Set.of("x")));
        assertEquals(Set.of("x"), empty.downstreamWithTributaries(Set.of("x")));
        assertEquals(Set.of("x"), empty.connected(Set.of("x")));
    }
}
