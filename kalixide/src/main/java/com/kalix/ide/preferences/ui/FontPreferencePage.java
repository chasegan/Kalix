package com.kalix.ide.preferences.ui;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.util.function.IntConsumer;

/**
 * Font preferences page: editor font size.
 */
public class FontPreferencePage extends AbstractPreferencePage {

    private final EnhancedTextEditor textEditor;
    private final IntConsumer onFontSizeChanged;

    private JSpinner fontSizeSpinner;

    /**
     * @param textEditor        the main editor, updated immediately on change
     * @param onFontSizeChanged notified with the new size after the font size
     *                          preference changes (e.g. to update MinimalEditorWindows)
     */
    public FontPreferencePage(EnhancedTextEditor textEditor, IntConsumer onFontSizeChanged) {
        super("Font");
        this.textEditor = textEditor;
        this.onFontSizeChanged = onFontSizeChanged;
        initializePanel();
    }

    @Override
    public String id() {
        return "font";
    }

    @Override
    public String treePath() {
        return "Editor/Font";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Font size setting
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Font Size:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        SpinnerNumberModel spinnerModel = new SpinnerNumberModel(
            PreferenceKeys.EDITOR_FONT_SIZE.get().intValue(), // current value (unboxed: keep the int constructor)
            8,    // minimum
            24,   // maximum
            1     // step
        );
        fontSizeSpinner = new JSpinner(spinnerModel);
        fontSizeSpinner.setToolTipText("Set the font size for the text editor (8-24 points)");

        // Set a reasonable width for the spinner
        JComponent editor = fontSizeSpinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) editor).getTextField().setColumns(3);
        }

        fontSizeSpinner.addChangeListener(e -> {
            int fontSize = (Integer) fontSizeSpinner.getValue();
            PreferenceKeys.EDITOR_FONT_SIZE.set(fontSize);

            // Update the text editor font immediately
            if (textEditor != null) {
                textEditor.updateFontSize(fontSize);
            }

            // Notify callback listeners (e.g., to update MinimalEditorWindows)
            onFontSizeChanged.accept(fontSize);
        });
        formPanel.add(fontSizeSpinner, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        formPanel.add(new JLabel("pt"), gbc);

        // Add a filler to push everything to the left
        gbc.gridx = 3; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(Box.createHorizontalGlue(), gbc);

        add(formPanel, BorderLayout.NORTH);
    }
}
