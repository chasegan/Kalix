package com.kalix.gui.components;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Custom progress bar component designed for integration with the status bar.
 * Features a horizontal bar with rounded corners and smooth animations.
 */
public class StatusProgressBar extends JComponent {
    
    private static final int DEFAULT_WIDTH = 150;
    private static final int DEFAULT_HEIGHT = 18;
    private static final int CORNER_RADIUS = 4;
    
    private double progress = 0.0; // 0.0 to 1.0
    private String progressText = "";
    private boolean visible = false;
    private boolean indeterminate = false;

    // Simple colors without command-specific logic
    private Color backgroundColor = new Color(230, 230, 230);
    private Color progressColor = new Color(76, 175, 80);  // Simple green
    private Color borderColor = new Color(200, 200, 200);
    private Color textColor = Color.BLACK;

    // Animation support
    private Timer animationTimer;
    private double targetProgress = 0.0;
    private static final int ANIMATION_DELAY = 16; // ~60 FPS
    private static final double ANIMATION_SPEED = 0.05; // How fast to animate
    
    /**
     * Creates a new StatusProgressBar.
     */
    public StatusProgressBar() {
        setPreferredSize(new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        setMinimumSize(new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        setMaximumSize(new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        
        // Initially invisible
        setVisible(false);
        
        // Set up animation timer
        animationTimer = new Timer(ANIMATION_DELAY, e -> animateProgress());
    }
    
    /**
     * Sets the progress value with smooth animation.
     * 
     * @param progress progress value between 0.0 and 1.0
     */
    public void setProgress(double progress) {
        setProgress(progress, true);
    }
    
    /**
     * Sets the progress value.
     * 
     * @param progress progress value between 0.0 and 1.0
     * @param animate whether to animate the change
     */
    public void setProgress(double progress, boolean animate) {
        this.targetProgress = Math.max(0.0, Math.min(1.0, progress));
        
        if (animate && isVisible()) {
            if (!animationTimer.isRunning()) {
                animationTimer.start();
            }
        } else {
            this.progress = targetProgress;
            repaint();
        }
    }
    
    /**
     * Sets the progress as a percentage (0-100).
     * 
     * @param percentage progress percentage
     */
    public void setProgressPercentage(double percentage) {
        setProgress(percentage / 100.0);
    }
    
    /**
     * Sets the progress text to display.
     * 
     * @param text the text to display
     */
    public void setProgressText(String text) {
        this.progressText = text != null ? text : "";
        repaint();
    }
    
    /**
     * Shows the progress bar with initial progress.
     *
     * @param initialProgress initial progress value (0.0-1.0)
     * @param text initial text to display
     */
    public void showProgress(double initialProgress, String text) {
        showProgress(initialProgress, text, null);
    }

    /**
     * Shows the progress bar with initial progress.
     *
     * @param initialProgress initial progress value (0.0-1.0)
     * @param text initial text to display
     * @param command the command (ignored)
     */
    public void showProgress(double initialProgress, String text, String command) {
        this.progress = initialProgress;
        this.targetProgress = initialProgress;
        this.progressText = text != null ? text : "";
        this.visible = true;

        setVisible(true);
        repaint();
    }
    
    /**
     * Hides the progress bar.
     */
    public void hideProgress() {
        this.visible = false;
        setVisible(false);
        animationTimer.stop();
    }
    
    /**
     * Sets indeterminate mode (spinning animation).
     * 
     * @param indeterminate true for indeterminate mode
     */
    public void setIndeterminate(boolean indeterminate) {
        this.indeterminate = indeterminate;
        if (indeterminate) {
            // Could add spinning animation here if needed
        }
        repaint();
    }
    
    /**
     * Gets the current progress value.
     * 
     * @return progress value between 0.0 and 1.0
     */
    public double getProgress() {
        return progress;
    }
    
    /**
     * Checks if the progress bar is currently visible.
     * 
     * @return true if visible
     */
    public boolean isProgressVisible() {
        return visible;
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (!visible) {
            return;
        }
        
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int width = getWidth();
        int height = getHeight();
        
        // Draw background
        g2d.setColor(backgroundColor);
        RoundRectangle2D background = new RoundRectangle2D.Float(
            0, 0, width - 1, height - 1, CORNER_RADIUS, CORNER_RADIUS);
        g2d.fill(background);

        // Draw progress
        if (progress > 0) {
            int progressWidth = (int) (progress * (width - 2));
            if (progressWidth > 0) {
                g2d.setColor(progressColor);
                RoundRectangle2D progressRect = new RoundRectangle2D.Float(
                    1, 1, progressWidth, height - 3, CORNER_RADIUS, CORNER_RADIUS);
                g2d.fill(progressRect);
            }
        }

        // Draw border
        g2d.setColor(borderColor);
        g2d.draw(background);

        // Draw text if present
        if (!progressText.isEmpty()) {
            g2d.setColor(textColor);
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(progressText);
            int textHeight = fm.getAscent();
            
            // Center the text
            int x = (width - textWidth) / 2;
            int y = (height + textHeight) / 2 - 2;
            
            g2d.drawString(progressText, x, y);
        }
        
        g2d.dispose();
    }
    
    /**
     * Animates the progress bar towards the target progress.
     */
    private void animateProgress() {
        if (Math.abs(progress - targetProgress) < 0.001) {
            progress = targetProgress;
            animationTimer.stop();
            repaint();
            return;
        }
        
        // Smooth interpolation towards target
        double diff = targetProgress - progress;
        progress += diff * ANIMATION_SPEED;
        
        repaint();
    }
    
    /**
     * Resets the progress bar to initial state.
     */
    public void reset() {
        this.progress = 0.0;
        this.targetProgress = 0.0;
        this.progressText = "";
        this.indeterminate = false;
        animationTimer.stop();
        repaint();
    }
    
}