package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.AnnotationKey;
import com.kalix.ide.linter.parsing.IniSyntax;
import com.kalix.ide.model.NodeSectionLocator;
import com.kalix.ide.model.NodeSectionLocator.AnnotationLine;
import com.kalix.ide.model.NodeSectionLocator.HeaderBlock;
import com.kalix.ide.model.NodeSectionLocator.NodeSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The cluster edits as pure functions from text to edits; {@link CommandExecutor} is the
 * one place that applies them. Create and Add are both {@link #assign}. All of them read
 * the text through {@link NodeSectionLocator}, never the model, so Rename and Disband
 * reach a section that isn't on the map, which would otherwise keep the old name.
 *
 * <p>A node ends with at most one {@code #@cluster} line, so it can't fall back to an
 * earlier line. The last line is rewritten in place and any others deleted, which keeps a
 * comment below the line inside the section. A node with none gets one directly above its
 * header, below any comments: those belong to the section above with or without the line
 * (ADR-0010 §3), so a later Cut takes the same text plus the annotation.</p>
 */
final class ClusterEdits {

    private static final String LINE_PREFIX = IniSyntax.ANNOTATION_PREFIX + AnnotationKey.CLUSTER.key() + " = ";

    private ClusterEdits() {
    }

    /** Puts each node in {@code cluster}, moving it from any cluster it was in. */
    static List<CommandExecutor.TextEdit> assign(String text, Collection<String> nodes, String cluster) {
        return editNodes(text, nodes, cluster);
    }

    /** Takes each node out of its cluster. */
    static List<CommandExecutor.TextEdit> clear(String text, Collection<String> nodes) {
        return editNodes(text, nodes, null);
    }

    /** Moves every member of {@code oldName} to {@code newName}, merging if that exists. */
    static List<CommandExecutor.TextEdit> rename(String text, String oldName, String newName) {
        List<CommandExecutor.TextEdit> edits = new ArrayList<>();
        for (NodeSection section : NodeSectionLocator.findAll(text)) {
            HeaderBlock block = NodeSectionLocator.headerBlock(text, section, AnnotationKey.CLUSTER);
            if (oldName.equals(cluster(block))) {
                addEdits(edits, text, block, newName);
            }
        }
        return edits;
    }

    /** Takes every member of the named clusters out of them; the nodes stay. */
    static List<CommandExecutor.TextEdit> disband(String text, Collection<String> clusters) {
        List<CommandExecutor.TextEdit> edits = new ArrayList<>();
        for (NodeSection section : NodeSectionLocator.findAll(text)) {
            HeaderBlock block = NodeSectionLocator.headerBlock(text, section, AnnotationKey.CLUSTER);
            String cluster = cluster(block);
            if (cluster != null && clusters.contains(cluster)) {
                addEdits(edits, text, block, null);
            }
        }
        return edits;
    }

    /** What is wrong with {@code name} as a cluster's name, or {@code null} if nothing is. */
    static String nameError(String name) {
        if (AnnotationKey.CLUSTER.isValidValue(name)) {
            return null;
        }
        return "Cluster name '" + name + "' must be a lowercase letter, then lowercase letters, "
                + "digits or underscores";
    }

    /** The cluster a section's lines put it in, read as the parser reads them. */
    private static String cluster(HeaderBlock block) {
        if (block.lines().isEmpty()) {
            return null;
        }
        String value = block.lines().getLast().value();
        return AnnotationKey.CLUSTER.isValidValue(value) ? value : null;
    }

    private static List<CommandExecutor.TextEdit> editNodes(String text, Collection<String> nodes, String cluster) {
        List<CommandExecutor.TextEdit> edits = new ArrayList<>();
        for (String node : new LinkedHashSet<>(nodes)) {
            NodeSection section = NodeSectionLocator.find(text, node);
            if (section != null) {
                addEdits(edits, text, NodeSectionLocator.headerBlock(text, section, AnnotationKey.CLUSTER), cluster);
            }
        }
        return edits;
    }

    /** Adds what leaves this section with one line naming {@code cluster}, or none if null. */
    private static void addEdits(List<CommandExecutor.TextEdit> edits, String text, HeaderBlock block, String cluster) {
        List<AnnotationLine> lines = block.lines();
        if (lines.isEmpty()) {
            if (cluster != null) {
                int at = block.headerStart();
                edits.add(new CommandExecutor.TextEdit(at, at, LINE_PREFIX + cluster + lineEnding(text, at)));
            }
            return;
        }

        for (AnnotationLine line : lines.subList(0, lines.size() - 1)) {
            edits.add(new CommandExecutor.TextEdit(line.lineStart(), line.lineEnd(), ""));
        }
        AnnotationLine last = lines.getLast();
        if (cluster == null) {
            edits.add(new CommandExecutor.TextEdit(last.lineStart(), last.lineEnd(), ""));
        } else if (!cluster.equals(last.value())) {
            edits.add(new CommandExecutor.TextEdit(last.lineStart(), last.contentEnd(), LINE_PREFIX + cluster));
        }
    }

    /** The line ending at {@code at}'s line, or the line before it if that is the last. */
    private static String lineEnding(String text, int at) {
        int newline = text.indexOf('\n', at);
        if (newline < 0 && at > 0) {
            newline = text.lastIndexOf('\n', at - 1);
        }
        return newline > 0 && text.charAt(newline - 1) == '\r' ? "\r\n" : "\n";
    }
}
