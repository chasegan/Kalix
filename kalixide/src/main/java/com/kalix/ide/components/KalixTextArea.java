package com.kalix.ide.components;

import com.kalix.ide.managers.FontManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.WeakRegistry;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.beans.PropertyChangeEvent;
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
 * </ul>
 *
 * Subclasses should extend this class rather than RSyntaxTextArea directly to
 * inherit these critical configurations automatically.
 *
 * @see KalixIniTextArea for Kalix INI file editing with syntax highlighting
 * @see KalixPlainTextArea for plain text display (logs, output)
 */
public abstract class KalixTextArea extends RSyntaxTextArea {

    private static final Logger logger = LoggerFactory.getLogger(KalixTextArea.class);

    private static final WeakRegistry<KalixTextArea> instances = new WeakRegistry<>();

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

        addPropertyChangeListener("graphicsConfiguration", this::onGraphicsConfigurationChanged);
    }

    /**
     * Rebuilds the cached font metrics when this editor lands on a screen with a
     * different {@link GraphicsConfiguration}.
     *
     * <p>RSyntaxTextArea measures text with {@link java.awt.FontMetrics} that it caches
     * from the component's {@code Graphics}: once at first paint, and again whenever
     * the font is set. Those metrics belong to the device scale of the screen they were
     * taken on. With fractional metrics off, each glyph advance is rounded to whole
     * device pixels, so a 125% screen and a 150% screen measure the same string
     * differently. Painting always uses the live screen. When the window is dragged
     * to a monitor with another scale, or the display scale is changed while the IDE
     * runs, the caret and the glyphs under it therefore drift apart
     * (<a href="https://github.com/chasegan/Kalix/issues/162">#162</a>).
     *
     * <p>The JDK fires the {@code graphicsConfiguration} property on every component
     * whose configuration changes, for exactly this purpose. Re-applying the current
     * font is RSyntaxTextArea's public route to its (private) metrics refresh; it also
     * updates the margin line and the current-line highlight.
     *
     * <p>Only a change from one screen to another needs this. The first configuration
     * (old value {@code null}) is covered by RSyntaxTextArea's refresh at first paint,
     * and losing the configuration (new value {@code null}) means the component has
     * left the hierarchy.
     */
    private void onGraphicsConfigurationChanged(PropertyChangeEvent event) {
        if (event.getOldValue() != null && event.getNewValue() != null) {
            GraphicsConfiguration screen = (GraphicsConfiguration) event.getNewValue();
            logger.debug("Editor moved to a screen at {}x scale; rebuilding font metrics",
                screen.getDefaultTransform().getScaleX());
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
