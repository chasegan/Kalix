package com.kalix.ide.components;

import com.kalix.ide.managers.FontManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.WeakRegistry;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;

import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.util.function.Consumer;

/**
 * Base class for all text editor components in Kalix IDE.
 *
 * Provides common configuration that ensures consistent behavior across platforms:
 * <ul>
 *   <li>Monospace font configuration (JetBrains Mono with fallbacks)</li>
 *   <li>Font metrics that follow the screen the editor is shown on</li>
 *   <li>Common editor settings (anti-aliasing, tab handling, etc.)</li>
 *   <li>A registry of live instances, so a preference change reaches every editor</li>
 *   <li>A caret that opens any collapsed fold it is moved into, by a move or by undo</li>
 * </ul>
 *
 * Subclasses should extend this class rather than RSyntaxTextArea directly to
 * inherit these critical configurations automatically.
 *
 * @see KalixIniTextArea for Kalix INI file editing with syntax highlighting
 * @see KalixPlainTextArea for plain text display (logs, output)
 */
public abstract class KalixTextArea extends RSyntaxTextArea {

    private static final WeakRegistry<KalixTextArea> instances = new WeakRegistry<>();

    /** True while {@link #undoLastAction()} or {@link #redoLastAction()} runs. */
    private boolean undoingOrRedoing;

    /**
     * Creates a KalixTextArea with default dimensions.
     */
    protected KalixTextArea() {
        this(20, 60);
    }

    /**
     * Creates a KalixTextArea with specified dimensions.
     *
     * @param rows number of visible rows
     * @param cols number of visible columns
     */
    protected KalixTextArea(int rows, int cols) {
        super(rows, cols);
        initializeBase();
        getDocument().addDocumentListener(new RevealUndoneEdits());
        instances.add(this);
    }

    /**
     * Initializes common settings for all Kalix text areas.
     * Subclasses should call super.initializeBase() if they override this method.
     */
    protected void initializeBase() {
        // Common editor settings
        setAntiAliasingEnabled(true);
        setTabSize(4);
        setTabsEmulated(true);
        setLineWrap(false);

        // Configure monospace font from preferences
        configureMonospaceFont();
    }

    /**
     * A programmatic move of the caret into a collapsed fold opens the fold, with
     * the call the library's own search makes, so that going to a node, a line, a
     * lint issue or a find result shows what was asked for. Left to itself the
     * library's caret filter ({@code ConfigurableCaret}'s fold-aware navigation
     * filter) would push the caret to the next visible line and say nothing.
     * Keyboard and mouse moves go through the caret, not here, and cannot reach a
     * hidden line. A fold that has just been collapsed parks the caret at the end
     * of its own first line, which is not a hidden line, so this does not reopen
     * it.
     */
    @Override
    public void setCaretPosition(int offset) {
        openFoldsHiding(offset);
        super.setCaretPosition(offset);
    }

    /** The selection end is moved the same way; see {@link #setCaretPosition(int)}. */
    @Override
    public void moveCaretPosition(int offset) {
        openFoldsHiding(offset);
        super.moveCaretPosition(offset);
    }

    /**
     * Undo and redo move the caret through the caret's own document listener, not
     * {@link #setCaretPosition}, so the fold hiding an undone edit is opened from a
     * document listener of ours while the undo runs. Ours was added after the
     * caret's, so the document notifies it first and the caret then lands on a
     * visible line.
     */
    @Override
    public void undoLastAction() {
        undoingOrRedoing = true;
        try {
            super.undoLastAction();
        } finally {
            undoingOrRedoing = false;
        }
    }

    /** See {@link #undoLastAction()}. */
    @Override
    public void redoLastAction() {
        undoingOrRedoing = true;
        try {
            super.redoLastAction();
        } finally {
            undoingOrRedoing = false;
        }
    }

    private final class RevealUndoneEdits implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent e) {
            reveal(e);
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            reveal(e);
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
        }

        private void reveal(DocumentEvent e) {
            if (undoingOrRedoing) {
                openFoldsHiding(e.getOffset());
            }
        }
    }

    private void openFoldsHiding(int offset) {
        FoldManager folds = getFoldManager();
        int line = getDocument().getDefaultRootElement().getElementIndex(offset);
        if (folds.isLineHidden(line)) {
            folds.ensureOffsetNotInClosedFold(offset);
        }
    }

    /**
     * Keeps the cached font metrics in step with the screen this editor is shown on.
     *
     * <p>RSyntaxTextArea measures text with {@link FontMetrics} it caches from the
     * component's {@code Graphics}: once when first shown, and again whenever the font is
     * set. Those metrics belong to the device scale of the screen they were taken on. With
     * fractional metrics off, each glyph advance is rounded to whole device pixels, so a
     * 125% screen and a 100% screen measure the same string differently. Painting always
     * uses the live screen, so metrics from another screen leave the caret and the glyphs
     * under it misaligned
     * (<a href="https://github.com/chasegan/Kalix/issues/162">#162</a>). That happens
     * when the window is dragged to a monitor with another scale or the display scale
     * changes while the IDE runs, and it can happen from the start: the first measurement
     * runs in {@code addNotify}, from the configuration the window has at that moment, and
     * a window learns which screen it is really on only after it is shown.
     *
     * <p>Each paint therefore compares the scale the metrics were measured at, which they
     * record in their {@link java.awt.font.FontRenderContext}, with the scale of the screen
     * this editor is on, and re-applies the font when they differ. Judging the metrics
     * themselves, rather than remembering what was last done to them, means a measurement
     * that lands on the wrong screen is corrected at the next paint. Judging against the
     * screen, rather than the {@code Graphics} in hand, means a paint into an off-screen
     * image, as when a tab is dragged, neither disturbs the metrics nor triggers a
     * remeasure. The {@code graphicsConfiguration} property and {@code addNotify} were
     * tried first and each misses a case: the property fires on a re-attached component
     * before it can measure, and {@code addNotify} runs before the window has found its
     * screen.
     */
    @Override
    protected void paintComponent(Graphics g) {
        GraphicsConfiguration screen = getGraphicsConfiguration();
        if (screen != null) {
            remeasureUnlessMeasuredAt(screen.getDefaultTransform().getScaleX());
        }
        super.paintComponent(g);
    }

    /**
     * Rebuilds the font metrics unless they were measured at the given device scale.
     * Re-applying the current font is RSyntaxTextArea's public route to its (private)
     * metrics refresh, which measures with the component's own {@code Graphics} and so
     * lands on the screen's scale; it also updates the margin line and the current-line
     * highlight. All token styles are measured together, so any one stands for them all.
     *
     * @param screenScale device pixels per user-space unit of the screen the editor is on
     */
    void remeasureUnlessMeasuredAt(double screenScale) {
        FontMetrics metrics = getFontMetricsForTokenType(TokenTypes.IDENTIFIER);
        if (metrics != null && metrics.getFontRenderContext().getTransform().getScaleX() != screenScale) {
            setFont(getFont());
        }
    }

    /**
     * Configures the monospace font from user preferences.
     * Uses FontManager to get JetBrains Mono or an appropriate fallback.
     */
    protected void configureMonospaceFont() {
        int fontSize = PreferenceKeys.EDITOR_FONT_SIZE.get();
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        setFont(monoFont);
    }

    /**
     * Updates the font size dynamically.
     * Called when font size preference changes.
     *
     * @param fontSize the new font size in points
     */
    public void updateFontSize(int fontSize) {
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        setFont(monoFont);
    }

    /**
     * Updates the font size of every live Kalix text area.
     *
     * @param fontSize the new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        instances.forEach(textArea -> textArea.updateFontSize(fontSize));
    }

    /**
     * Re-resolves theme-dependent colours in every live Kalix text area.
     * Called when the application theme changes.
     */
    public static void updateAllForThemeChange() {
        instances.forEach(KalixTextArea::updateCurrentLineHighlight);
    }

    /**
     * Applies an action to every live instance of the given subclass.
     *
     * @param type   the text area class to visit; other instances are skipped
     * @param action what to do with each one
     * @param <T>    the text area class
     */
    protected static <T extends KalixTextArea> void forEachInstance(Class<T> type, Consumer<? super T> action) {
        instances.forEach(type, action);
    }

    /**
     * Determines if the current application theme is dark.
     * Useful for subclasses that need theme-aware styling.
     *
     * @return true if the current theme is dark, false otherwise
     */
    protected boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return false;
        }
        // Consider theme dark if the sum of RGB values is less than 384 (128 * 3)
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) < 384;
    }

    /**
     * Updates the current line highlight color based on the application theme.
     * Subclasses can override this to provide custom highlighting behavior.
     */
    public void updateCurrentLineHighlight() {
        Color selectionBgColor = UIManager.getColor("TextArea.selectionBackground");

        if (selectionBgColor != null) {
            int alpha = 80;
            Color lineHighlightColor = new Color(
                selectionBgColor.getRed(),
                selectionBgColor.getGreen(),
                selectionBgColor.getBlue(),
                alpha
            );
            setCurrentLineHighlightColor(lineHighlightColor);
        } else {
            if (isDarkTheme()) {
                setCurrentLineHighlightColor(new Color(255, 255, 255, 25));
            } else {
                setCurrentLineHighlightColor(new Color(0, 0, 0, 25));
            }
        }
    }
}
