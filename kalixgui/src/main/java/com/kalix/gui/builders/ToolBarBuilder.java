package com.kalix.gui.builders;

import com.kalix.gui.constants.AppConstants;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
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
            FontIcon.of(FontAwesomeSolid.FILE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.newModel()
        ));
        
        toolBar.add(createToolBarButton(
            "Open", 
            AppConstants.TOOLBAR_OPEN_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.FOLDER_OPEN, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.openModel()
        ));
        
        toolBar.add(createToolBarButton(
            "Save", 
            AppConstants.TOOLBAR_SAVE_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.SAVE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.saveModel()
        ));
        
        toolBar.addSeparator();
        
        // Model operations
        toolBar.add(createToolBarButton(
            "Run", 
            AppConstants.TOOLBAR_RUN_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.PLAY, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.runModel()
        ));
        
        toolBar.addSeparator();
        
        // Utility operations  
        toolBar.add(createToolBarButton(
            "Search", 
            AppConstants.TOOLBAR_SEARCH_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.SEARCH, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.searchModel()
        ));
        
        toolBar.add(createToolBarButton(
            "FlowViz", 
            AppConstants.TOOLBAR_FLOWVIZ_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.CHART_BAR, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.flowViz()
        ));
        
        toolBar.addSeparator();
        
        // CLI operations
        toolBar.add(createToolBarButton(
            "Version", 
            AppConstants.TOOLBAR_VERSION_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.INFO_CIRCLE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.getCliVersion()
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
    
}