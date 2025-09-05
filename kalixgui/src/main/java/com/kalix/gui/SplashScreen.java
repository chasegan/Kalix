package com.kalix.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;

public class SplashScreen extends JWindow {
    
    private static final int DEFAULT_DURATION = 3000; // 3 seconds
    private static final String SPLASH_IMAGE_PATH = "./images/splash1.png";
    
    private BufferedImage splashImage;
    private Timer autoCloseTimer;
    private boolean dismissed = false;
    
    public SplashScreen() {
        loadSplashImage();
        setupWindow();
        setupAutoClose();
        setupClickToDismiss();
    }
    
    private void loadSplashImage() {
        try {
            File imageFile = new File(SPLASH_IMAGE_PATH);
            if (imageFile.exists()) {
                splashImage = ImageIO.read(imageFile);
            } else {
                System.err.println("Splash image not found: " + SPLASH_IMAGE_PATH);
                createFallbackImage();
            }
        } catch (IOException e) {
            System.err.println("Failed to load splash image: " + e.getMessage());
            createFallbackImage();
        }
    }
    
    private void createFallbackImage() {
        // Create a simple fallback splash screen
        splashImage = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = splashImage.createGraphics();
        
        // Set rendering hints for better quality
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Background gradient
        GradientPaint gradient = new GradientPaint(0, 0, new Color(70, 130, 180), 
                                                  0, 300, new Color(25, 25, 112));
        g2d.setPaint(gradient);
        g2d.fillRect(0, 0, 400, 300);
        
        // Title text
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 32));
        FontMetrics fm = g2d.getFontMetrics();
        String title = "Kalix";
        int titleX = (400 - fm.stringWidth(title)) / 2;
        g2d.drawString(title, titleX, 120);
        
        // Subtitle
        g2d.setFont(new Font("Arial", Font.PLAIN, 16));
        fm = g2d.getFontMetrics();
        String subtitle = "Hydrologic Modeling Platform";
        int subtitleX = (400 - fm.stringWidth(subtitle)) / 2;
        g2d.drawString(subtitle, subtitleX, 160);
        
        // Version
        g2d.setFont(new Font("Arial", Font.PLAIN, 12));
        fm = g2d.getFontMetrics();
        String version = "Version 1.0";
        int versionX = (400 - fm.stringWidth(version)) / 2;
        g2d.drawString(version, versionX, 200);
        
        // Click to continue hint
        g2d.setColor(new Color(220, 220, 220));
        g2d.setFont(new Font("Arial", Font.PLAIN, 11));
        fm = g2d.getFontMetrics();
        String hint = "Click anywhere to continue";
        int hintX = (400 - fm.stringWidth(hint)) / 2;
        g2d.drawString(hint, hintX, 270);
        
        g2d.dispose();
    }
    
    private void setupWindow() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int maxHeight = (int) (screenSize.height * 0.5); // 50% of screen height
        
        int windowWidth, windowHeight;
        
        if (splashImage != null) {
            // Scale image to fit within 50% screen height while maintaining aspect ratio
            int imageWidth = splashImage.getWidth();
            int imageHeight = splashImage.getHeight();
            
            if (imageHeight > maxHeight) {
                // Scale down proportionally
                double scale = (double) maxHeight / imageHeight;
                windowWidth = (int) (imageWidth * scale);
                windowHeight = maxHeight;
            } else {
                // Image is already small enough
                windowWidth = imageWidth;
                windowHeight = imageHeight;
            }
        } else {
            // Fallback size
            windowWidth = 400;
            windowHeight = 300;
        }
        
        setSize(windowWidth, windowHeight);
        setLocationRelativeTo(null); // Center on screen
        
        // Create and set the content panel
        final int finalWidth = windowWidth;
        final int finalHeight = windowHeight;
        
        JPanel contentPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (splashImage != null) {
                    // Draw scaled image with high quality
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    g2d.drawImage(splashImage, 0, 0, finalWidth, finalHeight, this);
                    g2d.dispose();
                }
            }
        };
        
        contentPanel.setPreferredSize(new Dimension(windowWidth, windowHeight));
        setContentPane(contentPanel);
        
        // Make sure the window appears on top
        setAlwaysOnTop(true);
    }
    
    private void setupAutoClose() {
        autoCloseTimer = new Timer(DEFAULT_DURATION, e -> closeSplash());
        autoCloseTimer.setRepeats(false);
    }
    
    private void setupClickToDismiss() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                closeSplash();
            }
        });
        
        // Also allow clicking on the content panel
        getContentPane().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                closeSplash();
            }
        });
    }
    
    public void showSplash() {
        if (!dismissed) {
            setVisible(true);
            autoCloseTimer.start();
        }
    }
    
    public void closeSplash() {
        if (!dismissed) {
            dismissed = true;
            autoCloseTimer.stop();
            setVisible(false);
            dispose();
        }
    }
    
    public static void showSplashScreen() {
        SwingUtilities.invokeLater(() -> {
            SplashScreen splash = new SplashScreen();
            splash.showSplash();
        });
    }
}