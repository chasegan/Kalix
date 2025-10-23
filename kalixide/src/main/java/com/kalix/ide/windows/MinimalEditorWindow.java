package com.kalix.ide.windows;

import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Minimal text editor window with RSyntaxTextArea and Load/Save buttons.
 * Provides a simple, clean interface for viewing and editing text content.
 */
public class MinimalEditorWindow extends JFrame {

    private static java.util.function.Supplier<File> baseDirectorySupplier;

    private RSyntaxTextArea textArea;
    private RTextScrollPane scrollPane;
    private JButton loadButton;
    private JButton saveButton;
    private File currentFile;

    /**
     * Sets the base directory supplier for file dialogs.
     * This supplier provides the current working directory (usually the directory of the currently open file).
     *
     * @param supplier Supplier that returns the base directory (null if no file is loaded)
     */
    public static void setBaseDirectorySupplier(java.util.function.Supplier<File> supplier) {
        baseDirectorySupplier = supplier;
    }

    /**
     * Creates a MinimalEditorWindow with empty content.
     */
    public MinimalEditorWindow() {
        this("");
    }

    /**
     * Creates a MinimalEditorWindow with the specified initial text content.
     *
     * @param initialContent The initial text to display
     */
    public MinimalEditorWindow(String initialContent) {
        setupWindow();
        initializeComponents();
        setupLayout();

        if (initialContent != null && !initialContent.isEmpty()) {
            textArea.setText(initialContent);
        }
    }

    /**
     * Creates a MinimalEditorWindow and loads content from the specified file.
     *
     * @param file The file to load
     */
    public MinimalEditorWindow(File file) {
        setupWindow();
        initializeComponents();
        setupLayout();

        if (file != null && file.exists()) {
            loadFile(file);
        }
    }

    private void setupWindow() {
        setTitle("Text Editor");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);
    }

    private void initializeComponents() {
        // Initialize text area
        textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        textArea.setLineWrap(false);
        textArea.setCodeFoldingEnabled(false);

        // Apply saved syntax theme
        applySavedSyntaxTheme();

        // Initialize scroll pane
        scrollPane = new RTextScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Initialize buttons
        loadButton = new JButton("Load");
        loadButton.addActionListener(e -> showLoadDialog());

        saveButton = new JButton("Save");
        saveButton.addActionListener(e -> showSaveDialog());
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Add text area in center
        add(scrollPane, BorderLayout.CENTER);

        // Create button panel in bottom-right
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(loadButton);
        buttonPanel.add(saveButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * Applies the saved syntax theme from preferences.
     */
    private void applySavedSyntaxTheme() {
        try {
            String savedThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_SYNTAX_THEME, "LIGHT");
            SyntaxTheme.Theme savedTheme = SyntaxTheme.getThemeByName(savedThemeName);
            updateSyntaxTheme(savedTheme);
        } catch (Exception e) {
            // Fallback to Light theme if anything goes wrong
            updateSyntaxTheme(SyntaxTheme.Theme.LIGHT);
        }
    }

    /**
     * Updates the syntax highlighting theme for the text editor.
     *
     * @param syntaxTheme The syntax theme to apply
     */
    public void updateSyntaxTheme(SyntaxTheme.Theme syntaxTheme) {
        if (textArea == null) {
            return;
        }

        org.fife.ui.rsyntaxtextarea.SyntaxScheme syntaxScheme = textArea.getSyntaxScheme();

        if (syntaxScheme != null && syntaxTheme != null) {
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.IDENTIFIER).foreground = syntaxTheme.getIdentifierColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.OPERATOR).foreground = syntaxTheme.getOperatorColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.LITERAL_STRING_DOUBLE_QUOTE).foreground = syntaxTheme.getStringColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD).foreground = syntaxTheme.getReservedWordColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.COMMENT_EOL).foreground = syntaxTheme.getCommentColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.WHITESPACE).foreground = syntaxTheme.getWhitespaceColor();

            textArea.repaint();
        }
    }

    private void showLoadDialog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load File");

        // Set initial directory to current working directory if available
        if (baseDirectorySupplier != null) {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        // If we have a current file, use its directory (takes precedence)
        if (currentFile != null) {
            fileChooser.setCurrentDirectory(currentFile.getParentFile());
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            loadFile(selectedFile);
        }
    }

    private void showSaveDialog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save File");

        // Set initial directory to current working directory if available
        if (baseDirectorySupplier != null) {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        // If we have a current file, select it (includes setting directory)
        if (currentFile != null) {
            fileChooser.setSelectedFile(currentFile);
        }

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            saveFile(selectedFile);
        }
    }

    private void loadFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            textArea.setText(content);
            currentFile = file;
            setTitle("Text Editor - " + file.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to load file: " + e.getMessage(),
                "Load Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void saveFile(File file) {
        try {
            String content = textArea.getText();
            Files.writeString(file.toPath(), content);
            currentFile = file;
            setTitle("Text Editor - " + file.getName());

            JOptionPane.showMessageDialog(
                this,
                "File saved successfully",
                "Save Successful",
                JOptionPane.INFORMATION_MESSAGE
            );
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to save file: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Gets the text content from the editor.
     *
     * @return The current text content
     */
    public String getText() {
        return textArea.getText();
    }

    /**
     * Sets the text content in the editor.
     *
     * @param text The text to set
     */
    public void setText(String text) {
        textArea.setText(text);
    }

    /**
     * Gets the current file being edited.
     *
     * @return The current file, or null if no file is loaded
     */
    public File getCurrentFile() {
        return currentFile;
    }

    /**
     * Sets the syntax editing style for the text area.
     *
     * @param syntaxStyle The syntax style constant (e.g., SyntaxConstants.SYNTAX_STYLE_JSON)
     */
    public void setSyntaxEditingStyle(String syntaxStyle) {
        textArea.setSyntaxEditingStyle(syntaxStyle);
    }
}
