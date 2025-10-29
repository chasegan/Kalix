package com.kalix.ide.diff;

import com.github.difflib.text.DiffRow;
import com.kalix.ide.managers.FontManager;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Window for displaying side-by-side diff comparison of two model versions.
 * Provides read-only view with syntax highlighting and change navigation.
 */
public class DiffWindow extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(DiffWindow.class);

    // Custom syntax style for Kalix INI
    private static final String SYNTAX_STYLE_KALIX_INI = "text/kalixini";

    // Track all open instances for preference updates (using WeakReference to avoid memory leaks)
    private static final List<WeakReference<DiffWindow>> openWindows = new ArrayList<>();

    // Static block to register custom TokenMaker
    static {
        registerCustomTokenMaker();
    }

    // UI Components
    private RSyntaxTextArea leftTextArea;
    private RSyntaxTextArea rightTextArea;
    private RTextScrollPane leftScrollPane;
    private RTextScrollPane rightScrollPane;
    private JSplitPane splitPane;
    private JToolBar navigationToolbar;
    private JLabel statsLabel;
    private JButton prevButton;
    private JButton nextButton;

    // Diff data
    private DiffResult diffResult;
    private int currentDifferenceIndex = -1;

    // Diff colors (theme-aware)
    private Color addedBackgroundColor;
    private Color deletedBackgroundColor;
    private Color changedBackgroundColor;

    // Header labels
    private String leftHeaderLabel;
    private String rightHeaderLabel;

    /**
     * Creates a diff window with default title and headers.
     *
     * @param thisModel The current/modified model
     * @param referenceModel The original/reference model
     */
    public DiffWindow(String thisModel, String referenceModel) {
        this(thisModel, referenceModel, "Model Comparison", "Reference Model", "This Model");
    }

    /**
     * Creates a diff window with custom title and default headers.
     *
     * @param thisModel The current/modified model
     * @param referenceModel The original/reference model
     * @param title The window title
     */
    public DiffWindow(String thisModel, String referenceModel, String title) {
        this(thisModel, referenceModel, title, "Reference Model", "This Model");
    }

    /**
     * Creates a diff window with custom title and headers.
     *
     * @param thisModel The current/modified model
     * @param referenceModel The original/reference model
     * @param title The window title
     * @param leftHeader The header label for the left pane (reference model)
     * @param rightHeader The header label for the right pane (this model)
     */
    public DiffWindow(String thisModel, String referenceModel, String title, String leftHeader, String rightHeader) {
        setTitle(title);

        // Store header labels
        this.leftHeaderLabel = leftHeader;
        this.rightHeaderLabel = rightHeader;

        // Compute diff
        diffResult = DiffEngine.computeDiff(referenceModel, thisModel);

        // Initialize theme-aware colors
        initializeColors();

        // Setup window
        setupWindow();

        // Initialize components
        initializeComponents(referenceModel, thisModel);

        // Setup layout
        setupLayout();

        // Apply diff highlighting
        applyDiffHighlighting();

        // Setup synchronized scrolling
        setupSynchronizedScrolling();

        // Update navigation state
        updateNavigationState();

        // Make visible
        setVisible(true);
    }

    private void setupWindow() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        // Register this instance for preference updates
        synchronized (openWindows) {
            openWindows.add(new WeakReference<>(this));
        }

        // Clean up when window is closed
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // Cleanup is handled by WeakReferences and updateAllFontSizes()
            }
        });
    }

    private void initializeComponents(String referenceModel, String thisModel) {
        // Create left text area (Reference Model)
        leftTextArea = createTextArea();
        leftTextArea.setText(referenceModel);
        leftTextArea.setCaretPosition(0);
        leftScrollPane = new RTextScrollPane(leftTextArea);
        leftScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Create right text area (This Model)
        rightTextArea = createTextArea();
        rightTextArea.setText(thisModel);
        rightTextArea.setCaretPosition(0);
        rightScrollPane = new RTextScrollPane(rightTextArea);
        rightScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Create split pane
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(createPanelWithHeader(leftHeaderLabel, leftScrollPane));
        splitPane.setRightComponent(createPanelWithHeader(rightHeaderLabel, rightScrollPane));
        splitPane.setDividerLocation(600); // Half of 1200 default width
        splitPane.setResizeWeight(0.5);

        // Create navigation toolbar
        navigationToolbar = createNavigationToolbar();
    }

    private RSyntaxTextArea createTextArea() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();

        // Set Kalix INI syntax
        textArea.setSyntaxEditingStyle(SYNTAX_STYLE_KALIX_INI);

        // Read-only
        textArea.setEditable(false);

        // No line wrap
        textArea.setLineWrap(false);
        textArea.setCodeFoldingEnabled(false);

        // Highlighting features
        textArea.setHighlightCurrentLine(true);
        textArea.setCurrentLineHighlightColor(new Color(0, 0, 0, 20));

        // Configure monospace font
        configureMonospaceFont(textArea);

        // Apply saved syntax theme
        applySavedSyntaxTheme(textArea);

        return textArea;
    }

    private JPanel createPanelWithHeader(String headerText, RTextScrollPane scrollPane) {
        JPanel panel = new JPanel(new BorderLayout());

        // Create header label
        JLabel headerLabel = new JLabel(headerText);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        headerLabel.setOpaque(true);
        headerLabel.setBackground(UIManager.getColor("Panel.background"));

        panel.add(headerLabel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JToolBar createNavigationToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(new EmptyBorder(5, 5, 5, 5));

        // Previous button
        prevButton = new JButton("< Previous");
        prevButton.addActionListener(e -> previousDifference());
        toolbar.add(prevButton);

        // Next button
        nextButton = new JButton("Next >");
        nextButton.addActionListener(e -> nextDifference());
        toolbar.add(nextButton);

        // Spacer
        toolbar.add(Box.createHorizontalStrut(20));

        // Stats label
        statsLabel = new JLabel();
        toolbar.add(statsLabel);

        // Right-aligned spacer
        toolbar.add(Box.createHorizontalGlue());

        // Close button
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        toolbar.add(closeButton);

        return toolbar;
    }

    private void setupLayout() {
        setLayout(new BorderLayout());
        add(navigationToolbar, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
    }

    private void configureMonospaceFont(RSyntaxTextArea textArea) {
        int fontSize = PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, 12);
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        textArea.setFont(monoFont);
    }

    /**
     * Updates the font size of both text editors.
     *
     * @param fontSize The new font size in points
     */
    public void updateFontSize(int fontSize) {
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        leftTextArea.setFont(monoFont);
        rightTextArea.setFont(monoFont);
    }

    private void applySavedSyntaxTheme(RSyntaxTextArea textArea) {
        try {
            String savedThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_SYNTAX_THEME, "LIGHT");
            SyntaxTheme.Theme savedTheme = SyntaxTheme.getThemeByName(savedThemeName);
            updateSyntaxTheme(textArea, savedTheme);
        } catch (Exception e) {
            updateSyntaxTheme(textArea, SyntaxTheme.Theme.LIGHT);
        }
    }

    private void updateSyntaxTheme(RSyntaxTextArea textArea, SyntaxTheme.Theme syntaxTheme) {
        if (textArea == null || syntaxTheme == null) {
            return;
        }

        org.fife.ui.rsyntaxtextarea.SyntaxScheme syntaxScheme = textArea.getSyntaxScheme();

        if (syntaxScheme != null) {
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.IDENTIFIER).foreground = syntaxTheme.getIdentifierColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.OPERATOR).foreground = syntaxTheme.getOperatorColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.LITERAL_STRING_DOUBLE_QUOTE).foreground = syntaxTheme.getStringColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD).foreground = syntaxTheme.getReservedWordColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.COMMENT_EOL).foreground = syntaxTheme.getCommentColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.WHITESPACE).foreground = syntaxTheme.getWhitespaceColor();

            textArea.repaint();
        }
    }

    private void initializeColors() {
        // Determine if dark theme
        boolean isDark = isDarkTheme();

        if (isDark) {
            addedBackgroundColor = new Color(40, 80, 40);        // Dark green
            deletedBackgroundColor = new Color(80, 40, 40);      // Dark red
            changedBackgroundColor = new Color(80, 80, 40);      // Dark yellow
        } else {
            addedBackgroundColor = new Color(200, 255, 200);     // Light green
            deletedBackgroundColor = new Color(255, 200, 200);   // Light red
            changedBackgroundColor = new Color(255, 255, 200);   // Light yellow
        }
    }

    private boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return false;
        }
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) < 384;
    }

    private void applyDiffHighlighting() {
        try {
            List<DiffRow> rows = diffResult.getRows();

            for (int i = 0; i < rows.size(); i++) {
                DiffRow row = rows.get(i);
                DiffRow.Tag tag = row.getTag();

                Color color = null;
                switch (tag) {
                    case INSERT:
                        color = addedBackgroundColor;
                        // Highlight in right pane only
                        rightTextArea.addLineHighlight(i, color);
                        break;
                    case DELETE:
                        color = deletedBackgroundColor;
                        // Highlight in left pane only
                        leftTextArea.addLineHighlight(i, color);
                        break;
                    case CHANGE:
                        color = changedBackgroundColor;
                        // Highlight in both panes
                        leftTextArea.addLineHighlight(i, color);
                        rightTextArea.addLineHighlight(i, color);
                        break;
                    case EQUAL:
                        // No highlighting for unchanged lines
                        break;
                }
            }
        } catch (Exception e) {
            logger.error("Error applying diff highlighting", e);
        }
    }

    private void setupSynchronizedScrolling() {
        JScrollBar leftVertical = leftScrollPane.getVerticalScrollBar();
        JScrollBar rightVertical = rightScrollPane.getVerticalScrollBar();

        // Sync left -> right
        leftVertical.addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                rightVertical.setValue(leftVertical.getValue());
            }
        });

        // Sync right -> left
        rightVertical.addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                leftVertical.setValue(rightVertical.getValue());
            }
        });
    }

    private void updateNavigationState() {
        int totalChanges = diffResult.getTotalChanges();

        if (totalChanges == 0) {
            statsLabel.setText("No changes");
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
        } else {
            String changeText = totalChanges == 1 ? "change" : "changes";
            statsLabel.setText(totalChanges + " " + changeText);

            // Enable navigation buttons based on current position
            List<Integer> changeLines = diffResult.getChangeLineNumbers();
            prevButton.setEnabled(currentDifferenceIndex > 0);
            nextButton.setEnabled(currentDifferenceIndex < changeLines.size() - 1);
        }
    }

    private void nextDifference() {
        List<Integer> changeLines = diffResult.getChangeLineNumbers();
        if (changeLines.isEmpty()) {
            return;
        }

        currentDifferenceIndex++;
        if (currentDifferenceIndex >= changeLines.size()) {
            currentDifferenceIndex = changeLines.size() - 1;
        }

        scrollToLine(changeLines.get(currentDifferenceIndex));
        updateNavigationState();
    }

    private void previousDifference() {
        List<Integer> changeLines = diffResult.getChangeLineNumbers();
        if (changeLines.isEmpty()) {
            return;
        }

        currentDifferenceIndex--;
        if (currentDifferenceIndex < 0) {
            currentDifferenceIndex = 0;
        }

        scrollToLine(changeLines.get(currentDifferenceIndex));
        updateNavigationState();
    }

    private void scrollToLine(int lineNumber) {
        try {
            // Scroll both text areas to the line
            int offset = leftTextArea.getLineStartOffset(lineNumber);
            leftTextArea.setCaretPosition(offset);

            offset = rightTextArea.getLineStartOffset(lineNumber);
            rightTextArea.setCaretPosition(offset);

            // Center the line in view
            Rectangle rect = leftTextArea.modelToView(offset);
            if (rect != null) {
                rect.y -= leftScrollPane.getViewport().getHeight() / 2;
                leftTextArea.scrollRectToVisible(rect);
            }

        } catch (Exception e) {
            logger.error("Error scrolling to line " + lineNumber, e);
        }
    }

    /**
     * Register the custom TokenMaker for Kalix INI format.
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
     * Updates the font size for all open DiffWindow instances.
     * Called when font size preference changes.
     *
     * @param fontSize The new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        synchronized (openWindows) {
            Iterator<WeakReference<DiffWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<DiffWindow> ref = iterator.next();
                DiffWindow window = ref.get();

                if (window == null) {
                    iterator.remove();
                } else {
                    window.updateFontSize(fontSize);
                }
            }
        }
    }
}
