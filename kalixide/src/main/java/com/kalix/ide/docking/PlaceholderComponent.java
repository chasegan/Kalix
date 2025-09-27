package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A special component used as a placeholder in empty docking areas.
 * This component should be ignored when checking if a docking area is truly empty.
 */
public class PlaceholderComponent extends JLabel {

    public PlaceholderComponent(String text) {
        super(text, SwingConstants.CENTER);
        setupStyling();
    }

    /**
     * Sets up the visual styling for the placeholder text.
     */
    private void setupStyling() {
        setFont(getFont().deriveFont(Font.ITALIC, Dimensions.PLACEHOLDER_FONT_SIZE));
        // Color will be set dynamically based on background in updateColor()
    }

    /**
     * Updates the text color based on the current background color.
     * Makes the text subtly visible by adjusting brightness relative to background.
     */
    private void updateColor() {
        Color background = getBackground();
        if (background == null) {
            // Fallback to parent's background
            Container parent = getParent();
            if (parent != null) {
                background = parent.getBackground();
            }
        }

        if (background != null) {
            // Calculate brightness of background (0-255)
            int brightness = (int)(0.299 * background.getRed() +
                                 0.587 * background.getGreen() +
                                 0.114 * background.getBlue());

            Color subtleColor;
            if (brightness > 128) {
                // Light background - make text darker
                subtleColor = new Color(
                    Math.max(0, background.getRed() - 60),
                    Math.max(0, background.getGreen() - 60),
                    Math.max(0, background.getBlue() - 60)
                );
            } else {
                // Dark background - make text lighter
                subtleColor = new Color(
                    Math.min(255, background.getRed() + 60),
                    Math.min(255, background.getGreen() + 60),
                    Math.min(255, background.getBlue() + 60)
                );
            }
            setForeground(subtleColor);
        } else {
            // Fallback if no background color available
            setForeground(Colors.PLACEHOLDER_FALLBACK);
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        updateColor();
    }

    @Override
    public void setBackground(Color bg) {
        super.setBackground(bg);
        updateColor();
    }

    /**
     * Factory method to create the Nietzsche quote placeholder.
     */
    public static PlaceholderComponent createNietzscheQuote() {
        return new PlaceholderComponent(Text.EMPTY_AREA_QUOTE);
    }
}