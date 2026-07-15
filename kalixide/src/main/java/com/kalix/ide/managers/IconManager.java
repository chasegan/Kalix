package com.kalix.ide.managers;

import com.kalix.ide.KalixIDE;
import com.kalix.ide.utils.Platform;
import com.kalix.ide.utils.PlatformUtils;

import javax.swing.JFrame;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.Taskbar;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets the application icon from the bundled multi-resolution PNG set.
 * <p>
 * {@link JFrame#setIconImages} covers the window/title-bar/Windows-taskbar icon.
 * The macOS Dock icon (and the Linux app-menu icon) is a separate mechanism:
 * a packaged bundle gets it from its .icns/.png, but a plain {@code java} launch
 * — e.g. running from an IDE or {@code ./gradlew run} — falls back to the generic
 * Java icon unless we set it explicitly via {@link Taskbar}. Doing both here means
 * the real icon shows even during development, not just in the packaged app.
 */
public class IconManager {

    public static void SetIcon(JFrame window) {
        List<Image> icons = loadIcons("kalix");
        window.setIconImages(icons);
        setTaskbarIcon(icons);
    }

    /**
     * Sets the OS-level app icon (macOS Dock / Linux app menu) so non-bundled
     * launches show the real icon. No-op on platforms that don't support it
     * (Windows drives its taskbar icon from the frame image instead).
     * <p>
     * macOS reserves a transparent margin around Dock content, so there we use
     * the padded {@code kalix-dock.png} — the full-bleed frame images would
     * render oversized next to native apps. Linux follows the full-bleed
     * convention, so it (like the fallback) uses the largest frame image.
     */
    private static void setTaskbarIcon(List<Image> icons) {
        if (!Taskbar.isTaskbarSupported() || icons.isEmpty()) {
            return;
        }
        Image dockIcon = null;
        if (PlatformUtils.getCurrentPlatform() == Platform.MACOS) {
            dockIcon = loadImage("/icons/kalix-dock.png"); // Apple-standard padding
        }
        if (dockIcon == null) {
            dockIcon = icons.get(icons.size() - 1); // largest frame image (full-bleed)
        }
        try {
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.setIconImage(dockIcon);
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Best-effort only: the frame icon above is the guaranteed path.
        }
    }

    private static Image loadImage(String resource) {
        URL url = KalixIDE.class.getResource(resource);
        return url != null ? new ImageIcon(url).getImage() : null;
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
