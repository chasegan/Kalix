package com.kalix.ide.renderers;

import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.RunContextMenuManager;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;
import java.awt.Component;

/**
 * Custom tree cell renderer for the run tree.
 * Provides colored text and icons based on run status and node type.
 */
public class RunTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final int TREE_ICON_SIZE = 12; // 75% of standard toolbar icon size

    // Status colors
    private static final Color COLOR_DONE = new Color(0, 120, 0);     // Dark green
    private static final Color COLOR_RUNNING = new Color(0, 0, 200);   // Blue
    private static final Color COLOR_ERROR = new Color(200, 0, 0);     // Red
    private static final Color COLOR_STARTING = new Color(150, 150, 0); // Dark yellow
    private static final Color COLOR_STOPPED = new Color(128, 128, 128); // Gray

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
            boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObject = node.getUserObject();

            if (userObject instanceof RunContextMenuManager.RunInfo) {
                renderRunInfo((RunContextMenuManager.RunInfo) userObject, sel);
            } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                renderDatasetInfo((DatasetLoaderManager.LoadedDatasetInfo) userObject, sel);
            }
        }

        return this;
    }

    /**
     * Renders a run info node with appropriate color and icon.
     */
    private void renderRunInfo(RunContextMenuManager.RunInfo runInfo, boolean selected) {
        setText(runInfo.getRunName());

        RunContextMenuManager.RunStatus runStatus = runInfo.getRunStatus();

        // Color code by run status (only when not selected)
        if (!selected) {
            setForeground(getColorForStatus(runStatus));
        }

        // Set appropriate FontAwesome icon based on run status
        setIcon(getIconForStatus(runStatus));
    }

    /**
     * Renders a dataset info node with blue color and layer icon.
     */
    private void renderDatasetInfo(DatasetLoaderManager.LoadedDatasetInfo datasetInfo, boolean selected) {
        setText(datasetInfo.fileName);

        // Color loaded datasets blue (when not selected)
        if (!selected) {
            setForeground(COLOR_RUNNING); // Using blue color
        }

        // Set layer-group icon for loaded datasets
        setIcon(FontIcon.of(FontAwesomeSolid.LAYER_GROUP, TREE_ICON_SIZE));
    }

    /**
     * Gets the color for a run status.
     */
    private Color getColorForStatus(RunContextMenuManager.RunStatus status) {
        switch (status) {
            case DONE:
                return COLOR_DONE;
            case RUNNING:
                return COLOR_RUNNING;
            case ERROR:
                return COLOR_ERROR;
            case STOPPED:
                return COLOR_STOPPED;
            default:
                return COLOR_STARTING;
        }
    }

    /**
     * Gets the icon for a run status.
     */
    private Icon getIconForStatus(RunContextMenuManager.RunStatus status) {
        switch (status) {
            case DONE:
                return FontIcon.of(FontAwesomeSolid.GRIP_HORIZONTAL, TREE_ICON_SIZE);
            case RUNNING:
                return FontIcon.of(FontAwesomeSolid.ROCKET, TREE_ICON_SIZE);
            case ERROR:
                return FontIcon.of(FontAwesomeSolid.BUG, TREE_ICON_SIZE);
            case STOPPED:
                return FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, TREE_ICON_SIZE);
            default: // STARTING, LOADING, etc.
                return FontIcon.of(FontAwesomeSolid.SUN, TREE_ICON_SIZE);
        }
    }
}