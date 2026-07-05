package com.kalix.ide.managers;

import com.kalix.ide.KalixIDE;

import javax.swing.JFrame;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets the application window icon from the bundled multi-resolution PNG set.
 */
public class IconManager {

    public static void SetIcon(JFrame window) {
        window.setIconImages(loadIcons("kalix")); // transparent background
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
}
