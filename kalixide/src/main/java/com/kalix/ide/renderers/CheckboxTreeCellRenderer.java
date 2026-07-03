package com.kalix.ide.renderers;

import com.kalix.ide.components.JCheckboxTree;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

public class CheckboxTreeCellRenderer extends JPanel implements TreeCellRenderer {
    private final JCheckBox checkbox;
    private final DefaultTreeCellRenderer label;

    public CheckboxTreeCellRenderer() {
        setLayout(new BorderLayout());
        checkbox = new JCheckBox();
        // Make checkbox display-only (tree handles interaction logic)
        checkbox.setFocusable(false);
        checkbox.setRequestFocusEnabled(false);

        label = new DefaultTreeCellRenderer();
        add(checkbox, BorderLayout.WEST);
        add(label, BorderLayout.CENTER);
        setOpaque(false);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                  boolean selected, boolean expanded,
                                                  boolean leaf, int row, boolean hasFocus) {
        // Update the label component with default rendering
        label.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (tree instanceof JCheckboxTree checkboxTree) {
            TreePath path = tree.getPathForRow(row);
            JCheckboxTree.CheckState state = path != null
                    ? checkboxTree.getCheckState(path)
                    : JCheckboxTree.CheckState.UNCHECKED;
            applyCheckState(state);
        } else {
            applyCheckState(JCheckboxTree.CheckState.UNCHECKED);
        }
        return this;
    }

    /**
     * Renders the checkbox for a tri-state {@link JCheckboxTree.CheckState}. PARTIAL uses
     * the button-model pressed+armed trick (no native tri-state JCheckBox exists in Swing):
     * https://stackoverflow.com/a/11067422 - Posted by Daniel, modified by community,
     * retrieved 2026-07-07, license CC BY-SA 3.0.
     *
     * <p>{@code checkbox} is a single instance reused for every row, so a PARTIAL render
     * leaves its model armed+pressed behind for whichever row renders next.
     * {@code DefaultButtonModel.setPressed(false)} treats a pressed-to-unpressed transition
     * while still armed as a mouse-release and toggles {@code selected} - exactly like a
     * real click - which would silently flip the *next* rendered row's checkbox back on
     * after we just set it unselected. Dropping {@code armed} before {@code pressed} on
     * every reset means that toggle's guard ({@code !pressed && armed}) can never be true
     * during cleanup.</p>
     */
    private void applyCheckState(JCheckboxTree.CheckState state) {
        var model = checkbox.getModel();
        model.setArmed(false);
        model.setPressed(false);
        switch (state) {
            case CHECKED -> checkbox.setSelected(true);
            case PARTIAL -> {
                checkbox.setSelected(false);
                model.setArmed(true);
                model.setPressed(true);
            }
            case UNCHECKED -> checkbox.setSelected(false);
            default -> throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    // Delegation methods for subclasses to customize the label appearance
    // These mirror DefaultTreeCellRenderer methods for API compatibility

    /**
     * Sets the text of the label component.
     * Delegates to the internal label renderer.
     */
    public void setText(String text) {
        label.setText(text);
    }

    /**
     * Sets the icon of the label component.
     * Delegates to the internal label renderer.
     */
    public void setIcon(Icon icon) {
        label.setIcon(icon);
    }

    /**
     * Sets the foreground color of the label component.
     * Delegates to the internal label renderer.
     */
    @Override
    public void setForeground(Color color) {
        // Must check for null because this can be called during construction
        // before the label field is initialized
        if (label != null) {
            label.setForeground(color);
        }
    }
}
