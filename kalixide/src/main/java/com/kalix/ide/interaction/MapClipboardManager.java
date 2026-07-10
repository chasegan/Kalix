package com.kalix.ide.interaction;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.model.NodeInsertionPoint;
import com.kalix.ide.model.NodeSectionLocator;
import com.kalix.ide.model.SectionSplice;
import com.kalix.ide.editor.EnhancedTextEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.*;

/**
 * Manages clipboard operations for map nodes including cut, copy, and paste.
 * Handles extraction of node sections from text, name suffix generation for copies,
 * coordinate translation, and internal link reference updates.
 */
public class MapClipboardManager {
    private static final Logger logger = LoggerFactory.getLogger(MapClipboardManager.class);

    private final HydrologicalModel model;
    private final EnhancedTextEditor textEditor;
    private final TextCoordinateUpdater textUpdater;

    // Clipboard storage
    private ClipboardEntry clipboard;

    public MapClipboardManager(HydrologicalModel model, EnhancedTextEditor textEditor,
                                TextCoordinateUpdater textUpdater) {
        this.model = model;
        this.textEditor = textEditor;
        this.textUpdater = textUpdater;
    }

    /**
     * Cut selected nodes to clipboard.
     * Extracts node sections and stores them, then deletes the original nodes.
     */
    public void cut() {
        Set<String> selectedNodes = model.getSelectedNodes();
        if (selectedNodes.isEmpty()) {
            return;
        }

        // Extract node sections
        List<ClipboardEntry.NodeSectionData> sections = extractNodeSections(selectedNodes);
        if (sections.isEmpty()) {
            return;
        }

        // Store in clipboard with anchor coordinates
        ClipboardEntry.NodeSectionData anchor = sections.get(0);
        clipboard = new ClipboardEntry(sections, true, anchor.x(), anchor.y());

        // Also copy to system clipboard for external paste
        copyToSystemClipboard(sections);

        // Delete the original nodes
        textUpdater.deleteSelectedElements(selectedNodes, Collections.emptySet());
        model.deleteSelectedNodes();

        logger.info("Cut {} nodes to clipboard", sections.size());
    }

    /**
     * Copy selected nodes to clipboard.
     * Extracts node sections and stores them without deleting originals.
     */
    public void copy() {
        Set<String> selectedNodes = model.getSelectedNodes();
        if (selectedNodes.isEmpty()) {
            return;
        }

        // Extract node sections
        List<ClipboardEntry.NodeSectionData> sections = extractNodeSections(selectedNodes);
        if (sections.isEmpty()) {
            return;
        }

        // Store in clipboard with anchor coordinates
        ClipboardEntry.NodeSectionData anchor = sections.get(0);
        clipboard = new ClipboardEntry(sections, false, anchor.x(), anchor.y());

        // Also copy to system clipboard for external paste
        copyToSystemClipboard(sections);

        logger.info("Copied {} nodes to clipboard", sections.size());
    }

    /**
     * Paste clipboard content at the specified map location.
     * @param pasteLocationX World X coordinate for paste
     * @param pasteLocationY World Y coordinate for paste
     */
    public void pasteAtMapLocation(double pasteLocationX, double pasteLocationY) {
        if (clipboard == null || clipboard.nodeSections().isEmpty()) {
            return;
        }

        List<ClipboardEntry.NodeSectionData> sections = clipboard.nodeSections();

        // Calculate offset from anchor to paste location
        double offsetX = pasteLocationX - clipboard.anchorX();
        double offsetY = pasteLocationY - clipboard.anchorY();

        // A cut keeps the original names; a copy needs fresh ones.
        String suffix = clipboard.isCut() ? null : ClipboardBlock.copySuffix(existingNodeNames(), copiedNames(sections));

        // What the block says is ClipboardBlock's decision; where it goes and how it is
        // written there belong to NodeInsertionPoint and SectionSplice, exactly as for
        // template insertion.
        insertSectionBlock(ClipboardBlock.build(sections, suffix, offsetX, offsetY));

        // Clear clipboard after cut paste (can only paste once)
        if (clipboard.isCut()) {
            clipboard = null;
        }

        logger.info("Pasted {} nodes at ({}, {})", sections.size(), pasteLocationX, pasteLocationY);
    }

    /**
     * Check if clipboard has content available to paste.
     */
    public boolean hasClipboardContent() {
        return clipboard != null && !clipboard.nodeSections().isEmpty();
    }

    /**
     * Check if the current selection can be cut/copied.
     */
    public boolean canCutOrCopy() {
        return model != null && model.getSelectedNodeCount() > 0;
    }

    // ========== INTERNAL METHODS ==========

    /**
     * Extract node sections from text for the given node names.
     * Returns sections ordered by their position in the text file.
     */
    private List<ClipboardEntry.NodeSectionData> extractNodeSections(Set<String> nodeNames) {
        List<ClipboardEntry.NodeSectionData> sections = new ArrayList<>();

        try {
            Document doc = textEditor.getTextArea().getDocument();
            String text = doc.getText(0, doc.getLength());

            // Find all node sections and their positions
            Map<String, Integer> nodePositions = new HashMap<>();
            for (String nodeName : nodeNames) {
                int[] bounds = getNodeSectionBounds(text, nodeName);
                if (bounds != null) {
                    nodePositions.put(nodeName, bounds[0]);
                }
            }

            // Sort by text position
            List<String> sortedNames = new ArrayList<>(nodePositions.keySet());
            sortedNames.sort(Comparator.comparingInt(nodePositions::get));

            // Extract each section in order
            int textOrder = 0;
            for (String nodeName : sortedNames) {
                int[] bounds = getNodeSectionBounds(text, nodeName);
                if (bounds != null) {
                    String sectionText = text.substring(bounds[0], bounds[1]);
                    ClipboardEntry.NodeSectionData data = parseNodeSection(nodeName, sectionText, textOrder++);
                    if (data != null) {
                        sections.add(data);
                    }
                }
            }
        } catch (BadLocationException e) {
            logger.error("Error extracting node sections: {}", e.getMessage());
        }

        return sections;
    }

    /**
     * Find the text boundaries of a node section via the shared INI-section grammar.
     */
    private int[] getNodeSectionBounds(String text, String nodeName) {
        NodeSectionLocator.NodeSection section = NodeSectionLocator.find(text, nodeName);
        if (section != null) {
            return new int[] { section.start(), section.end() };
        }
        return null;
    }

    /**
     * Parse a node section to extract its properties.
     */
    private ClipboardEntry.NodeSectionData parseNodeSection(String nodeName, String sectionText, int textOrder) {
        double[] loc = ClipboardBlock.coordinatesOf(sectionText, nodeName);
        if (loc == null) {
            logger.warn("Node section has no parseable loc property: {}", nodeName);
            loc = new double[]{0, 0};
        }
        return new ClipboardEntry.NodeSectionData(nodeName, sectionText, loc[0], loc[1], textOrder);
    }

    /** Every node name currently in the model. */
    private Collection<String> existingNodeNames() {
        List<String> names = new ArrayList<>();
        for (ModelNode node : model.getAllNodes()) {
            names.add(node.getName());
        }
        return names;
    }

    /** The names of the sections being pasted. */
    private static Collection<String> copiedNames(List<ClipboardEntry.NodeSectionData> sections) {
        List<String> names = new ArrayList<>();
        for (ClipboardEntry.NodeSectionData section : sections) {
            names.add(section.originalName());
        }
        return names;
    }




    /**
     * Inserts a block of one or more node sections relative to the caret, as a single
     * atomic edit.
     *
     * <p>Where it goes is {@link NodeInsertionPoint}'s decision and how it is written
     * there is {@link SectionSplice}'s — the same two policies template insertion uses,
     * so paste and insert cannot drift apart, and neither can hold its own idea of what
     * an INI section header looks like.</p>
     */
    private void insertSectionBlock(String block) {
        try {
            Document doc = textEditor.getTextArea().getDocument();
            String text = doc.getText(0, doc.getLength());
            int caret = textEditor.getTextArea().getCaretPosition();

            SectionSplice.Splice splice = SectionSplice.compute(text, NodeInsertionPoint.forAnchor(text, caret), block);

            textEditor.getTextArea().beginAtomicEdit();
            try {
                doc.remove(splice.start(), splice.end() - splice.start());
                doc.insertString(splice.start(), splice.text(), null);
            } finally {
                textEditor.getTextArea().endAtomicEdit();
            }
        } catch (BadLocationException e) {
            logger.error("Error inserting node sections: {}", e.getMessage());
        }
    }

    /**
     * Copy node section text to the OS system clipboard.
     * This allows pasting the INI text into external programs.
     * Works cross-platform (Windows, macOS, Linux) using java.awt.Toolkit.
     */
    private void copyToSystemClipboard(List<ClipboardEntry.NodeSectionData> sections) {
        if (sections == null || sections.isEmpty()) {
            return;
        }

        // Build combined text from all sections
        StringBuilder sb = new StringBuilder();
        for (ClipboardEntry.NodeSectionData section : sections) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(section.sectionText());
        }

        // Copy to system clipboard
        try {
            StringSelection selection = new StringSelection(sb.toString());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        } catch (Exception e) {
            // Clipboard access can fail in headless environments or with security restrictions
            logger.warn("Could not copy to system clipboard: {}", e.getMessage());
        }
    }
}
