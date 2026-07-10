package com.kalix.ide.model;

import com.kalix.ide.model.NodeSectionLocator.NodeSection;

import java.util.Collection;
import java.util.List;

/**
 * Where a newly-created node section belongs in the model text.
 *
 * <p>This is policy, not grammar. {@link NodeSectionLocator} answers <em>where the
 * sections are</em>; this class answers <em>where a new one goes</em>. Keeping them
 * apart stops the locator accreting each feature's preferences.
 *
 * <p>The rule, given an anchor offset (a caret, or a selected node):
 *
 * <ol>
 *   <li>Anchor above the first node section → immediately <b>before</b> the first node.</li>
 *   <li>Anchor within a node section → immediately <b>below</b> that node.</li>
 *   <li>Anchor after the last node section → immediately <b>below</b> the last node.</li>
 * </ol>
 *
 * <p>Clauses 2 and 3 are the same statement — "below the last node section that starts
 * at or before the anchor" — so the implementation has one branch, not three. A model
 * with no node sections at all appends at the end of the text.
 *
 * <p>"Below" means at the section's {@link NodeSection#contentEnd()}, not its
 * {@code end()}: a comment block between two nodes introduces the node beneath it, so
 * a new node belongs above that block.
 *
 * <p>Text position carries no geographic meaning — it is the logical calculation
 * sequence. A node's map coordinates are set independently of where its section lands.
 */
public final class NodeInsertionPoint {

    private NodeInsertionPoint() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * The offset at which to insert a new node section, relative to an anchor offset
     * such as the caret position.
     *
     * @param text         full model text (may be {@code null})
     * @param anchorOffset an offset into {@code text}; clamped into range
     * @return a valid offset into {@code text}
     */
    public static int forAnchor(String text, int anchorOffset) {
        if (text == null) {
            return 0;
        }
        return forAnchor(text, NodeSectionLocator.findAll(text), anchorOffset);
    }

    /**
     * The offset at which to insert a new node section given the currently selected
     * nodes: immediately below the last selected node in <em>document</em> order (a
     * selection is a set and carries no order of its own). With nothing selected, or
     * nothing selected that exists in the text, the new node goes at the bottom.
     *
     * @param text              full model text (may be {@code null})
     * @param selectedNodeNames selected node names (may be {@code null} or empty)
     * @return a valid offset into {@code text}
     */
    public static int forSelection(String text, Collection<String> selectedNodeNames) {
        if (text == null) {
            return 0;
        }
        List<NodeSection> sections = NodeSectionLocator.findAll(text);

        // findAll is in document order, so the last match is the last selected node.
        // A duplicated name therefore resolves to its final occurrence, as it should.
        NodeSection lastSelected = null;
        if (selectedNodeNames != null && !selectedNodeNames.isEmpty()) {
            for (NodeSection section : sections) {
                if (selectedNodeNames.contains(section.nodeName())) {
                    lastSelected = section;
                }
            }
        }

        // Anchoring on the section's start makes clause 2 select that very section,
        // so "below the last selected node" needs no separate code path.
        int anchor = (lastSelected == null) ? text.length() : lastSelected.start();
        return forAnchor(text, sections, anchor);
    }

    private static int forAnchor(String text, List<NodeSection> sections, int anchorOffset) {
        if (sections.isEmpty()) {
            return text.length();
        }
        int anchor = Math.max(0, Math.min(anchorOffset, text.length()));

        NodeSection preceding = null;
        for (NodeSection section : sections) {
            if (section.start() > anchor) {
                break;
            }
            preceding = section;
        }

        return (preceding == null)
            ? sections.get(0).start()   // clause 1: above the first node
            : preceding.contentEnd();   // clauses 2 and 3: below the node we are in, or past
    }
}
