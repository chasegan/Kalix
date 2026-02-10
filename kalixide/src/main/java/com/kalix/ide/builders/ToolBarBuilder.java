package com.kalix.ide.builders;

import com.kalix.ide.constants.AppConstants;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

/**
 * Builder class for creating and configuring the application toolbar.
 * Creates a toolbar with commonly used actions and appropriate icons.
 */
public class ToolBarBuilder {

    /**
     * Container for toolbar and its toggle buttons.
     * Allows easy access to toggle buttons for state synchronization.
     */
    public static class ToolBarComponents {
        public final JToolBar toolBar;
        public final JToggleButton lintingToggleButton;
        public final JToggleButton autoReloadToggleButton;
        public final JToggleButton gridlinesToggleButton;
        public final JButton backButton;
        public final JButton forwardButton;

        public ToolBarComponents(JToolBar toolBar, JToggleButton lintingToggleButton,
                                 JToggleButton autoReloadToggleButton, JToggleButton gridlinesToggleButton,
                                 JButton backButton, JButton forwardButton) {
            this.toolBar = toolBar;
            this.lintingToggleButton = lintingToggleButton;
            this.autoReloadToggleButton = autoReloadToggleButton;
            this.gridlinesToggleButton = gridlinesToggleButton;
            this.backButton = backButton;
            this.forwardButton = forwardButton;
        }
    }

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
     * Builds and returns the configured toolbar with references to toggle buttons.
     *
     * @return ToolBarComponents containing the toolbar and toggle button references
     */
    public ToolBarComponents buildToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setRollover(true);

        // Branding button - far left
        toolBar.add(createBrandingButton());
        toolBar.addSeparator();

        // File operations
        toolBar.add(createToolBarButton(
            "New",
            AppConstants.getToolbarNewTooltip(),
            FontIcon.of(FontAwesomeSolid.FILE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.newModel()
        ));

        toolBar.add(createToolBarButton(
            "Open",
            AppConstants.getToolbarOpenTooltip(),
            FontIcon.of(FontAwesomeSolid.FOLDER_OPEN, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.openModel()
        ));

        toolBar.add(createToolBarButton(
            "Save",
            AppConstants.getToolbarSaveTooltip(),
            FontIcon.of(FontAwesomeSolid.SAVE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.saveModel()
        ));

        toolBar.addSeparator();

        // Navigation buttons
        JButton backButton = createToolBarButton(
            "Back",
            AppConstants.getToolbarBackTooltip(),
            FontIcon.of(FontAwesomeSolid.ARROW_LEFT, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.navigateBack()
        );
        backButton.setEnabled(callbacks.canNavigateBack());
        toolBar.add(backButton);

        JButton forwardButton = createToolBarButton(
            "Forward",
            AppConstants.getToolbarForwardTooltip(),
            FontIcon.of(FontAwesomeSolid.ARROW_RIGHT, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.navigateForward()
        );
        forwardButton.setEnabled(callbacks.canNavigateForward());
        toolBar.add(forwardButton);

        // Utility operations
        toolBar.add(createToolBarButton(
            "Find",
            AppConstants.getToolbarSearchTooltip(),
            FontIcon.of(FontAwesomeSolid.SEARCH, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.searchModel()
        ));

        toolBar.add(createToolBarButton(
            "Find on Map",
            AppConstants.getToolbarFindOnMapTooltip(),
            FontIcon.of(FontAwesomeSolid.SEARCH_LOCATION, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.findNodeOnMap()
        ));

        toolBar.add(createToolBarButton(
            "Zoom to Fit",
            "Zoom to Fit",
            FontIcon.of(FontAwesomeSolid.EXPAND, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.zoomToFit()
        ));

        toolBar.addSeparator();

        // Model operations
        toolBar.add(createToolBarButton(
            "Run Model",
            AppConstants.getToolbarRunModelTooltip(),
            FontIcon.of(FontAwesomeSolid.PLAY, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.runModelFromMemory()
        ));

        toolBar.add(createToolBarButton(
            "Run Manager",
            AppConstants.TOOLBAR_SESSIONS_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.SERVER, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.showRunManager()
        ));

        toolBar.add(createToolBarButton(
            "Optimiser",
            AppConstants.TOOLBAR_OPTIMISER_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.DICE_D20, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.showOptimisation()
        ));

        toolBar.add(createToolBarButton(
            "FlowViz",
            AppConstants.TOOLBAR_FLOWVIZ_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.CHART_LINE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.flowViz()
        ));

        toolBar.addSeparator();

        // Create and store toggle buttons
        JToggleButton lintingButton = createLintingToggleButton();
        JToggleButton autoReloadButton = createAutoReloadToggleButton();
        JToggleButton gridlinesButton = createGridToggleButton();

        toolBar.add(lintingButton);
        toolBar.add(autoReloadButton);
        toolBar.add(gridlinesButton);

        return new ToolBarComponents(toolBar, lintingButton, autoReloadButton, gridlinesButton,
            backButton, forwardButton);
    }
    
    /**
     * Creates the branding button with the Kalix logo.
     * 
     * @return Configured branding JButton
     */
    private JButton createBrandingButton() {
        ImageIcon logoIcon = loadScaledLogo();
        JButton brandingButton = new JButton(logoIcon);
        brandingButton.setToolTipText(AppConstants.APP_WEBSITE_URL);
        brandingButton.setFocusPainted(false);
        brandingButton.addActionListener(e -> callbacks.openWebsite());

        // Set accessible name for screen readers
        brandingButton.getAccessibleContext().setAccessibleName("Kalix Logo");

        return brandingButton;
    }
    
    /**
     * Loads and scales the Kalix logo for toolbar use, choosing the appropriate version based on theme.
     * 
     * @return Scaled ImageIcon or a fallback icon if loading fails
     */
    private ImageIcon loadScaledLogo() {
        try {
            // Choose logo path based on current theme
            String logoPath = getThemeAwareLogoPath();
            java.net.URL logoUrl = getClass().getResource(logoPath);
            
            if (logoUrl != null) {
                ImageIcon originalIcon = new ImageIcon(logoUrl);
                Image originalImage = originalIcon.getImage();
                
                // Calculate scaled dimensions maintaining aspect ratio
                int originalWidth = originalImage.getWidth(null);
                int originalHeight = originalImage.getHeight(null);
                int targetHeight = AppConstants.TOOLBAR_LOGO_HEIGHT;
                int targetWidth = (originalWidth * targetHeight) / originalHeight;
                
                // Scale with high quality
                Image scaledImage = originalImage.getScaledInstance(
                    targetWidth, targetHeight, Image.SCALE_SMOOTH);
                
                return new ImageIcon(scaledImage);
            }
        } catch (Exception e) {
            System.err.println("Failed to load Kalix logo: " + e.getMessage());
        }
        
        // Fallback to a simple icon if logo loading fails
        return new ImageIcon(createFallbackLogo());
    }
    
    /**
     * Gets the appropriate logo path based on the current theme.
     * 
     * @return Logo path for the current theme
     */
    private String getThemeAwareLogoPath() {
        // Get the current toolbar background color from the theme
        Color toolbarBackground = UIManager.getColor("ToolBar.background");
        
        if (toolbarBackground != null) {
            // Calculate if the theme is dark or light
            int sum = toolbarBackground.getRed() + toolbarBackground.getGreen() + toolbarBackground.getBlue();
            boolean isDarkTheme = sum < 384; // Same threshold used elsewhere
            
            // Return appropriate logo path
            return isDarkTheme ? AppConstants.KALIX_LOGO_DARK_PATH : AppConstants.KALIX_LOGO_PATH;
        }
        
        // Fallback to light logo if theme color not available
        return AppConstants.KALIX_LOGO_PATH;
    }
    
    /**
     * Creates a simple fallback logo if the image file cannot be loaded.
     * 
     * @return Simple text-based logo image
     */
    private BufferedImage createFallbackLogo() {
        int width = 60;
        int height = AppConstants.TOOLBAR_LOGO_HEIGHT;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable antialiasing for better text quality
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Set background
        g2d.setColor(Color.LIGHT_GRAY);
        g2d.fillRect(0, 0, width, height);
        
        // Draw "KALIX" text
        g2d.setColor(Color.DARK_GRAY);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g2d.getFontMetrics();
        String text = "KALIX";
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getAscent();
        int x = (width - textWidth) / 2;
        int y = (height + textHeight) / 2;
        g2d.drawString(text, x, y);
        
        g2d.dispose();
        return image;
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
        // Apply theme-appropriate color to the icon if it's a FontIcon
        if (icon instanceof FontIcon fontIcon) {
            Color iconColor = getThemeAwareIconColor();
            fontIcon.setIconColor(iconColor);
        }

        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.addActionListener(listener);

        // Set accessible name for screen readers
        button.getAccessibleContext().setAccessibleName(text);

        return button;
    }
    
    /**
     * Gets the appropriate icon color based on the current theme.
     * 
     * @return Color for toolbar icons that contrasts with the current theme
     */
    private Color getThemeAwareIconColor() {
        // Get the current toolbar background color from the theme
        Color toolbarBackground = UIManager.getColor("ToolBar.background");
        
        if (toolbarBackground != null) {
            // Calculate if the theme is dark or light
            int sum = toolbarBackground.getRed() + toolbarBackground.getGreen() + toolbarBackground.getBlue();
            boolean isDarkTheme = sum < 384; // Same threshold used in MapPanel
            
            // Return appropriate icon color
            return isDarkTheme ? Color.LIGHT_GRAY : Color.DARK_GRAY;
        }
        
        // Fallback to dark gray if theme color not available
        return Color.DARK_GRAY;
    }

    /**
     * Creates a toggle button for linting with theme-aware ninja icon.
     *
     * @return Configured JToggleButton for linting
     */
    private JToggleButton createLintingToggleButton() {
        // Create ninja icon
        FontIcon icon = FontIcon.of(FontAwesomeSolid.USER_NINJA, AppConstants.TOOLBAR_ICON_SIZE);
        Color iconColor = getThemeAwareIconColor();
        icon.setIconColor(iconColor);

        JToggleButton lintingButton = new JToggleButton(icon);
        lintingButton.setFocusPainted(false);

        // Set initial state
        boolean lintingEnabled = callbacks.isLintingEnabled();
        lintingButton.setSelected(lintingEnabled);
        lintingButton.setToolTipText(lintingEnabled
            ? "Linting enabled - click to disable"
            : "Linting disabled - click to enable");

        // Add action listener
        lintingButton.addActionListener(e -> {
            callbacks.toggleLinting();
            boolean enabled = callbacks.isLintingEnabled();
            lintingButton.setSelected(enabled);
            lintingButton.setToolTipText(enabled
                ? "Linting enabled - click to disable"
                : "Linting disabled - click to enable");
        });

        // Set accessible name for screen readers
        lintingButton.getAccessibleContext().setAccessibleName("Toggle Linting");

        return lintingButton;
    }

    /**
     * Creates a toggle button for auto-reload with theme-aware eye icon.
     *
     * @return Configured JToggleButton for auto-reload
     */
    private JToggleButton createAutoReloadToggleButton() {
        // Create eye icon
        FontIcon icon = FontIcon.of(FontAwesomeSolid.EYE, AppConstants.TOOLBAR_ICON_SIZE);
        Color iconColor = getThemeAwareIconColor();
        icon.setIconColor(iconColor);

        JToggleButton autoReloadButton = new JToggleButton(icon);
        autoReloadButton.setFocusPainted(false);

        // Set initial state
        boolean autoReloadEnabled = callbacks.isAutoReloadEnabled();
        autoReloadButton.setSelected(autoReloadEnabled);
        autoReloadButton.setToolTipText(autoReloadEnabled
            ? "Auto-reload enabled - click to disable"
            : "Auto-reload disabled - click to enable");

        // Add action listener
        autoReloadButton.addActionListener(e -> {
            boolean newState = !callbacks.isAutoReloadEnabled();
            callbacks.toggleAutoReload(newState);
            autoReloadButton.setSelected(newState);
            autoReloadButton.setToolTipText(newState
                ? "Auto-reload enabled - click to disable"
                : "Auto-reload disabled - click to enable");
        });

        // Set accessible name for screen readers
        autoReloadButton.getAccessibleContext().setAccessibleName("Toggle Auto-reload");

        return autoReloadButton;
    }

    /**
     * Creates a toggle button for gridlines with theme-aware border icon.
     *
     * @return Configured JToggleButton for gridlines
     */
    private JToggleButton createGridToggleButton() {
        // Create border-none icon
        FontIcon icon = FontIcon.of(FontAwesomeSolid.BORDER_NONE, AppConstants.TOOLBAR_ICON_SIZE);
        Color iconColor = getThemeAwareIconColor();
        icon.setIconColor(iconColor);

        JToggleButton gridButton = new JToggleButton(icon);
        gridButton.setFocusPainted(false);

        // Set initial state
        boolean gridVisible = callbacks.isGridlinesVisible();
        gridButton.setSelected(gridVisible);
        gridButton.setToolTipText(gridVisible
            ? "Gridlines visible - click to hide"
            : "Gridlines hidden - click to show");

        // Add action listener
        gridButton.addActionListener(e -> {
            boolean newState = !callbacks.isGridlinesVisible();
            callbacks.toggleGridlines(newState);
            gridButton.setSelected(newState);
            gridButton.setToolTipText(newState
                ? "Gridlines visible - click to hide"
                : "Gridlines hidden - click to show");
        });

        // Set accessible name for screen readers
        gridButton.getAccessibleContext().setAccessibleName("Toggle Gridlines");

        return gridButton;
    }

}