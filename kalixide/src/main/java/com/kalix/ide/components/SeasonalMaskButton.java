package com.kalix.ide.components;

import com.kalix.ide.flowviz.stats.SeasonalMaskMode;

import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.JButton;
import javax.swing.JPopupMenu;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Toolbar button that restricts a view to part of the calendar year. Clicking it drops a
 * popup of "All" plus Jan..Dec; the resulting {@link SeasonalMaskMode} is handed to the
 * consumer supplied at construction.
 *
 * <p>The button knows nothing about what it is masking — a plot, a statistics table, or
 * anything else — so each toolbar wires it to its own sink and each instance holds its own
 * selection. Two buttons never share state.</p>
 *
 * <p>Sizing is left to the caller so the button matches whatever toolbar it joins; only the
 * icon size is passed in, because the button swaps its own glyph as the mask goes in and
 * out of effect.</p>
 */
public class SeasonalMaskButton extends JButton {

    /** A ticked calendar while a mask is in effect, a plain one otherwise. */
    private static final FontAwesomeSolid ICON_ACTIVE = FontAwesomeSolid.CALENDAR_CHECK;
    private static final FontAwesomeSolid ICON_INACTIVE = FontAwesomeSolid.CALENDAR_DAY;

    private final Consumer<SeasonalMaskMode> onModeChanged;
    private final int iconSize;

    private final JStickyCheckBoxMenuItem allItem = new JStickyCheckBoxMenuItem("All");
    private final EnumMap<Month, JStickyCheckBoxMenuItem> monthItems = new EnumMap<>(Month.class);

    private SeasonalMaskMode mode;

    /**
     * @param onModeChanged receives every new mode the user selects
     * @param iconSize      font-icon size in points, to match the host toolbar
     * @param initialMode   the mode to show on creation; {@code null} is treated as disabled
     */
    public SeasonalMaskButton(Consumer<SeasonalMaskMode> onModeChanged, int iconSize,
                              SeasonalMaskMode initialMode) {
        this.onModeChanged = onModeChanged;
        this.iconSize = iconSize;
        this.mode = (initialMode != null) ? initialMode : SeasonalMaskMode.DISABLED;

        setToolTipText("Seasonal data mask");
        setFocusable(false);

        JPopupMenu menu = buildMenu();
        // Anchor the popup's top-left to the button's bottom-left. A method reference here
        // would bind the deprecated no-arg Component.show() instead.
        addActionListener(e -> menu.show(this, 0, getHeight()));

        showMode(this.mode);
        syncMenu(this.mode);
    }

    /** The mask currently selected. */
    public SeasonalMaskMode getMode() {
        return mode;
    }

    /**
     * Reflects a mode chosen elsewhere - an undo, a tab reset - in the button and its
     * menu, <em>without</em> notifying the sink. The caller is already applying the mode;
     * echoing it back would be a feedback loop.
     */
    public void setMode(SeasonalMaskMode newMode) {
        mode = (newMode != null) ? newMode : SeasonalMaskMode.DISABLED;
        showMode(mode);
        syncMenu(mode);
    }

    private JPopupMenu buildMenu() {
        for (Month month : Month.values()) {
            String label = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            monthItems.put(month, new JStickyCheckBoxMenuItem(label));
        }

        allItem.addActionListener(e -> {
            // Checking "All" clears the months; unchecking it selects every one. Either way
            // the whole year is in scope, which of() reports as DISABLED.
            boolean selectEveryMonth = !allItem.isSelected();
            for (var item : monthItems.values()) {
                item.setSelected(selectEveryMonth);
            }
            applyMode(SeasonalMaskMode.DISABLED);
        });

        for (var monthItem : monthItems.values()) {
            monthItem.addActionListener(e -> {
                EnumSet<Month> selected = EnumSet.noneOf(Month.class);
                monthItems.forEach((month, item) -> {
                    if (item.isSelected()) {
                        selected.add(month);
                    }
                });
                // "All" is the complement of a partial selection: checked exactly when no
                // individual month is, so deselecting the last month falls back to it.
                allItem.setSelected(selected.isEmpty());
                applyMode(SeasonalMaskMode.of(selected));
            });
        }

        JPopupMenu menu = new JPopupMenu();
        menu.add(allItem);
        menu.addSeparator();
        for (var item : monthItems.values()) {
            menu.add(item);
        }
        return menu;
    }

    /**
     * Records a user-driven change, updates the glyph, and notifies the sink. The menu is
     * deliberately not resynced here: the ticks are what the user just set, and they carry
     * information the mode does not. Unchecking "All" ticks all twelve months yet still
     * masks nothing, so of() reports DISABLED - and rewriting the menu from DISABLED would
     * clear the very ticks the click just made.
     */
    private void applyMode(SeasonalMaskMode newMode) {
        mode = newMode;
        showMode(newMode);
        onModeChanged.accept(newMode);
    }

    /**
     * Reflects a mode in the button glyph. The lit state is derived from the mode itself,
     * so a selection that masks nothing - none, or all twelve - can never look active.
     */
    private void showMode(SeasonalMaskMode current) {
        boolean active = current instanceof SeasonalMaskMode.Enabled;
        setSelected(active);
        setIcon(FontIcon.of(active ? ICON_ACTIVE : ICON_INACTIVE, iconSize));
    }

    /**
     * Drives the menu ticks from a mode. Only for state arriving from outside - an initial
     * value, an undo, a tab reset - where there is no user selection to preserve.
     */
    private void syncMenu(SeasonalMaskMode current) {
        boolean active = current instanceof SeasonalMaskMode.Enabled;
        allItem.setSelected(!active);
        for (var entry : monthItems.entrySet()) {
            entry.getValue().setSelected(active && current.includes(entry.getKey()));
        }
    }
}
