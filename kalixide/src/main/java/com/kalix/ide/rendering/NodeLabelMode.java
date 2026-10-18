package com.kalix.ide.rendering;

import com.kalix.ide.model.ModelNode;

import java.util.List;

/**
 * Which text the map draws under each node. One toolbar button cycles through the
 * three modes in the order declared here; the rule for what a node shows, with and
 * without the mouse over it, lives in {@link #labelLines(ModelNode, boolean)} so
 * the renderer has nothing to decide.
 *
 * <p>A node's {@code aka} (ADR-0010 §6.1) is a readable alternative to its name.
 * {@link #AKA} draws only nodes that have one, which keeps a dense model's map
 * sparse: a modeller annotates the nodes that matter and the rest fall quiet.</p>
 */
public enum NodeLabelMode {

    /** Every node's name. Hover reveals nothing; there is nothing hidden. */
    NAMES("names"),

    /** Only nodes with an {@code aka}, by their aka. Hover swaps in the node's name. */
    AKA("aka"),

    /** Nothing. Hover reveals the node's aka, if it has one, and its name. */
    HIDDEN("hidden");

    private final String key;

    NodeLabelMode(String key) {
        this.key = key;
    }

    /** The stored preference value for this mode. */
    public String key() {
        return key;
    }

    /** The mode stored under {@code key}, or {@code null} if it names none. */
    public static NodeLabelMode fromKey(String key) {
        for (NodeLabelMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }

    /** The next mode in the cycle: names, aka, hidden, names. */
    public NodeLabelMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** True if any label is drawn without hovering, i.e. the toolbar button is selected. */
    public boolean drawsLabels() {
        return this != HIDDEN;
    }

    /** True if hovering a node changes what is drawn, so the map must hit-test on mouse move. */
    public boolean revealsOnHover() {
        return this != NAMES;
    }

    /**
     * The lines drawn under {@code node}, top first; empty when nothing is drawn.
     *
     * @param hovered whether the mouse is over this node
     */
    public List<String> labelLines(ModelNode node, boolean hovered) {
        String aka = node.getAka();
        switch (this) {
            case NAMES:
                return List.of(node.getName());
            case AKA:
                if (hovered) {
                    return List.of(node.getName());
                }
                return aka == null ? List.of() : List.of(aka);
            case HIDDEN:
                if (!hovered) {
                    return List.of();
                }
                return aka == null ? List.of(node.getName()) : List.of(aka, node.getName());
            default:
                throw new IllegalStateException("Unhandled label mode " + this);
        }
    }
}
