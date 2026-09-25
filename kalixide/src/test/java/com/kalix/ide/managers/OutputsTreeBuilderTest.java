package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.RunSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the split between what the outputs tree <em>shows</em> and what the checked
 * sources <em>offer</em>. The filter narrows the first and must never narrow the
 * second: RunManager prunes a tab's plotted series against
 * {@link OutputsTreeBuilder#availableRefs}, so a filter leaking into it empties the
 * plot on the next source change (#431).
 */
class OutputsTreeBuilderTest {

    /** Sources are run ids; each offers the series named in the map. */
    private static final Map<Object, List<String>> OUTPUTS = Map.of(
        1L, List.of("node.a.ds_1", "node.b.ds_1"),
        2L, List.of("node.a.ds_1"));

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final OutputsTreeBuilder builder = new OutputsTreeBuilder(
        new JTree(model),
        model,
        source -> new ArrayList<>(OUTPUTS.getOrDefault(source, List.of())),
        String::compareTo,
        (seriesName, source) -> new RunSeries((Long) source, seriesName),
        new LabelResolver() {
            @Override public String labelFor(SeriesRef ref) { return ref.toString(); }
            @Override public String sourceLabel(SeriesRef ref) { return "Run_" + ((RunSeries) ref).runId(); }
        });

    private void filter(String text) throws SeriesFilter.SyntaxException {
        builder.setFilter(SeriesFilter.parse(text));
    }

    private Set<SeriesRef> refsInTree() {
        Set<SeriesRef> refs = new HashSet<>();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object userObject = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
            if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
                refs.add(leaf.ref);
            }
        }
        return refs;
    }

    @Test
    void availableRefsMatchTheUnfilteredTree() {
        builder.updateTree(List.of(1L, 2L), List.of());

        assertEquals(
            Set.of(new RunSeries(1L, "node.a.ds_1"), new RunSeries(1L, "node.b.ds_1"),
                   new RunSeries(2L, "node.a.ds_1")),
            builder.availableRefs(List.of(1L, 2L)));
        assertEquals(refsInTree(), builder.availableRefs(List.of(1L, 2L)));
    }

    @Test
    void filterNarrowsTheTreeButNotWhatSourcesOffer() throws Exception {
        filter("nomatch");
        builder.updateTree(List.of(1L, 2L), List.of());
        assertTrue(refsInTree().isEmpty(), "filter should have hidden every series");

        filter("node.b");  // matches across tree levels
        builder.updateTree(List.of(1L, 2L), List.of());
        assertEquals(Set.of(new RunSeries(1L, "node.b.ds_1")), refsInTree());

        // Hidden series are still offered — this is what keeps them plotted.
        assertEquals(3, builder.availableRefs(List.of(1L, 2L)).size());
    }

    @Test
    void filterMatchesSourceLabelsAndExcludes() throws Exception {
        filter("Run_2");
        builder.updateTree(List.of(1L, 2L), List.of());
        assertEquals(Set.of(new RunSeries(2L, "node.a.ds_1")), refsInTree());

        filter("!Run_2");
        builder.updateTree(List.of(1L, 2L), List.of());
        assertEquals(Set.of(new RunSeries(1L, "node.a.ds_1"), new RunSeries(1L, "node.b.ds_1")),
                     refsInTree());

        filter("node.*.ds_1 !a");
        builder.updateTree(List.of(1L, 2L), List.of());
        assertEquals(Set.of(new RunSeries(1L, "node.b.ds_1")), refsInTree());

        builder.setFilter(SeriesFilter.NONE);
        builder.updateTree(List.of(1L, 2L), List.of());
        assertEquals(3, refsInTree().size());
    }

    @Test
    void uncheckedSourcesOfferNothing() {
        assertEquals(Set.of(new RunSeries(2L, "node.a.ds_1")), builder.availableRefs(List.of(2L)));
        assertTrue(builder.availableRefs(List.of()).isEmpty());
    }
}
