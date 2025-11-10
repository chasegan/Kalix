package com.kalix.ide.windows;

import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;
import com.kalix.ide.managers.FontManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal text editor window with RSyntaxTextArea and Load/Save buttons.
 * Provides a simple, clean interface for viewing and editing text content.
 * Supports both plain text mode and Kalix INI mode with syntax highlighting.
 */
public class MinimalEditorWindow extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(MinimalEditorWindow.class);

    // Custom syntax style for Kalix INI with line continuation
    private static final String SYNTAX_STYLE_KALIX_INI = "text/kalixini";

    // Track all open instances for preference updates (using WeakReference to avoid memory leaks)
    private static final List<WeakReference<MinimalEditorWindow>> openWindows = new ArrayList<>();

    // Static block to register custom TokenMaker
    static {
        registerCustomTokenMaker();
    }

    private static java.util.function.Supplier<File> baseDirectorySupplier;

    private RSyntaxTextArea textArea;
    private RTextScrollPane scrollPane;
    private JButton loadButton;
    private JButton saveButton;
    private File currentFile;
    private final boolean useIniMode;

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
     * Creates a MinimalEditorWindow with empty content in plain text mode.
     */
    public MinimalEditorWindow() {
        this("", false);
    }

    /**
     * Creates a MinimalEditorWindow with the specified initial text content in plain text mode.
     *
     * @param initialContent The initial text to display
     */
    public MinimalEditorWindow(String initialContent) {
        this(initialContent, false);
    }

    /**
     * Creates a MinimalEditorWindow with the specified initial text content and editor mode.
     *
     * @param initialContent The initial text to display
     * @param useIniMode If true, enables Kalix INI syntax highlighting; if false, uses plain text mode
     */
    public MinimalEditorWindow(String initialContent, boolean useIniMode) {
        this.useIniMode = useIniMode;
        setupWindow();
        initializeComponents();
        setupLayout();

        if (initialContent != null && !initialContent.isEmpty()) {
            textArea.setText(initialContent);
            textArea.setCaretPosition(0); // Scroll to top
        }
    }

    /**
     * Creates a MinimalEditorWindow and loads content from the specified file in plain text mode.
     *
     * @param file The file to load
     */
    public MinimalEditorWindow(File file) {
        this(file, false);
    }

    /**
     * Creates a MinimalEditorWindow and loads content from the specified file with the specified editor mode.
     *
     * @param file The file to load
     * @param useIniMode If true, enables Kalix INI syntax highlighting; if false, uses plain text mode
     */
    public MinimalEditorWindow(File file, boolean useIniMode) {
        this.useIniMode = useIniMode;
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

        // Register this instance for preference updates
        synchronized (openWindows) {
            openWindows.add(new WeakReference<>(this));
        }

        // Clean up when window is closed
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // Cleanup is handled by WeakReferences and cleanupStaleReferences()
            }
        });
    }

    private void initializeComponents() {
        // Initialize text area
        textArea = new RSyntaxTextArea();

        // Set syntax style based on mode
        if (useIniMode) {
            textArea.setSyntaxEditingStyle(SYNTAX_STYLE_KALIX_INI);

            // Enable INI mode features
            textArea.setMarkOccurrences(true);
            textArea.setMarkOccurrencesDelay(300); // 300ms delay before highlighting
            textArea.setHighlightCurrentLine(true);

            // Set current line highlight color based on theme
            updateCurrentLineHighlight();
        } else {
            textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        }

        textArea.setLineWrap(false);
        textArea.setCodeFoldingEnabled(false);

        // CRITICAL: Set monospace font to prevent cursor position mismatch
        // Without this, proportional fonts cause cursor to appear ahead of actual typing position
        configureMonospaceFont();

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

            // Update current line highlight if in INI mode
            if (useIniMode) {
                updateCurrentLineHighlight();
            }

            textArea.repaint();
        }
    }

    /**
     * Configures a monospace font for the text area.
     * This is critical to prevent cursor position misalignment issues where the cursor
     * appears ahead of the actual typing position due to proportional font usage.
     *
     * Uses the embedded JetBrains Mono font with automatic fallback to system fonts.
     */
    private void configureMonospaceFont() {
        // Get the saved font size from preferences (default: 12pt)
        int fontSize = PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, 12);

        // Use FontManager to get the best available monospace font
        // This will use embedded JetBrains Mono if available, otherwise fall back to system fonts
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        textArea.setFont(monoFont);
    }

    /**
     * Updates the font size of the text editor.
     * This method can be called at runtime to change the font size dynamically.
     *
     * @param fontSize The new font size in points
     */
    public void updateFontSize(int fontSize) {
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        textArea.setFont(monoFont);
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
            textArea.setCaretPosition(0); // Scroll to top
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
        textArea.setCaretPosition(0); // Scroll to top
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

    /**
     * Updates the current line highlight color based on the current theme.
     * Called during initialization and when themes change.
     */
    private void updateCurrentLineHighlight() {
        if (textArea == null) {
            return;
        }

        Color selectionBgColor = UIManager.getColor("TextArea.selectionBackground");

        if (selectionBgColor != null) {
            // Create a more subtle version of the selection color for line highlight
            int alpha = 80; // More visible for better navigation feedback
            Color lineHighlightColor = new Color(
                selectionBgColor.getRed(),
                selectionBgColor.getGreen(),
                selectionBgColor.getBlue(),
                alpha
            );
            textArea.setCurrentLineHighlightColor(lineHighlightColor);
        } else {
            // Fallback: determine if dark theme and set appropriate color
            if (isDarkTheme()) {
                textArea.setCurrentLineHighlightColor(new Color(255, 255, 255, 25)); // Light highlight for dark theme
            } else {
                textArea.setCurrentLineHighlightColor(new Color(0, 0, 0, 25)); // Dark highlight for light theme
            }
        }
    }

    /**
     * Determines if the current theme is dark based on the background color.
     * @return true if dark theme, false if light theme
     */
    private boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return false;
        }
        // Consider theme dark if the sum of RGB values is less than 384 (128 * 3)
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) < 384;
    }

    /**
     * Register the custom TokenMaker for Kalix INI format with line continuation support.
     * This method is called once when the class is loaded.
     */
    private static void registerCustomTokenMaker() {
        try {
            AbstractTokenMakerFactory factory = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
            factory.putMapping(SYNTAX_STYLE_KALIX_INI, "com.kalix.ide.editor.KalixIniTokenMaker");
        } catch (Exception e) {
            logger.error("Failed to register custom Kalix INI TokenMaker", e);
        }
    }

    /**
     * Updates the font size for all open MinimalEditorWindow instances.
     * Called when font size preference changes.
     *
     * @param fontSize The new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        synchronized (openWindows) {
            // Clean up stale references and update active windows
            Iterator<WeakReference<MinimalEditorWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<MinimalEditorWindow> ref = iterator.next();
                MinimalEditorWindow window = ref.get();

                if (window == null) {
                    // Window has been garbage collected, remove the reference
                    iterator.remove();
                } else {
                    // Update the font size
                    window.updateFontSize(fontSize);
                }
            }
        }
    }

    /**
     * Updates the syntax theme for all open MinimalEditorWindow instances.
     * Called when syntax theme preference changes.
     *
     * @param syntaxTheme The new syntax theme to apply
     */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme syntaxTheme) {
        synchronized (openWindows) {
            Iterator<WeakReference<MinimalEditorWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<MinimalEditorWindow> ref = iterator.next();
                MinimalEditorWindow window = ref.get();

                if (window == null) {
                    // Window has been garbage collected, remove the reference
                    iterator.remove();
                } else {
                    // Update the syntax theme
                    window.updateSyntaxTheme(syntaxTheme);
                }
            }
        }
    }

    /**
     * Updates theme-dependent colors for all open MinimalEditorWindow instances.
     * Called when the application theme changes (e.g., light to dark).
     */
    public static void updateAllForThemeChange() {
        synchronized (openWindows) {
            Iterator<WeakReference<MinimalEditorWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<MinimalEditorWindow> ref = iterator.next();
                MinimalEditorWindow window = ref.get();

                if (window == null) {
                    // Window has been garbage collected, remove the reference
                    iterator.remove();
                } else {
                    // Update current line highlight color based on new theme
                    if (window.useIniMode) {
                        window.updateCurrentLineHighlight();
                    }
                }
            }
        }
    }
}
