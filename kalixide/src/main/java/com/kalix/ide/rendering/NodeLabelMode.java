package com.kalix.ide.rendering;

import com.kalix.ide.model.ModelNode;
import com.kalix.ide.preferences.PreferenceKeys;

import java.util.List;

/**
 * Which text the map draws under each node. One toolbar button cycles through the
 * three modes; the rule for what a node shows, with and without the mouse over it,
 * lives in {@link #labelLines(ModelNode, boolean)} so the renderer has nothing to
 * decide. Every switch over the modes is exhaustive, so a fourth mode is a compile
 * error wherever it is not yet handled (ADR-0008 §2).
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
    static NodeLabelMode fromKey(String key) {
        for (NodeLabelMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * The saved mode. A preference file from before the three-way key has only the
     * on/off toggle; it is read once here and rewritten as the new key, the way
     * {@code ThemePreferences} migrates theme names.
     */
    public static NodeLabelMode saved() {
        NodeLabelMode mode = fromKey(PreferenceKeys.MAP_LABEL_MODE.get());
        if (mode == null) {
            mode = PreferenceKeys.MAP_SHOW_LABELS.get() ? NAMES : HIDDEN;
            mode.save();
        }
        return mode;
    }

    /** Persists this mode as the saved one. */
    public void save() {
        PreferenceKeys.MAP_LABEL_MODE.set(key);
    }

    /** The next mode in the cycle: names, aka, hidden, names. */
    public NodeLabelMode next() {
        return switch (this) {
            case NAMES -> AKA;
            case AKA -> HIDDEN;
            case HIDDEN -> NAMES;
        };
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
        String name = node.getName();
        String aka = node.getAka();
        return switch (this) {
            case NAMES -> List.of(name);
            case AKA -> {
                if (hovered) {
                    yield List.of(name);
                }
                yield aka == null ? List.of() : List.of(aka);
            }
            case HIDDEN -> {
                if (!hovered) {
                    yield List.of();
                }
                yield aka == null ? List.of(name) : List.of(aka, name);
            }
        };
    }
}
