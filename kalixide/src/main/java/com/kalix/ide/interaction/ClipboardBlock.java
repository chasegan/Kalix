package com.kalix.ide.interaction;

import com.kalix.ide.model.NodeSectionLocator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the INI text that a paste inserts: the copied node sections, renamed if this
 * is a copy rather than a cut, with their coordinates translated to the drop point, and
 * joined into one block.
 *
 * <p>Pure — a function of the copied sections and the paste offset. Where the block goes
 * and how it is written there belong to {@code NodeInsertionPoint} and
 * {@code SectionSplice}; this only decides what it says.
 *
 * <p>Every read of the section text goes through {@link NodeSectionLocator}, so an
 * indented header, a {@code refloc} property, a commented-out {@code ds_1}, or an inline
 * comment on a link line all behave here exactly as they behave in the parser.
 */
final class ClipboardBlock {

    private ClipboardBlock() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * The lowest {@code _copyN} suffix that collides with no existing node name.
     *
     * @param existingNames every node name already in the model
     * @param copiedNames   the names being pasted
     */
    static String copySuffix(Collection<String> existingNames, Collection<String> copiedNames) {
        for (int n = 1; ; n++) {
            String suffix = "_copy" + n;
            boolean clash = false;
            for (String name : copiedNames) {
                if (existingNames.contains(name + suffix)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return suffix;
            }
        }
    }

    /**
     * The pasted block: sections in text order, one blank line between them.
     *
     * @param sections the copied sections, in text order
     * @param suffix   appended to every pasted node's name, or {@code null} for a cut,
     *                 which keeps the original names
     * @param offsetX  added to every section's x coordinate
     * @param offsetY  added to every section's y coordinate
     */
    static String build(List<ClipboardEntry.NodeSectionData> sections, String suffix,
                        double offsetX, double offsetY) {
        // Insertion order: a rename is applied against the original text, so the mapping
        // must be complete before any section is rewritten.
        Map<String, String> renames = new LinkedHashMap<>();
        if (suffix != null) {
            for (ClipboardEntry.NodeSectionData section : sections) {
                renames.put(section.originalName(), section.originalName() + suffix);
            }
        }

        List<String> texts = new ArrayList<>();
        for (ClipboardEntry.NodeSectionData section : sections) {
            String text = renameNodes(section.sectionText(), section.originalName(), renames);
            String name = renames.getOrDefault(section.originalName(), section.originalName());
            texts.add(withCoordinates(text, name, section.x() + offsetX, section.y() + offsetY).stripTrailing());
        }
        return String.join("\n\n", texts);
    }

    /**
     * Rewrites this section's header to its new name, and any {@code ds_N} link that
     * points at another node being pasted to that node's new name. Links to nodes
     * outside the paste are left alone — they still refer to the originals.
     *
     * <p>All edits are computed against the original text and applied right-to-left, so
     * no rename can see, or be applied on top of, another. A section named {@code a} and
     * one named {@code a_copy1} in the same paste therefore cannot cascade.</p>
     */
    static String renameNodes(String sectionText, String ownName, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return sectionText;
        }

        // (start, end, replacement), gathered against the original offsets.
        List<int[]> spans = new ArrayList<>();
        List<String> replacements = new ArrayList<>();

        NodeSectionLocator.NodeSection section = NodeSectionLocator.find(sectionText, ownName);
        String newOwnName = renames.get(ownName);
        if (section != null && newOwnName != null) {
            // The locator has confirmed the header, so the literal token is present at or
            // after the section start. Replacing the token keeps any indentation.
            String token = "[node." + ownName + "]";
            int at = sectionText.indexOf(token, section.start());
            if (at >= 0) {
                spans.add(new int[]{at, at + token.length()});
                replacements.add("[node." + newOwnName + "]");
            }
        }

        for (NodeSectionLocator.DsReference ref : NodeSectionLocator.findDsReferences(sectionText)) {
            String newTarget = renames.get(ref.target());
            if (newTarget == null) {
                continue;
            }
            String line = sectionText.substring(ref.lineStart(), ref.lineEnd());
            int valueStart = line.indexOf('=');
            int at = (valueStart < 0) ? -1 : line.indexOf(ref.target(), valueStart + 1);
            if (at >= 0) {
                spans.add(new int[]{ref.lineStart() + at, ref.lineStart() + at + ref.target().length()});
                replacements.add(newTarget);
            }
        }

        // Right-to-left keeps the earlier offsets valid as we edit.
        Integer[] order = new Integer[spans.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(spans.get(b)[0], spans.get(a)[0]));

        StringBuilder result = new StringBuilder(sectionText);
        for (int i : order) {
            result.replace(spans.get(i)[0], spans.get(i)[1], replacements.get(i));
        }
        return result.toString();
    }

    /**
     * Rewrites the section's {@code loc} value, preserving the author's separator. A
     * section with no parseable {@code loc} is returned unchanged.
     */
    static String withCoordinates(String sectionText, String nodeName, double x, double y) {
        NodeSectionLocator.NodeSection section = NodeSectionLocator.find(sectionText, nodeName);
        if (section == null || section.locValue() == null) {
            return sectionText;
        }
        NodeSectionLocator.LocValueSpan loc = section.locValue();
        String replacement = String.format(Locale.ROOT, "%.2f", x)
            + loc.separator()
            + String.format(Locale.ROOT, "%.2f", y);
        return sectionText.substring(0, loc.start()) + replacement + sectionText.substring(loc.end());
    }

    /**
     * The {@code loc} coordinates of a lone section, or {@code null} if it has none.
     */
    static double[] coordinatesOf(String sectionText, String nodeName) {
        NodeSectionLocator.NodeSection section = NodeSectionLocator.find(sectionText, nodeName);
        if (section == null || section.locValue() == null) {
            return null;
        }
        NodeSectionLocator.LocValueSpan loc = section.locValue();
        String span = sectionText.substring(loc.start(), loc.end());
        String[] parts = span.split(java.util.regex.Pattern.quote(loc.separator()), 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
