package com.kalix.gui.managers;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.prefs.Preferences;

/**
 * Manages font dialog functionality for the text editor.
 * Provides a user interface for selecting font family and size with live preview.
 */
public class FontDialogManager {
    
    private final Component parentComponent;
    private final EnhancedTextEditor textEditor;
    private final Preferences prefs;
    
    /**
     * Creates a new FontDialogManager instance.
     * 
     * @param parentComponent The parent component for dialog positioning
     * @param textEditor The text editor to apply font changes to
     * @param prefs The preferences object for storing font settings
     */
    public FontDialogManager(Component parentComponent, EnhancedTextEditor textEditor, Preferences prefs) {
        this.parentComponent = parentComponent;
        this.textEditor = textEditor;
        this.prefs = prefs;
    }
    
    /**
     * Shows the font selection dialog.
     */
    public void showFontDialog() {
        Font currentFont = textEditor.getTextPane().getFont();
        
        JDialog fontDialog = createFontDialog();
        FontDialogComponents components = createDialogComponents(currentFont);
        setupDialogLayout(fontDialog, components);
        setupEventHandlers(fontDialog, components, currentFont);
        
        fontDialog.setVisible(true);
    }
    
    /**
     * Loads and applies saved font preferences to the text editor.
     */
    public void loadFontPreferences() {
        String fontName = prefs.get(AppConstants.PREF_FONT_NAME, AppConstants.DEFAULT_FONT_NAME);
        int fontSize = prefs.getInt(AppConstants.PREF_FONT_SIZE, AppConstants.DEFAULT_FONT_SIZE);
        
        Font savedFont = new Font(fontName, Font.PLAIN, fontSize);
        textEditor.getTextPane().setFont(savedFont);
    }
    
    /**
     * Creates the main font dialog.
     */
    private JDialog createFontDialog() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(parentComponent), 
                                    "Font Settings", true);
        dialog.setSize(AppConstants.FONT_DIALOG_WIDTH, AppConstants.FONT_DIALOG_HEIGHT);
        dialog.setLocationRelativeTo(parentComponent);
        dialog.setLayout(new BorderLayout());
        return dialog;
    }
    
    /**
     * Creates all dialog components.
     */
    private FontDialogComponents createDialogComponents(Font currentFont) {
        FontDialogComponents components = new FontDialogComponents();
        
        // Font selection components
        components.fontComboBox = new JComboBox<>(AppConstants.MONOSPACE_FONTS);
        components.fontComboBox.setSelectedItem(currentFont.getFontName());
        
        components.sizeComboBox = new JComboBox<>(AppConstants.FONT_SIZES);
        components.sizeComboBox.setSelectedItem(currentFont.getSize());
        
        // Preview area
        components.previewArea = new JTextArea(AppConstants.FONT_PREVIEW_TEXT);
        components.previewArea.setFont(currentFont);
        components.previewArea.setEditable(false);
        components.previewArea.setBorder(BorderFactory.createTitledBorder("Preview"));
        
        // Buttons
        components.okButton = new JButton("OK");
        components.cancelButton = new JButton("Cancel");
        
        return components;
    }
    
    /**
     * Sets up the dialog layout.
     */
    private void setupDialogLayout(JDialog dialog, FontDialogComponents components) {
        // Settings panel with GridBagLayout
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = AppConstants.DEFAULT_INSETS;
        
        // Font family selection
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST;
        settingsPanel.add(new JLabel("Font:"), gbc);
        
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(components.fontComboBox, gbc);
        
        // Font size selection
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
        settingsPanel.add(new JLabel("Size:"), gbc);
        
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(components.sizeComboBox, gbc);
        
        // Preview area
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; 
        gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        settingsPanel.add(new JScrollPane(components.previewArea), gbc);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(components.okButton);
        buttonPanel.add(components.cancelButton);
        
        dialog.add(settingsPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Sets up event handlers for dialog components.
     */
    private void setupEventHandlers(JDialog dialog, FontDialogComponents components, Font currentFont) {
        // Preview update listener
        ActionListener updatePreview = e -> updatePreview(components);
        components.fontComboBox.addActionListener(updatePreview);
        components.sizeComboBox.addActionListener(updatePreview);
        
        // OK button action
        components.okButton.addActionListener(e -> {
            applyFontSelection(components);
            dialog.dispose();
        });
        
        // Cancel button action
        components.cancelButton.addActionListener(e -> dialog.dispose());
    }
    
    /**
     * Updates the preview area with selected font.
     */
    private void updatePreview(FontDialogComponents components) {
        String fontName = (String) components.fontComboBox.getSelectedItem();
        Integer fontSize = (Integer) components.sizeComboBox.getSelectedItem();
        Font newFont = new Font(fontName, Font.PLAIN, fontSize);
        components.previewArea.setFont(newFont);
    }
    
    /**
     * Applies the selected font to the text editor and saves preferences.
     */
    private void applyFontSelection(FontDialogComponents components) {
        String fontName = (String) components.fontComboBox.getSelectedItem();
        Integer fontSize = (Integer) components.sizeComboBox.getSelectedItem();
        Font newFont = new Font(fontName, Font.PLAIN, fontSize);
        
        // Apply font to text editor
        textEditor.getTextPane().setFont(newFont);
        
        // Save preferences
        prefs.put(AppConstants.PREF_FONT_NAME, fontName);
        prefs.putInt(AppConstants.PREF_FONT_SIZE, fontSize);
    }
    
    /**
     * Applies a font to the text editor without showing the dialog.
     * Used by the settings dialog to apply font changes.
     * 
     * @param fontName The font family name
     * @param fontSize The font size
     */
    public void applyFont(String fontName, int fontSize) {
        Font newFont = new Font(fontName, Font.PLAIN, fontSize);
        textEditor.getTextPane().setFont(newFont);
    }
    
    /**
     * Helper class to hold dialog components.
     */
    private static class FontDialogComponents {
        JComboBox<String> fontComboBox;
        JComboBox<Integer> sizeComboBox;
        JTextArea previewArea;
        JButton okButton;
        JButton cancelButton;
    }
}