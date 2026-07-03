package com.kalix.ide.preferences.ui;

import com.kalix.ide.managers.ThemeManager;
import com.kalix.ide.themes.KalixTheme;
import com.kalix.ide.themes.ThemePreferences;
import com.kalix.ide.themes.ThemeRegistry;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * Theme preferences page: application theme, plus node and syntax themes that
 * either follow the application theme or pin an explicit palette.
 */
public class ThemePreferencePage extends AbstractPreferencePage {

    /** Combo entry meaning "no explicit choice — follow the application theme". */
    private static final String FOLLOW_ITEM = "Follow application theme";

    private final ThemeManager themeManager;
    private final Runnable onMapPreferencesChanged;

    private JComboBox<KalixTheme> themeComboBox;
    private JComboBox<Object> nodeThemeComboBox;
    private JComboBox<Object> syntaxThemeComboBox;

    /**
     * @param themeManager            applies application and syntax theme switches
     * @param onMapPreferencesChanged notified after the node theme preference changes
     *                                (the listener re-resolves the preference)
     */
    public ThemePreferencePage(ThemeManager themeManager, Runnable onMapPreferencesChanged) {
        super("Themes");
        this.themeManager = themeManager;
        this.onMapPreferencesChanged = onMapPreferencesChanged;
        initializePanel();
    }

    @Override
    public String id() {
        return "theme";
    }

    @Override
    public String treePath() {
        return "Editor/Themes";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Application theme selection
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Application Theme:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        themeComboBox = new JComboBox<>(ThemeRegistry.all().toArray(new KalixTheme[0]));
        themeComboBox.setRenderer(themeDisplayNameRenderer());
        themeComboBox.setSelectedItem(themeManager.getCurrentTheme());
        themeComboBox.addActionListener(e -> {
            KalixTheme selectedTheme = (KalixTheme) themeComboBox.getSelectedItem();
            if (selectedTheme != null && selectedTheme != themeManager.getCurrentTheme()) {
                themeManager.switchTheme(selectedTheme);
            }
        });
        formPanel.add(themeComboBox, gbc);

        // Node theme selection: follow the application theme (default) or an explicit palette
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        formPanel.add(new JLabel("Node Theme:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        nodeThemeComboBox = new JComboBox<>(themeItemsWithFollow());
        nodeThemeComboBox.setRenderer(themeDisplayNameRenderer());
        nodeThemeComboBox.setSelectedItem(
            ThemePreferences.explicitNodeTheme().<Object>map(t -> t).orElse(FOLLOW_ITEM));

        nodeThemeComboBox.addActionListener(e -> {
            Object selected = nodeThemeComboBox.getSelectedItem();
            if (selected instanceof KalixTheme theme) {
                ThemePreferences.storeNodeTheme(theme);
            } else {
                ThemePreferences.storeNodeThemeFollow();
            }
            // Notify callback to update map display (re-resolves the preference)
            onMapPreferencesChanged.run();
        });
        formPanel.add(nodeThemeComboBox, gbc);

        // Syntax theme selection: follow the application theme (default) or an explicit palette
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        formPanel.add(new JLabel("Syntax Theme:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        syntaxThemeComboBox = new JComboBox<>(themeItemsWithFollow());
        syntaxThemeComboBox.setRenderer(themeDisplayNameRenderer());
        syntaxThemeComboBox.setSelectedItem(
            ThemePreferences.explicitSyntaxTheme().<Object>map(t -> t).orElse(FOLLOW_ITEM));

        syntaxThemeComboBox.addActionListener(e -> {
            Object selected = syntaxThemeComboBox.getSelectedItem();
            if (selected instanceof KalixTheme theme) {
                ThemePreferences.storeSyntaxTheme(theme);
            } else {
                ThemePreferences.storeSyntaxThemeFollow();
            }
            // Apply the now-effective syntax palette to all editors
            themeManager.updateSyntaxTheme(ThemePreferences.effectiveSyntaxTheme());
        });
        formPanel.add(syntaxThemeComboBox, gbc);


        add(formPanel, BorderLayout.NORTH);
    }

    /** The follow entry followed by every registered theme, in registry order. */
    private Object[] themeItemsWithFollow() {
        List<Object> items = new ArrayList<>();
        items.add(FOLLOW_ITEM);
        items.addAll(ThemeRegistry.all());
        return items.toArray();
    }

    /** Renders KalixTheme items by display name; other items (the follow entry) as-is. */
    private DefaultListCellRenderer themeDisplayNameRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof KalixTheme theme) {
                    setText(theme.displayName());
                }
                return this;
            }
        };
    }
}
