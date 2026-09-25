package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.RunSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private final JTree tree = new JTree(model);
    private final OutputsTreeBuilder builder = new OutputsTreeBuilder(
        tree,
        model,
        // Immutable, as RunManager returns for an aggregate: the builder must not sort in place.
        source -> OUTPUTS.getOrDefault(source, List.of()),
        String::compareTo,
        (seriesName, source) -> new RunSeries((Long) source, seriesName),
        ref -> ref.toString());

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
        builder.updateTree(List.of(1L, 2L));

        assertEquals(
            Set.of(new RunSeries(1L, "node.a.ds_1"), new RunSeries(1L, "node.b.ds_1"),
                   new RunSeries(2L, "node.a.ds_1")),
            builder.availableRefs(List.of(1L, 2L)));
        assertEquals(refsInTree(), builder.availableRefs(List.of(1L, 2L)));
    }

    @Test
    void filterNarrowsTheTreeButNotWhatSourcesOffer() {
        builder.setFilterText("node.b");  // matches nothing: segments are separate nodes
        builder.updateTree(List.of(1L, 2L));
        assertTrue(refsInTree().isEmpty(), "filter should have hidden every series");

        builder.setFilterText("b");
        builder.updateTree(List.of(1L, 2L));
        assertEquals(Set.of(new RunSeries(1L, "node.b.ds_1")), refsInTree());

        // Hidden series are still offered — this is what keeps them plotted.
        assertEquals(3, builder.availableRefs(List.of(1L, 2L)).size());
    }

    @Test
    void uncheckedSourcesOfferNothing() {
        assertEquals(Set.of(new RunSeries(2L, "node.a.ds_1")), builder.availableRefs(List.of(2L)));
        assertTrue(builder.availableRefs(List.of()).isEmpty());
    }

    @Test
    void rebuildKeepsCollapsedFolders() {
        builder.updateTree(List.of(1L, 2L));
        TreePath b = pathTo("node", "b");
        tree.collapsePath(b);

        builder.updateTree(List.of(1L, 2L));

        assertFalse(tree.isExpanded(pathTo("node", "b")), "b should stay collapsed");
        assertTrue(tree.isExpanded(pathTo("node", "a")), "a should stay expanded");
    }

    @Test
    void singleSourceWithImmutableNamesBuilds() {
        builder.updateTree(List.of(1L));
        assertEquals(Set.of(new RunSeries(1L, "node.a.ds_1"), new RunSeries(1L, "node.b.ds_1")),
            refsInTree());
    }

    private TreePath pathTo(String... names) {
        DefaultMutableTreeNode node = root;
        for (String name : names) {
            DefaultMutableTreeNode next = null;
            for (int i = 0; i < node.getChildCount() && next == null; i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                if (name.equals(child.toString())) {
                    next = child;
                }
            }
            if (next == null) {
                throw new AssertionError("No node " + name + " under " + node);
            }
            node = next;
        }
        return new TreePath(node.getPath());
    }
}
