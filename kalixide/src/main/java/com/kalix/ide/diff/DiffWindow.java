package com.kalix.ide.diff;

import com.github.difflib.text.DiffRow;
import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.themes.SyntaxTheme;
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

    private static final List<WeakReference<DiffWindow>> openWindows = new ArrayList<>();

    // UI Components
    private KalixIniTextArea leftTextArea;
    private KalixIniTextArea rightTextArea;
    private RTextScrollPane leftScrollPane;
    private RTextScrollPane rightScrollPane;
    private JSplitPane splitPane;
    private JToolBar navigationToolbar;
    private JLabel statsLabel;
    private JButton prevButton;
    private JButton nextButton;

    // Diff data
    private final DiffResult diffResult;
    private int currentDifferenceIndex = -1;

    // Diff colors (theme-aware)
    private Color addedBackgroundColor;
    private Color deletedBackgroundColor;
    private Color changedBackgroundColor;
    private Color paddingBackgroundColor;
    private Color inlineChangeColor;

    // Header labels
    private final String leftHeaderLabel;
    private final String rightHeaderLabel;

    // Inline change ranges
    private List<InlineChange> leftInlineChanges;
    private List<InlineChange> rightInlineChanges;

    /**
     * Creates a diff window with default title and headers.
     *
     * @param thisModel The current/modified model
     * @param referenceModel The original/reference model
     */
    public DiffWindow(String thisModel, String referenceModel) {
        this(thisModel, referenceModel, "Kalix - Model Comparison", "Reference Model", "This Model");
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

        // Apply inline change highlighting
        applyInlineHighlighting();

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
        // Create aligned text versions with padding for proper visual alignment
        AlignedTexts alignedTexts = createAlignedTexts();

        // Store inline changes for highlighting
        this.leftInlineChanges = alignedTexts.leftInlineChanges;
        this.rightInlineChanges = alignedTexts.rightInlineChanges;

        // Create left text area (Reference Model)
        leftTextArea = createTextArea();
        leftTextArea.setText(alignedTexts.leftText);
        leftTextArea.setCaretPosition(0);
        leftScrollPane = new RTextScrollPane(leftTextArea);
        leftScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Create right text area (This Model)
        rightTextArea = createTextArea();
        rightTextArea.setText(alignedTexts.rightText);
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

    private KalixIniTextArea createTextArea() {
        KalixIniTextArea textArea = KalixIniTextArea.createReadOnly(20, 60);

        // Disable code folding for diff view
        textArea.setCodeFoldingEnabled(false);

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

    /**
     * Updates the font size of both text editors.
     *
     * @param fontSize the new font size in points
     */
    public void updateFontSize(int fontSize) {
        leftTextArea.updateFontSize(fontSize);
        rightTextArea.updateFontSize(fontSize);
    }

    private void initializeColors() {
        // Determine if dark theme
        boolean isDark = isDarkTheme();

        if (isDark) {
            addedBackgroundColor = new Color(40, 80, 40);        // Dark green
            deletedBackgroundColor = new Color(80, 40, 40);      // Dark red
            changedBackgroundColor = new Color(80, 80, 40);      // Dark yellow
            paddingBackgroundColor = new Color(60, 60, 60);      // Medium grey for padding
            inlineChangeColor = new Color(100, 100, 60);         // Darker yellow for inline changes
        } else {
            addedBackgroundColor = new Color(200, 255, 200);     // Light green
            deletedBackgroundColor = new Color(255, 200, 200);   // Light red
            changedBackgroundColor = new Color(255, 255, 200);   // Light yellow
            paddingBackgroundColor = new Color(240, 240, 240);   // Light grey for padding
            inlineChangeColor = new Color(255, 230, 150);        // Darker yellow for inline changes
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
                        // Left side is padding (grey), right side is added (green)
                        leftTextArea.addLineHighlight(i, paddingBackgroundColor);
                        rightTextArea.addLineHighlight(i, addedBackgroundColor);
                        break;
                    case DELETE:
                        // Left side is deleted (red), right side is padding (grey)
                        leftTextArea.addLineHighlight(i, deletedBackgroundColor);
                        rightTextArea.addLineHighlight(i, paddingBackgroundColor);
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

    /**
     * Applies character-level highlighting for inline changes within modified lines.
     * This provides finer-grained visual feedback than full-line highlighting.
     */
    private void applyInlineHighlighting() {
        try {
            // Create a custom highlight painter with our inline change color
            javax.swing.text.DefaultHighlighter.DefaultHighlightPainter painter =
                new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(inlineChangeColor);

            // Apply inline highlights to left text area
            javax.swing.text.Highlighter leftHighlighter = leftTextArea.getHighlighter();
            for (InlineChange change : leftInlineChanges) {
                leftHighlighter.addHighlight(change.startOffset, change.endOffset, painter);
            }

            // Apply inline highlights to right text area
            javax.swing.text.Highlighter rightHighlighter = rightTextArea.getHighlighter();
            for (InlineChange change : rightInlineChanges) {
                rightHighlighter.addHighlight(change.startOffset, change.endOffset, painter);
            }

        } catch (Exception e) {
            logger.error("Error applying inline highlighting", e);
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

            // Enable navigation buttons when there are changes (wrap-around enabled)
            prevButton.setEnabled(true);
            nextButton.setEnabled(true);
        }
    }

    private void nextDifference() {
        List<Integer> changeLines = diffResult.getChangeLineNumbers();
        if (changeLines.isEmpty()) {
            return;
        }

        currentDifferenceIndex++;
        if (currentDifferenceIndex >= changeLines.size()) {
            currentDifferenceIndex = 0;  // Wrap to first change
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
            currentDifferenceIndex = changeLines.size() - 1;  // Wrap to last change
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
     * Creates aligned text versions with blank lines inserted for proper visual alignment.
     * This ensures that matching content stays aligned even when there are insertions/deletions.
     * Also parses inline change markers (~...~) and returns clean text with highlight ranges.
     */
    private AlignedTexts createAlignedTexts() {
        StringBuilder leftText = new StringBuilder();
        StringBuilder rightText = new StringBuilder();
        List<InlineChange> leftInlineChanges = new ArrayList<>();
        List<InlineChange> rightInlineChanges = new ArrayList<>();

        List<DiffRow> rows = diffResult.getRows();

        for (int i = 0; i < rows.size(); i++) {
            DiffRow row = rows.get(i);
            String oldLine = row.getOldLine();
            String newLine = row.getNewLine();

            // Handle null values (shouldn't happen, but be defensive)
            if (oldLine == null) oldLine = "";
            if (newLine == null) newLine = "";

            // Parse and remove inline markers from left side, tracking ranges
            int leftStartOffset = leftText.length();
            String cleanedOldLine = parseInlineChanges(oldLine, leftStartOffset, leftInlineChanges);
            leftText.append(cleanedOldLine);

            // Parse and remove inline markers from right side, tracking ranges
            int rightStartOffset = rightText.length();
            String cleanedNewLine = parseInlineChanges(newLine, rightStartOffset, rightInlineChanges);
            rightText.append(cleanedNewLine);

            // Add newline if not the last line
            if (i < rows.size() - 1) {
                leftText.append("\n");
                rightText.append("\n");
            }
        }

        return new AlignedTexts(leftText.toString(), rightText.toString(),
                                leftInlineChanges, rightInlineChanges);
    }

    /**
     * Parses a line to find inline change markers (~...~), removes them,
     * and records the character ranges that should be highlighted.
     *
     * @param line The line with potential ~...~ markers
     * @param baseOffset The offset in the document where this line starts
     * @param inlineChanges List to add discovered inline change ranges to
     * @return The line with markers removed
     */
    private String parseInlineChanges(String line, int baseOffset, List<InlineChange> inlineChanges) {
        StringBuilder cleaned = new StringBuilder();
        int currentOffset = 0;
        boolean inChange = false;
        int changeStart = -1;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '~') {
                if (!inChange) {
                    // Start of inline change
                    inChange = true;
                    changeStart = currentOffset;
                } else {
                    // End of inline change
                    inChange = false;
                    // Record the range (start to current position in cleaned text)
                    inlineChanges.add(new InlineChange(
                        baseOffset + changeStart,
                        baseOffset + currentOffset
                    ));
                }
                // Don't add ~ to cleaned text
            } else {
                cleaned.append(c);
                currentOffset++;
            }
        }

        return cleaned.toString();
    }

    /**
     * Container for aligned left and right text with inline change ranges.
     */
    private static class AlignedTexts {
        final String leftText;
        final String rightText;
        final List<InlineChange> leftInlineChanges;
        final List<InlineChange> rightInlineChanges;

        AlignedTexts(String leftText, String rightText,
                     List<InlineChange> leftInlineChanges,
                     List<InlineChange> rightInlineChanges) {
            this.leftText = leftText;
            this.rightText = rightText;
            this.leftInlineChanges = leftInlineChanges;
            this.rightInlineChanges = rightInlineChanges;
        }
    }

    /**
     * Represents a character range that should be highlighted for inline changes.
     */
    private static class InlineChange {
        final int startOffset;
        final int endOffset;

        InlineChange(int startOffset, int endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    /**
     * Updates the font size for all open DiffWindow instances.
     *
     * @param fontSize the new font size in points
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

    /**
     * Updates the syntax theme for all open DiffWindow instances.
     *
     * @param syntaxTheme the new syntax theme to apply
     */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme syntaxTheme) {
        synchronized (openWindows) {
            Iterator<WeakReference<DiffWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<DiffWindow> ref = iterator.next();
                DiffWindow window = ref.get();

                if (window == null) {
                    iterator.remove();
                } else {
                    window.leftTextArea.updateSyntaxTheme(syntaxTheme);
                    window.rightTextArea.updateSyntaxTheme(syntaxTheme);
                }
            }
        }
    }
}
