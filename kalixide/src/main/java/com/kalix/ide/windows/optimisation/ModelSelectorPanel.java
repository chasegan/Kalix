package com.kalix.ide.windows.optimisation;

import com.kalix.ide.document.DocumentLabels;
import com.kalix.ide.document.OpenModel;
import com.kalix.ide.document.WorkspaceView;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * The "Model:" selector at the head of the Config tab — which open model this
 * optimisation targets.
 *
 * <p>Without this, the target is whichever tab happened to be in front when "New" was
 * clicked: implicit, invisible, and only changeable by going back to the main window.</p>
 *
 * <p><b>Identity:</b> the combo model holds {@link OpenModel} references, never
 * names. Labels are projected at render time by {@link DocumentLabels}, which qualifies
 * duplicate basenames with their folder — so two open {@code model.ini} files stay
 * distinguishable without the label ever becoming the identity
 * (see {@code manifestos/identity-and-labels.md} §2).</p>
 */
public class ModelSelectorPanel extends JPanel {

    /**
     * Width of the closed combo. Fixed rather than content-derived: a disambiguated
     * label like {@code alpha/run/model.ini} is far longer than a bare file name, and
     * letting the box size to it would make the whole top strip jump about as models
     * open and close. The popup is widened separately so the full names stay readable
     * where it actually matters — while choosing.
     */
    private static final int COMBO_WIDTH = 190;

    /** Slack added to the popup so the widest entry is not painted flush to the edge. */
    private static final int POPUP_PADDING = 40;

    private final WorkspaceView workspace;
    private final WidePopupComboBox combo;

    /** Labels for the current combo contents, positionally aligned with the model. */
    private List<String> labels = List.of();

    /** Suppresses selection callbacks while the contents are rebuilt programmatically. */
    private boolean syncing = false;

    /** Notified when the user picks a different model; never fired for programmatic changes. */
    private Consumer<OpenModel> selectionListener = source -> {};

    /**
     * Vetoes a selection before it takes effect. Returning false reverts the combo to
     * its previous value — used to confirm the session rebuild that a model change
     * forces on an already-created optimisation.
     */
    private BiPredicate<OpenModel, OpenModel> selectionGuard = (from, to) -> true;

    /** The selection before the in-flight change, so a vetoed change can be reverted. */
    private OpenModel previousSelection;

    public ModelSelectorPanel(WorkspaceView workspace) {
        this.workspace = workspace;

        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 5));

        add(new JLabel("Model:"));

        combo = new WidePopupComboBox();
        combo.setRenderer(new ModelRenderer());
        combo.addActionListener(e -> onSelectionChanged());
        // Widen the drop-down list to its content each time it opens. Done here rather
        // than through a look-and-feel property because the IDE swaps themes at runtime,
        // so anything FlatLaf-specific would silently stop working under another LAF.
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                combo.sizePopupOnce();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        add(combo);

        refresh();
        // Re-list whenever a model is opened, closed or activated in the main window.
        workspace.addChangeListener(this::refresh);
    }

    /** Registers the listener notified when the user picks a different model. */
    public void setSelectionListener(Consumer<OpenModel> listener) {
        this.selectionListener = listener != null ? listener : source -> {};
    }

    /**
     * Registers a veto called with (previous, requested) before a user-driven change is
     * accepted. Returning false reverts the combo.
     */
    public void setSelectionGuard(BiPredicate<OpenModel, OpenModel> guard) {
        this.selectionGuard = guard != null ? guard : (from, to) -> true;
    }

    /** @return the selected model, or {@code null} if no model is open */
    public OpenModel getSelectedModel() {
        return (OpenModel) combo.getSelectedItem();
    }

    /**
     * Selects the given model without notifying the selection listener. A model that is
     * no longer open is added as a trailing entry so a bound optimisation still shows
     * what it targets after its tab is closed.
     */
    public void setSelectedModel(OpenModel source) {
        syncing = true;
        try {
            if (source != null && !contains(source)) {
                rebuild(source);
            }
            combo.setSelectedItem(source);
            previousSelection = source;
            updateTooltip();
        } finally {
            syncing = false;
        }
    }

    /** Enables or disables the choice (a running optimisation cannot be retargeted). */
    public void setSelectionEnabled(boolean enabled) {
        combo.setEnabled(enabled);
    }

    /** Rebuilds the list from the workspace, preserving the current selection if still open. */
    public void refresh() {
        OpenModel selected = getSelectedModel();
        syncing = true;
        try {
            rebuild(selected != null && !workspace.openModels().contains(selected) ? selected : null);
            if (selected != null && contains(selected)) {
                combo.setSelectedItem(selected);
            } else if (combo.getItemCount() > 0) {
                combo.setSelectedItem(defaultSelection());
            }
            previousSelection = getSelectedModel();
            updateTooltip();
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
            updateTooltip();
        } finally {
            syncing = false;
        }
    }

    /** The main window's active model when it is selectable, else the first entry. */
    private OpenModel defaultSelection() {
        OpenModel active = workspace.activeModel();
        return active != null && contains(active) ? active : combo.getItemAt(0);
    }

    /**
     * Repopulates the combo with the open models, optionally retaining one closed model
     * so a bound optimisation keeps showing its (now closed) target.
     */
    private void rebuild(OpenModel retainedClosed) {
        List<OpenModel> items = new ArrayList<>(workspace.openModels());
        if (retainedClosed != null && !items.contains(retainedClosed)) {
            items.add(retainedClosed);
        }
        labels = DocumentLabels.labelsFor(items, workspace.projectRoot());
        combo.setModel(new DefaultComboBoxModel<>(items.toArray(new OpenModel[0])));

        // Pin the closed width so the strip's layout never shifts with the contents.
        combo.setPreferredSize(null);
        int height = combo.getPreferredSize().height;
        Dimension fixed = new Dimension(COMBO_WIDTH, height);
        combo.setPreferredSize(fixed);
        combo.setMinimumSize(fixed);
        combo.setMaximumSize(fixed);
    }

    /**
     * The width the drop-down needs to show every label in full.
     *
     * <p>Never narrower than the box it hangs from.</p>
     */
    private int desiredPopupWidth() {
        FontMetrics metrics = combo.getFontMetrics(combo.getFont());
        int widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, metrics.stringWidth(label));
        }
        return Math.max(combo.getWidth(), widest + POPUP_PADDING);
    }

    /**
     * A combo whose drop-down may be wider than the box.
     *
     * <p>{@code BasicComboPopup} derives the popup's width from
     * {@link JComboBox#getSize()} and then <em>overwrites</em> whatever preferred size
     * the scroll pane was given — so widening the popup directly does not survive.
     * Reporting a wider size for the duration of that one calculation is the way in that
     * does not depend on the look and feel, which matters here because the IDE swaps
     * themes at runtime.</p>
     */
    private class WidePopupComboBox extends JComboBox<OpenModel> {

        /** True only while the popup's bounds are being computed. */
        private boolean sizingPopup = false;

        @Override
        public Dimension getSize() {
            Dimension size = super.getSize();
            if (sizingPopup) {
                size.width = Math.max(size.width, desiredPopupWidth());
            }
            return size;
        }

        /**
         * Reports the popup width for the synchronous {@code show()} that follows, then
         * stands down again — the combo must report its true size for its own painting.
         */
        void sizePopupOnce() {
            sizingPopup = true;
            SwingUtilities.invokeLater(() -> sizingPopup = false);
        }
    }

    /**
     * Shortens {@code text} from the <em>left</em> until it fits, e.g.
     * {@code …/run/model.ini}.
     *
     * <p>Trimming the right — Swing's default — would eat the file name and leave
     * {@code alpha/run/mod…}, hiding the very part that identifies the model. The
     * qualifying folders are the disposable half.</p>
     *
     * @param widthOf measures rendered width; injected so the logic is testable without
     *                a font
     */
    static String elideHead(String text, ToIntFunction<String> widthOf, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0 || widthOf.applyAsInt(text) <= maxWidth) {
            return text;
        }
        int ellipsis = widthOf.applyAsInt("…");
        for (int start = 1; start < text.length(); start++) {
            if (ellipsis + widthOf.applyAsInt(text.substring(start)) <= maxWidth) {
                return "…" + text.substring(start);
            }
        }
        // Not even the last character fits alongside the ellipsis; show what we can.
        return "…";
    }

    private boolean contains(OpenModel source) {
        return indexOf(source) >= 0;
    }

    /** Position of {@code source} in the combo by identity, or -1. */
    private int indexOf(OpenModel source) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i) == source) {
                return i;
            }
        }
        return -1;
    }

    /** The label for a model as it currently reads in this combo. */
    private String labelOf(OpenModel source) {
        int position = indexOf(source);
        return position >= 0 && position < labels.size()
                ? labels.get(position)
                : (source != null ? source.getDisplayName() : "");
    }

    /**
     * Puts the selection's full label on the combo's tooltip. The renderer's own tooltip
     * covers the drop-down rows, but Swing uses the renderer as an unattached rubber
     * stamp for the closed box — so the box needs its own, and it is the one that elides.
     */
    private void updateTooltip() {
        OpenModel selected = getSelectedModel();
        combo.setToolTipText(selected != null
                ? labelOf(selected)
                : "The model this optimisation runs against");
    }

    private void onSelectionChanged() {
        if (syncing) {
            return;
        }
        OpenModel selected = getSelectedModel();
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
        updateTooltip();
        selectionListener.accept(selected);
    }

    /**
     * Projects each entry to its label. A model that cannot be optimised (unsaved, so
     * there is no folder for relative data paths) is shown greyed with the reason —
     * visible but plainly unusable, rather than silently missing from the list.
     *
     * <p>The same renderer draws both the drop-down rows and the closed box; Swing
     * signals the latter with {@code index == -1}. Only the closed box elides, because
     * only it is width-constrained.</p>
     */
    private class ModelRenderer extends DefaultListCellRenderer {

        /** Whether this render pass is the width-constrained closed box. */
        private boolean elideToFit = false;

        private void setElideToFit(boolean elide) {
            this.elideToFit = elide;
        }

        /**
         * Paints the label shortened from the left when it does not fit, e.g.
         * {@code …/run/model.ini}.
         *
         * <p>Done at paint time, and <em>only</em> at paint time, for two reasons. The
         * width is exact here — the combo's UI stamps the renderer with the value area's
         * bounds immediately before painting — whereas estimating it by subtracting a
         * guessed arrow-button width over-shot, so the text was elided to a width it did
         * not have and Swing clipped it again, ellipsing both ends.</p>
         *
         * <p>And measurement must see the <em>full</em> label:
         * {@code BasicComboBoxUI.getDisplaySize()} sizes the drop-down by rendering every
         * item with {@code index == -1} — the closed-box path — so eliding anywhere that
         * feeds preferred size would shrink the popup to the width of the truncation.</p>
         *
         * <p>Swapping the text around {@code super} is safe because
         * {@link DefaultListCellRenderer} makes {@code revalidate} and {@code repaint}
         * no-ops.</p>
         */
        @Override
        protected void paintComponent(Graphics g) {
            String full = getText();
            if (elideToFit && full != null && getWidth() > 0) {
                Insets insets = getInsets();
                int available = getWidth() - insets.left - insets.right;
                String fitted = elideHead(full, getFontMetrics(getFont())::stringWidth, available);
                if (!fitted.equals(full)) {
                    setText(fitted);
                    super.paintComponent(g);
                    setText(full);
                    return;
                }
            }
            super.paintComponent(g);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof OpenModel source)) {
                setText(combo.getItemCount() == 0 ? "No model open" : "");
                setToolTipText(null);
                return this;
            }

            int position = indexOf(source);
            String label = position >= 0 && position < labels.size()
                    ? labels.get(position) : source.getDisplayName();

            boolean closed = !workspace.openModels().contains(source);
            if (closed) {
                label = label + "  (closed)";
            } else if (!source.isOptimisable()) {
                label = label + "  (unsaved)";
            }

            // Elide only in the closed box (index -1); the drop-down rows size to fit.
            setElideToFit(index < 0);
            setText(label);
            // The full label is always reachable on hover, elided or not.
            setToolTipText(label);

            if ((closed || !source.isOptimisable()) && !isSelected) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            return this;
        }
    }
}
