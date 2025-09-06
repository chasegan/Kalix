package com.kalix.gui.builders;

import com.kalix.gui.constants.AppConstants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Builder class for creating and configuring the application toolbar.
 * Creates a toolbar with commonly used actions and appropriate icons.
 */
public class ToolBarBuilder {
    
    private final MenuBarBuilder.MenuBarCallbacks callbacks;
    
    /**
     * Creates a new ToolBarBuilder instance.
     * 
     * @param callbacks The callback interface for toolbar actions
     */
    public ToolBarBuilder(MenuBarBuilder.MenuBarCallbacks callbacks) {
        this.callbacks = callbacks;
    }
    
    /**
     * Builds and returns the configured toolbar.
     * 
     * @return The configured JToolBar
     */
    public JToolBar buildToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setRollover(true);
        
        // File operations
        toolBar.add(createToolBarButton(
            "New", 
            AppConstants.TOOLBAR_NEW_TOOLTIP,
            createNewIcon(),
            e -> callbacks.newModel()
        ));
        
        toolBar.add(createToolBarButton(
            "Open", 
            AppConstants.TOOLBAR_OPEN_TOOLTIP,
            createOpenIcon(),
            e -> callbacks.openModel()
        ));
        
        toolBar.add(createToolBarButton(
            "Save", 
            AppConstants.TOOLBAR_SAVE_TOOLTIP,
            createSaveIcon(),
            e -> callbacks.saveModel()
        ));
        
        toolBar.addSeparator();
        
        // Model operations
        toolBar.add(createToolBarButton(
            "Run", 
            AppConstants.TOOLBAR_RUN_TOOLTIP,
            createRunIcon(),
            e -> callbacks.runModel()
        ));
        
        toolBar.addSeparator();
        
        // Utility operations  
        toolBar.add(createToolBarButton(
            "Search", 
            AppConstants.TOOLBAR_SEARCH_TOOLTIP,
            createSearchIcon(),
            e -> callbacks.searchModel()
        ));
        
        toolBar.add(createToolBarButton(
            "FlowViz", 
            AppConstants.TOOLBAR_FLOWVIZ_TOOLTIP,
            createFlowVizIcon(),
            e -> callbacks.flowViz()
        ));
        
        return toolBar;
    }
    
    /**
     * Creates a toolbar button with icon and tooltip.
     * 
     * @param text Button text (used for accessibility)
     * @param tooltip Tooltip text
     * @param icon Button icon
     * @param listener Action listener
     * @return Configured JButton
     */
    private JButton createToolBarButton(String text, String tooltip, Icon icon, ActionListener listener) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.addActionListener(listener);
        
        // Set accessible name for screen readers
        button.getAccessibleContext().setAccessibleName(text);
        
        return button;
    }
    
    // Icon creation methods - using simple geometric shapes for now
    // These can be replaced with proper icon files later
    
    private Icon createNewIcon() {
        return createSimpleIcon(Color.GREEN, "N");
    }
    
    private Icon createOpenIcon() {
        return createSimpleIcon(Color.BLUE, "O");
    }
    
    private Icon createSaveIcon() {
        return createSimpleIcon(Color.ORANGE, "S");
    }
    
    private Icon createRunIcon() {
        return createSimpleIcon(Color.RED, "▶");
    }
    
    private Icon createSearchIcon() {
        return createSimpleIcon(Color.GRAY, "🔍");
    }
    
    private Icon createFlowVizIcon() {
        return createSimpleIcon(Color.CYAN, "📊");
    }
    
    /**
     * Creates a simple colored icon with text for toolbar buttons.
     * This is a placeholder implementation - replace with actual icons later.
     * 
     * @param color Background color
     * @param text Text to display on icon
     * @return Simple icon
     */
    private Icon createSimpleIcon(Color color, String text) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Draw colored circle background
                g2d.setColor(color);
                g2d.fillOval(x, y, getIconWidth(), getIconHeight());
                
                // Draw border
                g2d.setColor(Color.DARK_GRAY);
                g2d.drawOval(x, y, getIconWidth() - 1, getIconHeight() - 1);
                
                // Draw text
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("SansSerif", Font.BOLD, 10));
                FontMetrics fm = g2d.getFontMetrics();
                int textWidth = fm.stringWidth(text);
                int textHeight = fm.getAscent();
                int textX = x + (getIconWidth() - textWidth) / 2;
                int textY = y + (getIconHeight() + textHeight) / 2 - 2;
                g2d.drawString(text, textX, textY);
                
                g2d.dispose();
            }
            
            @Override
            public int getIconWidth() {
                return AppConstants.TOOLBAR_ICON_SIZE;
            }
            
            @Override
            public int getIconHeight() {
                return AppConstants.TOOLBAR_ICON_SIZE;
            }
        };
    }
}