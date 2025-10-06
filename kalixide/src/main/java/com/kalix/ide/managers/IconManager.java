package com.kalix.ide.managers;
import com.kalix.ide.KalixIDE;

import javax.swing.JFrame;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
//import java.net.URI;
//import java.awt.Graphics2D;
//import java.awt.RenderingHints;
//import java.awt.image.BufferedImage;
//import com.kitfox.svg.SVGDiagram;
//import com.kitfox.svg.SVGUniverse;


public class IconManager {

    public static void SetIcon(JFrame window) {
        //window.setIconImages(loadIcons("kalix-white")); //"kalix-white"
        window.setIconImages(loadIcons("kalix")); //"kalix" <--- transparent background
    }


    private static List<Image> loadIcons(String prefix) {
        List<Image> icons = new ArrayList<>();
        int[] sizes = {16, 32, 48, 64, 128, 256, 512, 1024};
        for (int size : sizes) {
            URL iconURL = KalixIDE.class.getResource("/icons/" + prefix + "-" + size + ".png");
            if (iconURL != null) {
                icons.add(new ImageIcon(iconURL).getImage());
            }
        }
        return icons;
    }


//    public static ImageIcon loadSVGIcon(String resourcePath, int width, int height) {
//        try {
//            // Get the resource URL
//            URL url = KalixIDE.class.getResource(resourcePath);
//            if (url == null) {
//                System.err.println("Could not find resource: " + resourcePath);
//                return null;
//            }
//
//            // Create SVG universe and load the diagram
//            SVGUniverse universe = new SVGUniverse();
//            URI uri = universe.loadSVG(url);
//            SVGDiagram diagram = universe.getDiagram(uri);
//
//            // Get the original SVG dimensions
//            float svgWidth = diagram.getWidth();
//            float svgHeight = diagram.getHeight();
//
//            // Create a BufferedImage to render into
//            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
//            Graphics2D g2d = image.createGraphics();
//
//            // Enable high-quality rendering
//            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
//            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
//            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
//
//            // Scale to fit the target size
//            double scale = Math.min((double) width / svgWidth, (double) height / svgHeight);
//            g2d.scale(scale, scale);
//
//            // Render the SVG
//            diagram.render(g2d);
//            g2d.dispose();
//
//            return new ImageIcon(image);
//
//        } catch (Exception e) {
//            System.err.println("Error loading SVG icon: " + resourcePath);
//            e.printStackTrace();
//            return null;
//        }
//    }
}
