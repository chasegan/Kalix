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
        
        // Branding button - far left
        toolBar.add(createBrandingButton());
        toolBar.addSeparator();
        
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
            FontIcon.of(FontAwesomeSolid.CHART_LINE, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.flowViz()
        ));
        
        toolBar.addSeparator();
        
        // Model operations
        toolBar.add(createToolBarButton(
            "Run Model", 
            AppConstants.TOOLBAR_RUN_MODEL_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.PLAY, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.runModelFromMemory()
        ));
        
        toolBar.add(createToolBarButton(
            "Run Manager",
            AppConstants.TOOLBAR_SESSIONS_TOOLTIP,
            FontIcon.of(FontAwesomeSolid.TASKS, AppConstants.TOOLBAR_ICON_SIZE),
            e -> callbacks.showRunManager()
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
     * Creates the branding button with the Kalix logo.
     * 
     * @return Configured branding JButton
     */
    private JButton createBrandingButton() {
        ImageIcon logoIcon = loadScaledLogo();
        JButton brandingButton = new JButton(logoIcon);
        brandingButton.setToolTipText(AppConstants.TOOLBAR_LOGO_TOOLTIP);
        brandingButton.setFocusPainted(false);
        brandingButton.setBorderPainted(false);
        brandingButton.setContentAreaFilled(false);
        brandingButton.setOpaque(false);
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
        if (icon instanceof FontIcon) {
            FontIcon fontIcon = (FontIcon) icon;
            Color iconColor = getThemeAwareIconColor();
            fontIcon.setIconColor(iconColor);
        }
        
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
    
}