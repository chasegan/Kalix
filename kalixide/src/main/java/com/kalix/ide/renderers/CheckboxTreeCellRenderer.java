package com.kalix.ide.renderers;

import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.components.JTristateCheckBox;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

public class CheckboxTreeCellRenderer extends JPanel
        implements TreeCellRenderer, JCheckboxTree.CheckboxRowRenderer {
    private final JTristateCheckBox checkbox;
    private final DefaultTreeCellRenderer label;

    /**
     * Width of the checkbox glyph region at the leading edge of the row. The tree uses
     * this to hit-test clicks: only clicks inside this strip toggle the checkbox.
     */
    @Override
    public int getCheckboxWidth() {
        return checkbox.getPreferredSize().width;
    }

    public CheckboxTreeCellRenderer() {
        setLayout(new BorderLayout());
        checkbox = new JTristateCheckBox();
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
     * Renders the checkbox for a tri-state {@link JCheckboxTree.CheckState}, delegating the
     * PARTIAL indicator to {@link JTristateCheckBox#setPartial}. An earlier version of this
     * faked PARTIAL via the classic checkbox {@code ButtonModel} pressed+armed trick, but
     * that turned out to be fragile on a checkbox instance reused across every row: resetting
     * {@code pressed} before {@code armed} silently toggled {@code selected} back on for
     * whichever row rendered next, via {@code DefaultButtonModel}'s click-simulation side
     * effect. Painting the indicator directly (see {@link JTristateCheckBox}) sidesteps that
     * whole class of bug by never touching button-model state.
     */
    private void applyCheckState(JCheckboxTree.CheckState state) {
        switch (state) {
            case CHECKED -> {
                checkbox.setSelected(true);
                checkbox.setPartial(false);
            }
            case PARTIAL -> {
                checkbox.setSelected(false);
                checkbox.setPartial(true);
            }
            case UNCHECKED -> {
                checkbox.setSelected(false);
                checkbox.setPartial(false);
            }
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
