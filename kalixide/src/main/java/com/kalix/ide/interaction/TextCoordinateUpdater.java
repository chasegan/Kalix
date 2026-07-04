package com.kalix.ide.interaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.model.NodeSectionLocator;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JViewport;
import javax.swing.text.Document;
import javax.swing.text.BadLocationException;

/**
 * Handles updating node coordinates in the text editor when nodes are moved via dragging.
 * This provides bidirectional synchronization between the visual map and the text INI format.
 *
 * <p>All section/property location goes through {@link NodeSectionLocator} — the single
 * INI-section grammar shared with the parser — so text edits land exactly where the
 * parser reads.
 */
public class TextCoordinateUpdater {
    private static final Logger logger = LoggerFactory.getLogger(TextCoordinateUpdater.class);

    private final EnhancedTextEditor textEditor;

    public TextCoordinateUpdater(EnhancedTextEditor textEditor) {
        this.textEditor = textEditor;
    }

    /**
     * Update coordinates for multiple nodes as a single atomic undo operation.
     * @param nodeUpdates Map of node names to their new coordinates
     */
    public void updateNodeCoordinates(java.util.Map<String, java.awt.geom.Point2D.Double> nodeUpdates) {
        if (textEditor == null || nodeUpdates == null || nodeUpdates.isEmpty()) {
            return;
        }

        // Group all coordinate updates as single atomic undo operation
        textEditor.getTextArea().beginAtomicEdit();
        try {
            for (java.util.Map.Entry<String, java.awt.geom.Point2D.Double> entry : nodeUpdates.entrySet()) {
                String nodeName = entry.getKey();
                java.awt.geom.Point2D.Double coords = entry.getValue();
                updateSingleNodeCoordinate(nodeName, coords.x, coords.y);
            }
        } finally {
            textEditor.getTextArea().endAtomicEdit();
        }
    }

    /**
     * Internal helper to update a single node's coordinate.
     * Document operations are performed without atomic edit wrapping (caller handles that).
     */
    private void updateSingleNodeCoordinate(String nodeName, double x, double y) {
        if (textEditor == null) {
            return;
        }

        try {
            Document doc = textEditor.getTextArea().getDocument();
            String currentText = doc.getText(0, doc.getLength());

            if (currentText == null || currentText.trim().isEmpty()) {
                return;
            }

            NodeSectionLocator.NodeSection section = NodeSectionLocator.find(currentText, nodeName);
            if (section == null) {
                logger.warn("Could not find node section for: {}", nodeName);
                return;
            }
            NodeSectionLocator.LocValueSpan loc = section.locValue();
            if (loc == null) {
                logger.warn("Node section has no loc property: {}", nodeName);
                return;
            }

            // Format coordinates with reasonable precision (2 decimal places),
            // preserving the author's original separator formatting.
            String replacement = String.format("%.2f", x) + loc.separator() + String.format("%.2f", y);

            // Perform document operations (atomic edit wrapping handled by caller)
            doc.remove(loc.start(), loc.end() - loc.start());
            doc.insertString(loc.start(), replacement, null);

        } catch (BadLocationException e) {
            logger.error("Bad location error updating coordinates for {}: {}", nodeName, e.getMessage());
        } catch (Exception e) {
            logger.error("Error updating coordinates for {}: {}", nodeName, e.getMessage());
        }
    }

    /**
     * Scrolls the text editor to the specified node definition.
     * Finds the node's section header and positions it in the visible area.
     * @param nodeName Name of the node to scroll to
     * @return true if the node was found and scrolled to, false otherwise
     */
    public boolean scrollToNode(String nodeName) {
        if (textEditor == null || nodeName == null || nodeName.trim().isEmpty()) {
            return false;
        }

        try {
            Document doc = textEditor.getTextArea().getDocument();
            String currentText = doc.getText(0, doc.getLength());

            if (currentText == null || currentText.trim().isEmpty()) {
                return false;
            }

            NodeSectionLocator.NodeSection section = NodeSectionLocator.find(currentText, nodeName);

            if (section != null) {
                // Record navigation jump before moving caret
                textEditor.recordNavigationJump(section.start());

                // Set caret position to the start of the node section (the [node.name] header)
                textEditor.getTextArea().setCaretPosition(section.start());

                // Smart scroll: position the node at 1/4 from the top of the viewport
                // This provides good context and matches common editor behavior
                if (textEditor.getTextArea().getParent() instanceof JViewport viewport) {
                    Rectangle viewRect = viewport.getViewRect();
                    Rectangle caretRect = textEditor.getTextArea().modelToView(section.start());

                    // Position caret at 1/4 from top of viewport
                    int desiredY = caretRect.y - (viewRect.height / 4);
                    desiredY = Math.max(0, desiredY); // Don't scroll past document start

                    // Scroll to position
                    viewport.setViewPosition(new Point(viewRect.x, desiredY));
                } else {
                    // Fallback to default scrolling if viewport not available
                    textEditor.getTextArea().getCaret().setSelectionVisible(true);
                }

                // Note: We intentionally do NOT request focus here.
                // The map should retain focus so Delete key works on selected nodes.
                return true;
            } else {
                logger.warn("Could not find node section to scroll to: {}", nodeName);
                return false;
            }

        } catch (BadLocationException e) {
            logger.error("Bad location error scrolling to node {}: {}", nodeName, e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("Error scrolling to node {}: {}", nodeName, e.getMessage());
            return false;
        }
    }

    /**
     * A half-open character span {@code [start, end)} of text to delete.
     */
    record TextSpan(int start, int end) {
        int length() {
            return end - start;
        }
    }

    /**
     * Delete selected nodes and links from the text editor as a single atomic operation.
     * This handles:
     * - Removal of entire node sections for selected nodes
     * - Removal of ds_X = target property lines for selected links
     * - Removal of dangling ds_X = deletedNode lines from surviving sections
     *
     * @param nodeNames Set of node names to delete (may be null or empty)
     * @param linkIds Set of link IDs to delete in "source->target" format (may be null or empty)
     */
    public void deleteSelectedElements(Set<String> nodeNames, Set<String> linkIds) {
        if ((nodeNames == null || nodeNames.isEmpty()) &&
            (linkIds == null || linkIds.isEmpty())) {
            return;
        }

        textEditor.getTextArea().beginAtomicEdit();
        try {
            Document doc = textEditor.getTextArea().getDocument();
            String text = doc.getText(0, doc.getLength());

            List<TextSpan> deletions = computeDeletionSpans(text, nodeNames, linkIds);

            // Apply in reverse document order so earlier offsets stay valid
            for (int i = deletions.size() - 1; i >= 0; i--) {
                TextSpan span = deletions.get(i);
                doc.remove(span.start(), span.length());
            }
        } catch (BadLocationException e) {
            logger.error("Error deleting elements: {}", e.getMessage());
        } finally {
            textEditor.getTextArea().endAtomicEdit();
        }
    }

    /**
     * Pure computation of the text spans to delete for a node/link deletion:
     * the deleted nodes' sections, the selected links' ds_X property lines, and any
     * dangling ds_X lines in surviving sections that reference a deleted node.
     * Overlapping spans are merged, and the result is sorted by position.
     *
     * <p>Static and Swing-free for testability.
     *
     * @param text      full model text
     * @param nodeNames node names to delete (may be null or empty)
     * @param linkIds   link IDs to delete in "source->target" format (may be null or empty)
     * @return merged, ascending-sorted spans to remove
     */
    static List<TextSpan> computeDeletionSpans(String text, Set<String> nodeNames, Set<String> linkIds) {
        Set<String> deletedNodes = nodeNames != null ? nodeNames : Set.of();
        List<TextSpan> deletions = new ArrayList<>();

        // 1. Node sections to delete
        for (String nodeName : deletedNodes) {
            NodeSectionLocator.NodeSection section = NodeSectionLocator.find(text, nodeName);
            if (section != null) {
                deletions.add(new TextSpan(section.start(), section.end()));
            }
        }

        // 2. Explicitly selected links: their ds_X lines in surviving source sections
        Map<String, Set<String>> sourceToTargets = new HashMap<>();
        if (linkIds != null) {
            for (String linkId : linkIds) {
                String[] parts = linkId.split("->");
                // Skip if source node is being deleted (section removal handles it)
                if (parts.length == 2 && !deletedNodes.contains(parts[0])) {
                    sourceToTargets.computeIfAbsent(parts[0], k -> new HashSet<>()).add(parts[1]);
                }
            }
        }

        boolean wantDsLines = !sourceToTargets.isEmpty() || !deletedNodes.isEmpty();
        if (wantDsLines) {
            for (NodeSectionLocator.DsReference ref : NodeSectionLocator.findDsReferences(text)) {
                // 3. Dangling references: any ds_X line pointing at a deleted node.
                // Lines inside deleted sections are swallowed by the span merge below.
                boolean dangling = deletedNodes.contains(ref.target());
                boolean selectedLink = sourceToTargets.getOrDefault(ref.sourceNode(), Set.of())
                    .contains(ref.target());
                if (dangling || selectedLink) {
                    deletions.add(new TextSpan(ref.lineStart(), ref.lineEnd()));
                }
            }
        }

        return mergeSpans(deletions);
    }

    /**
     * Sorts spans by start offset and merges overlapping or contained spans
     * (e.g. a ds_X line inside a deleted node section) so each region is
     * deleted exactly once.
     */
    private static List<TextSpan> mergeSpans(List<TextSpan> spans) {
        spans.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<TextSpan> merged = new ArrayList<>();
        for (TextSpan span : spans) {
            TextSpan last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && span.start() < last.end()) {
                if (span.end() > last.end()) {
                    merged.set(merged.size() - 1, new TextSpan(last.start(), span.end()));
                }
            } else {
                merged.add(span);
            }
        }
        return merged;
    }
}
