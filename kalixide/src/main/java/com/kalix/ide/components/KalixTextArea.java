package com.kalix.ide.components;

import com.kalix.ide.managers.FontManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * Base class for all text editor components in Kalix IDE.
 *
 * Provides common configuration that ensures consistent behavior across platforms:
 * <ul>
 *   <li>Monospace font configuration (JetBrains Mono with fallbacks)</li>
 *   <li>Windows cursor alignment fix via addNotify() override</li>
 *   <li>Common editor settings (anti-aliasing, tab handling, etc.)</li>
 * </ul>
 *
 * Subclasses should extend this class rather than RSyntaxTextArea directly to
 * inherit these critical configurations automatically.
 *
 * @see KalixIniTextArea for Kalix INI file editing with syntax highlighting
 * @see KalixPlainTextArea for plain text display (logs, output)
 */
public abstract class KalixTextArea extends RSyntaxTextArea {

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
     * Re-applies font after component realization to fix Windows cursor alignment.
     *
     * On Windows, RSyntaxTextArea may not correctly calculate font metrics when
     * setFont() is called before the component has a valid Graphics context.
     * This override ensures proper font metrics by re-applying the font once
     * the component is connected to a native screen resource.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        configureMonospaceFont();
    }

    /**
     * Configures the monospace font from user preferences.
     * Uses FontManager to get JetBrains Mono or an appropriate fallback.
     */
    protected void configureMonospaceFont() {
        int fontSize = PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, 12);
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
