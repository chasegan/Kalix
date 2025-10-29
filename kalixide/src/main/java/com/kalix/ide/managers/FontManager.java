package com.kalix.ide.managers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.InputStream;

/**
 * Manages embedded fonts for the application.
 *
 * Loads and registers custom fonts (like JetBrains Mono) that are bundled with the application,
 * ensuring consistent text rendering across all platforms regardless of system fonts.
 */
public class FontManager {
    private static final Logger logger = LoggerFactory.getLogger(FontManager.class);

    // Embedded font paths
    private static final String JETBRAINS_MONO_REGULAR = "/fonts/JetBrainsMono-Regular.ttf";

    // Cached font instances
    private static Font jetBrainsMonoFont = null;
    private static boolean initialized = false;

    /**
     * Initializes the font manager and loads all embedded fonts.
     * This should be called once at application startup.
     */
    public static void initialize() {
        if (initialized) {
            return;
        }

        jetBrainsMonoFont = loadFont(JETBRAINS_MONO_REGULAR);
        initialized = true;

        if (jetBrainsMonoFont == null) {
            logger.warn("Failed to load JetBrains Mono font - will use system fallback");
        }
    }

    /**
     * Gets the JetBrains Mono font at the specified size.
     *
     * @param size The font size in points
     * @return A Font object, or null if the embedded font failed to load
     */
    public static Font getJetBrainsMono(int size) {
        if (!initialized) {
            initialize();
        }

        if (jetBrainsMonoFont != null) {
            return jetBrainsMonoFont.deriveFont(Font.PLAIN, size);
        }
        return null;
    }

    /**
     * Gets the JetBrains Mono font at the specified size and style.
     *
     * @param style The font style (Font.PLAIN, Font.BOLD, Font.ITALIC, etc.)
     * @param size The font size in points
     * @return A Font object, or null if the embedded font failed to load
     */
    public static Font getJetBrainsMono(int style, int size) {
        if (!initialized) {
            initialize();
        }

        if (jetBrainsMonoFont != null) {
            return jetBrainsMonoFont.deriveFont(style, size);
        }
        return null;
    }

    /**
     * Gets the best available monospace font for text editing.
     * Tries JetBrains Mono first, then falls back to system monospace fonts.
     *
     * @param size The font size in points
     * @return A Font object (never null - will use fallback if needed)
     */
    public static Font getMonospaceFont(int size) {
        if (!initialized) {
            initialize();
        }

        // Try embedded JetBrains Mono first
        if (jetBrainsMonoFont != null) {
            return jetBrainsMonoFont.deriveFont(Font.PLAIN, size);
        }

        // Fallback to system monospace fonts
        String[] preferredFonts = {
            "Menlo",              // macOS default
            "Consolas",           // Windows default
            "Monaco",             // macOS alternative
            "DejaVu Sans Mono",   // Linux common
            "Courier New",        // Universal fallback
            "Monospaced"          // Java logical font
        };

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] availableFonts = ge.getAvailableFontFamilyNames();
        java.util.Set<String> availableSet = new java.util.HashSet<>(java.util.Arrays.asList(availableFonts));

        for (String fontName : preferredFonts) {
            if (availableSet.contains(fontName)) {
                return new Font(fontName, Font.PLAIN, size);
            }
        }

        // Ultimate fallback
        logger.warn("No preferred monospace font found, using Monospaced");
        return new Font("Monospaced", Font.PLAIN, size);
    }

    /**
     * Loads a font from the application resources.
     *
     * @param resourcePath The path to the font file in resources
     * @return The loaded Font, or null if loading failed
     */
    private static Font loadFont(String resourcePath) {
        try {
            InputStream fontStream = FontManager.class.getResourceAsStream(resourcePath);
            if (fontStream == null) {
                logger.error("Font resource not found: {}", resourcePath);
                return null;
            }

            Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            fontStream.close();

            // Register the font with the graphics environment so it's available system-wide
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            boolean registered = ge.registerFont(font);

            if (!registered) {
                logger.warn("Font loaded but registration failed: {}", font.getFontName());
            }

            return font;

        } catch (Exception e) {
            logger.error("Failed to load font from {}: {}", resourcePath, e.getMessage(), e);
            return null;
        }
    }
}
