package com.kalix.ide.renderers;

import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationStatus;
import com.kalix.ide.models.optimisation.OptimisationResult;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * Custom tree cell renderer for optimisation tree nodes.
 * Provides status-based colors and icons for better visualization.
 */
public class OptimisationTreeCellRenderer extends DefaultTreeCellRenderer {

    private static final int ICON_SIZE = 16;

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObject = node.getUserObject();

            if (userObject instanceof OptimisationInfo) {
                renderOptimisationInfo((OptimisationInfo) userObject, selected);
            }
        }

        return this;
    }

    /**
     * Renders an optimisation info node with appropriate color and icon.
     */
    private void renderOptimisationInfo(OptimisationInfo info, boolean selected) {
        OptimisationStatus status = info.getStatus();

        // Build display text
        String displayText = info.getName();
        if (info.getResult() != null) {
            OptimisationResult result = info.getResult();

            // Add objective value if available
            if (result.getBestObjective() != null) {
                displayText = String.format("%s (obj: %.6f)", info.getName(), result.getBestObjective());
            }

            // Or show progress if still running
            else if (result.getCurrentProgress() != null) {
                displayText = String.format("%s (%d%%)", info.getName(), result.getCurrentProgress());
            }
        } else if (status == OptimisationStatus.RUNNING) {
            // Show running indicator if no progress available
            displayText = info.getName() + " (running...)";
        }

        setText(displayText);

        // Apply color and icon based on status (when not selected)
        if (!selected) {
            setForeground(status.getStatusColor());
        }

        setIcon(getIconForStatus(status));
    }

    /**
     * Gets the appropriate icon for an optimisation status.
     *
     * @param status The optimisation status
     * @return The icon to display
     */
    private Icon getIconForStatus(OptimisationStatus status) {
        switch (status) {
            case DONE:
                return FontIcon.of(FontAwesomeSolid.CHECK_CIRCLE, ICON_SIZE,
                    new Color(0, 120, 0));
            case RUNNING:
                return FontIcon.of(FontAwesomeSolid.COG, ICON_SIZE,
                    new Color(0, 0, 200));
            case ERROR:
                return FontIcon.of(FontAwesomeSolid.EXCLAMATION_TRIANGLE, ICON_SIZE,
                    new Color(200, 0, 0));
            case STARTING:
            case LOADING:
                return FontIcon.of(FontAwesomeSolid.HOURGLASS_HALF, ICON_SIZE,
                    new Color(150, 150, 0));
            case STOPPED:
                return FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, ICON_SIZE,
                    Color.GRAY);
            case CONFIGURING:
                return FontIcon.of(FontAwesomeSolid.EDIT, ICON_SIZE,
                    new Color(100, 100, 100));
            default:
                return null;
        }
    }
}