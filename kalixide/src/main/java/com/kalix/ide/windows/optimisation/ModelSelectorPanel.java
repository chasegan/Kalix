package com.kalix.ide.windows.optimisation;

import com.kalix.ide.document.DocumentLabels;
import com.kalix.ide.document.ModelSource;
import com.kalix.ide.document.ModelSourceRegistry;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The "Model:" selector at the head of the Config tab — which open model this
 * optimisation targets.
 *
 * <p>Without this, the target is whichever tab happened to be in front when "New" was
 * clicked: implicit, invisible, and only changeable by going back to the main window.</p>
 *
 * <p><b>Identity:</b> the combo model holds {@link ModelSource} references, never
 * names. Labels are projected at render time by {@link DocumentLabels}, which qualifies
 * duplicate basenames with their folder — so two open {@code model.ini} files stay
 * distinguishable without the label ever becoming the identity
 * (see {@code manifestos/identity-and-labels.md} §2).</p>
 */
public class ModelSelectorPanel extends JPanel {

    /** Widest the combo may grow; long paths truncate rather than crowd out the row. */
    private static final int MAX_COMBO_WIDTH = 260;

    private final ModelSourceRegistry registry;
    private final JComboBox<ModelSource> combo;

    /** Labels for the current combo contents, positionally aligned with the model. */
    private List<String> labels = List.of();

    /** Suppresses selection callbacks while the contents are rebuilt programmatically. */
    private boolean syncing = false;

    /** Notified when the user picks a different model; never fired for programmatic changes. */
    private Consumer<ModelSource> selectionListener = source -> {};

    /**
     * Vetoes a selection before it takes effect. Returning false reverts the combo to
     * its previous value — used to confirm the session rebuild that a model change
     * forces on an already-created optimisation.
     */
    private BiPredicate<ModelSource, ModelSource> selectionGuard = (from, to) -> true;

    /** The selection before the in-flight change, so a vetoed change can be reverted. */
    private ModelSource previousSelection;

    public ModelSelectorPanel(ModelSourceRegistry registry) {
        this.registry = registry;

        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 5));

        add(new JLabel("Model:"));

        combo = new JComboBox<>();
        combo.setToolTipText("The model this optimisation runs against");
        combo.setRenderer(new ModelRenderer());
        combo.addActionListener(e -> onSelectionChanged());
        add(combo);

        refresh();
        // Re-list whenever a model is opened, closed or activated in the main window.
        registry.addChangeListener(this::refresh);
    }

    /** Registers the listener notified when the user picks a different model. */
    public void setSelectionListener(Consumer<ModelSource> listener) {
        this.selectionListener = listener != null ? listener : source -> {};
    }

    /**
     * Registers a veto called with (previous, requested) before a user-driven change is
     * accepted. Returning false reverts the combo.
     */
    public void setSelectionGuard(BiPredicate<ModelSource, ModelSource> guard) {
        this.selectionGuard = guard != null ? guard : (from, to) -> true;
    }

    /** @return the selected model, or {@code null} if no model is open */
    public ModelSource getSelectedModel() {
        return (ModelSource) combo.getSelectedItem();
    }

    /**
     * Selects the given model without notifying the selection listener. A model that is
     * no longer open is added as a trailing entry so a bound optimisation still shows
     * what it targets after its tab is closed.
     */
    public void setSelectedModel(ModelSource source) {
        syncing = true;
        try {
            if (source != null && !contains(source)) {
                rebuild(source);
            }
            combo.setSelectedItem(source);
            previousSelection = source;
        } finally {
            syncing = false;
        }
    }

    /** Enables or disables the choice (a running optimisation cannot be retargeted). */
    public void setSelectionEnabled(boolean enabled) {
        combo.setEnabled(enabled);
    }

    /** Rebuilds the list from the registry, preserving the current selection if still open. */
    public void refresh() {
        ModelSource selected = getSelectedModel();
        syncing = true;
        try {
            rebuild(selected != null && !registry.available().contains(selected) ? selected : null);
            if (selected != null && contains(selected)) {
                combo.setSelectedItem(selected);
            } else if (combo.getItemCount() > 0) {
                combo.setSelectedItem(defaultSelection());
            }
            previousSelection = getSelectedModel();
        } finally {
            syncing = false;
        }
    }

    /**
     * Re-points an <em>unbound</em> selector at the main window's active model.
     *
     * <p>Called when no optimisation is on screen and when the window is re-shown. The
     * selector is inside the Config tab, so with nothing selected it is not visible —
     * without this, "New" would silently build against whichever model was active the
     * first time the window opened, which is the very implicitness this replaced.</p>
     */
    public void resetToActive() {
        if (combo.getItemCount() == 0) {
            return;
        }
        syncing = true;
        try {
            rebuild(null);
            combo.setSelectedItem(defaultSelection());
            previousSelection = getSelectedModel();
        } finally {
            syncing = false;
        }
    }

    /** The main window's active model when it is selectable, else the first entry. */
    private ModelSource defaultSelection() {
        ModelSource active = registry.active();
        return active != null && contains(active) ? active : combo.getItemAt(0);
    }

    /**
     * Repopulates the combo with the open models, optionally retaining one closed model
     * so a bound optimisation keeps showing its (now closed) target.
     */
    private void rebuild(ModelSource retainedClosed) {
        List<ModelSource> items = new ArrayList<>(registry.available());
        if (retainedClosed != null && !items.contains(retainedClosed)) {
            items.add(retainedClosed);
        }
        labels = DocumentLabels.labelsFor(items, registry.projectRoot());
        combo.setModel(new DefaultComboBoxModel<>(items.toArray(new ModelSource[0])));
        combo.setPreferredSize(null);
        Dimension preferred = combo.getPreferredSize();
        combo.setPreferredSize(new Dimension(
                Math.min(preferred.width + 20, MAX_COMBO_WIDTH), preferred.height));
    }

    private boolean contains(ModelSource source) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i) == source) {
                return true;
            }
        }
        return false;
    }

    private void onSelectionChanged() {
        if (syncing) {
            return;
        }
        ModelSource selected = getSelectedModel();
        if (selected == previousSelection) {
            return;
        }
        if (!selectionGuard.test(previousSelection, selected)) {
            syncing = true;
            try {
                combo.setSelectedItem(previousSelection);
            } finally {
                syncing = false;
            }
            return;
        }
        previousSelection = selected;
        selectionListener.accept(selected);
    }

    /**
     * Projects each entry to its label. A model that cannot be optimised (unsaved, so
     * there is no folder for relative data paths) is shown greyed with the reason —
     * visible but plainly unusable, rather than silently missing from the list.
     */
    private class ModelRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof ModelSource source)) {
                setText(combo.getItemCount() == 0 ? "No model open" : "");
                return this;
            }

            int position = indexOf(source);
            String label = position >= 0 && position < labels.size()
                    ? labels.get(position) : source.getDisplayName();

            boolean closed = !registry.available().contains(source);
            if (closed) {
                setText(label + "  (closed)");
            } else if (!source.isOptimisable()) {
                setText(label + "  (unsaved)");
            } else {
                setText(label);
            }

            if ((closed || !source.isOptimisable()) && !isSelected) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return this;
        }

        private int indexOf(ModelSource source) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (combo.getItemAt(i) == source) {
                    return i;
                }
            }
            return -1;
        }
    }
}
