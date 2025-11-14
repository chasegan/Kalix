package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * Creates and manages a visual drag preview window that follows the mouse cursor
 * during drag operations. The preview shows a translucent representation of the
 * panel being dragged.
 */
public class DragPreview {
    private JWindow previewWindow;
    private final DockablePanel originalPanel;
    private final Point mouseOffset; // Offset from panel's top-left to where mouse grabbed

    /**
     * Creates a new drag preview for the specified panel.
     *
     * @param panel The panel being dragged
     */
    public DragPreview(DockablePanel panel) {
        this.originalPanel = panel;
        // Calculate offset - grip is at (GRIP_MARGIN, GRIP_MARGIN) and is GRIP_WIDTH x GRIP_HEIGHT,
        // so center of grip is at (GRIP_MARGIN + GRIP_WIDTH/2, GRIP_MARGIN + GRIP_HEIGHT/2)
        this.mouseOffset = new Point(
            Dimensions.GRIP_MARGIN + Dimensions.GRIP_WIDTH / 2,
            Dimensions.GRIP_MARGIN + Dimensions.GRIP_HEIGHT / 2
        );
        createPreviewWindow();
    }

    /**
     * Creates the translucent preview window with visual representation of the panel.
     */
    private void createPreviewWindow() {
        previewWindow = new JWindow();
        previewWindow.setAlwaysOnTop(true);

        // Make the entire window translucent
        previewWindow.setOpacity(Timing.DRAG_PREVIEW_OPACITY);

        // Create a visual representation of the panel
        JPanel previewPanel = createPreviewPanel();
        previewWindow.add(previewPanel);
        previewWindow.pack();
    }

    /**
     * Creates the visual panel that represents the dragged content.
     *
     * @return JPanel configured as preview representation
     */
    private JPanel createPreviewPanel() {
        JPanel previewPanel = new JPanel();
        previewPanel.setBackground(Colors.DRAG_PREVIEW);
        previewPanel.setBorder(BorderFactory.createLineBorder(
            Colors.DRAG_PREVIEW_BORDER, Dimensions.DRAG_PREVIEW_BORDER_WIDTH));
        previewPanel.setPreferredSize(originalPanel.getSize());

        // Add a label showing what's being dragged
        JLabel label = new JLabel(Text.DRAG_PREVIEW_LABEL, SwingConstants.CENTER);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        previewPanel.add(label);

        return previewPanel;
    }

    /**
     * Shows the preview window at the specified screen location.
     *
     * @param screenLocation The screen coordinates where the preview should appear
     */
    public void show(Point screenLocation) {
        updatePosition(screenLocation);
        previewWindow.setVisible(true);
    }

    /**
     * Updates the preview window position to follow the mouse cursor.
     * Maintains the relative offset where the user initially grabbed the grip.
     *
     * @param screenLocation The current screen coordinates of the mouse
     */
    public void updatePosition(Point screenLocation) {
        if (previewWindow != null) {
            // Position the preview so the mouse cursor is at the same relative position
            // where the user grabbed the grip (maintaining the "hold" feeling)
            int x = screenLocation.x - mouseOffset.x;
            int y = screenLocation.y - mouseOffset.y;
            previewWindow.setLocation(x, y);
        }
    }

    /**
     * Hides and disposes of the preview window, cleaning up resources.
     */
    public void dispose() {
        if (previewWindow != null) {
            previewWindow.dispose();
            previewWindow = null;
        }
    }
}