package com.kalix.ide.workspace;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * Left-hand project tree region.
 *
 * <p>Phase 2 placeholder: shows a muted "No folder open" message in the style of
 * VSCode's empty explorer. Phase 4 replaces the contents with the real
 * filesystem-backed {@code JTree} (see {@code docs/multi-document-architecture.md}).
 * It exists now so the three-region layout, collapse/resize, and persistence can be
 * built and exercised before the tree itself lands.
 */
public class ProjectTreePanel extends JPanel {

    private final JLabel placeholder;

    public ProjectTreePanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        placeholder = new JLabel("No folder open", SwingConstants.CENTER);
        placeholder.setForeground(mutedForeground());
        add(placeholder, BorderLayout.CENTER);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Re-read the muted colour after a look-and-feel/theme change.
        if (placeholder != null) {
            placeholder.setForeground(mutedForeground());
        }
    }

    private static Color mutedForeground() {
        Color disabled = UIManager.getColor("Label.disabledForeground");
        if (disabled != null) {
            return disabled;
        }
        Color fg = UIManager.getColor("Label.foreground");
        return fg != null ? fg : Color.GRAY;
    }
}
