package com.kalix.ide.renderers;

import com.kalix.ide.managers.OutputsTreeBuilder;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Component;

/**
 * Custom tree cell renderer for the timeseries/outputs tree.
 * Provides themed icons based on variable names for better visual identification.
 */
public class OutputsTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final int TREE_ICON_SIZE = 12; // 75% of standard toolbar icon size

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
            boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            String variableName = node.getUserObject().toString();

            // Only add icons to leaf nodes (exclude special message nodes)
            if (leaf && !OutputsTreeBuilder.isSpecialMessageNode(node)) {
                setIcon(getIconForVariable(variableName));
            } else {
                // No icon for non-leaf nodes
                setIcon(null);
            }
        }

        return this;
    }

    /**
     * Determines the appropriate icon based on the variable name.
     *
     * @param variableName The name of the timeseries variable
     * @return The FontAwesome icon appropriate for the variable type
     */
    private Icon getIconForVariable(String variableName) {
        // Water flow variables
        if (variableName.equals("dsflow") || variableName.equals("usflow") || variableName.matches("ds_\\d+")) {
            return FontIcon.of(FontAwesomeSolid.WATER, TREE_ICON_SIZE);
        }
        // Storage/volume variables
        else if (variableName.equals("storage") || variableName.equals("volume")) {
            return FontIcon.of(FontAwesomeSolid.GLASS_WHISKEY, TREE_ICON_SIZE);
        }
        // Runoff depth
        else if (variableName.equals("runoff_depth")) {
            return FontIcon.of(FontAwesomeSolid.TINT, TREE_ICON_SIZE);
        }
        // Demand
        else if (variableName.equals("demand")) {
            return FontIcon.of(FontAwesomeSolid.ARROW_CIRCLE_LEFT, TREE_ICON_SIZE);
        }
        // Diversion
        else if (variableName.equals("diversion")) {
            return FontIcon.of(FontAwesomeSolid.ARROW_ALT_CIRCLE_LEFT, TREE_ICON_SIZE);
        }
        // Inflow and runoff volume
        else if (variableName.equals("inflow") || variableName.equals("runoff_volume")) {
            return FontIcon.of(FontAwesomeSolid.ARROW_ALT_CIRCLE_RIGHT, TREE_ICON_SIZE);
        }
        // Default icon for any other leaf nodes
        else {
            return FontIcon.of(FontAwesomeSolid.WAVE_SQUARE, TREE_ICON_SIZE);
        }
    }
}