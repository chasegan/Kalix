package com.kalix.ide.components;

import com.kalix.ide.managers.FontManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.EditorTheme;
import com.kalix.ide.utils.WeakRegistry;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;

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
     * Set at the end of {@link #initializeBase}. {@code updateUI} runs from the library's
     * constructor, before this object is set up and before the library has written its own
     * default colours, so the theme is applied there only once construction is complete.
     */
    private boolean constructed;

    /** The gutter the theme was last applied to, so that joining a window themes it once. */
    private Gutter themedGutter;

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

        // The library wrote its own default colours in its constructor; the theme's
        // replace them now, and again on every look-and-feel change (updateUI) and when
        // the editor joins a window and gains its gutter (addNotify).
        constructed = true;
        applyEditorTheme();
    }

    /** Whether this editor colours tokens from the syntax palette. Plain text does not. */
    protected boolean hasSyntaxPalette() {
        return false;
    }

    private void applyEditorTheme() {
        EditorTheme.apply(this, hasSyntaxPalette());
        themedGutter = RSyntaxUtilities.getGutter(this);
    }

    /**
     * Re-applies the editor theme on every look-and-feel change, so that a theme switch
     * reaches this editor through the ordinary {@code updateComponentTreeUI} walk in
     * whichever window holds it.
     */
    @Override
    public void updateUI() {
        super.updateUI();
        if (constructed) {
            applyEditorTheme();
        }
    }

    /**
     * The gutter only exists once the editor sits in its scroll pane, so the theme is
     * applied again as the editor joins a window, once per gutter: applying it costs the
     * library a re-measure of the document, and a reparent (a tab drag, say) fires this
     * again with the same gutter.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        if (constructed) {
            Gutter gutter = RSyntaxUtilities.getGutter(this);
            if (gutter != null && gutter != themedGutter) {
                applyEditorTheme();
            }
        }
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

}
