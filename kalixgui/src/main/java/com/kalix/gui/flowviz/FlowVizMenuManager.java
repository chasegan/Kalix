package com.kalix.gui.flowviz;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.flowviz.data.DataSet;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * Manages all menu bar functionality for the FlowViz window including menu creation,
 * keyboard shortcuts, and action handling.
 *
 * This class handles:
 * - Menu bar creation and organization
 * - Keyboard shortcut registration
 * - Action event handling and delegation
 * - Menu state management (checkboxes, toggles)
 * - Preference-based menu item state persistence
 */
public class FlowVizMenuManager {

    private final JFrame parentFrame;
    private final Preferences prefs;

    // Menu items that need state management
    private JCheckBoxMenuItem coordinateToggle;
    private JCheckBoxMenuItem dataToggle;
    private JCheckBoxMenuItem autoYToggle;
    private JCheckBoxMenuItem precision64Toggle;

    // Action callbacks - will be set by the parent window
    private Runnable newSessionAction;
    private Runnable openCsvFileAction;
    private Runnable exportPlotAction;
    private Runnable toggleDataAction;
    private Runnable toggleCoordinateDisplayAction;
    private Runnable zoomInAction;
    private Runnable zoomOutAction;
    private Runnable zoomToFitAction;
    private Runnable resetViewAction;
    private Runnable toggleAutoYModeAction;
    private Runnable showStatisticsAction;
    private Runnable showDataInfoAction;
    private Runnable showAboutAction;
    private Runnable showShortcutsAction;
    private Runnable togglePrecision64Action;

    // State suppliers
    private Supplier<Boolean> dataVisibleSupplier;
    private Supplier<Boolean> autoYModeSupplier;
    private Supplier<Boolean> coordinateDisplaySupplier;
    private Supplier<Boolean> precision64Supplier;

    /**
     * Creates a new FlowViz menu manager for comprehensive menu system management.
     *
     * <p>This manager handles all aspects of the FlowViz window's menu system including:
     * <ul>
     * <li>Menu bar creation and organization</li>
     * <li>Keyboard shortcut registration and handling</li>
     * <li>Menu item state management (checkboxes, toggles)</li>
     * <li>Action delegation to parent window components</li>
     * <li>Preference-based state persistence</li>
     * </ul>
     *
     * @param parentFrame The parent JFrame window for menu attachment and keyboard shortcut scope
     * @param prefs Java Preferences instance for persistent menu state storage
     */
    public FlowVizMenuManager(JFrame parentFrame, Preferences prefs) {
        this.parentFrame = parentFrame;
        this.prefs = prefs;
    }

    /**
     * Sets up all action callbacks that the menu manager will invoke when menu items are selected.
     *
     * <p>This method establishes the communication bridge between menu selections and the parent
     * window's functionality. All menu actions are delegated through these callback functions,
     * allowing the menu manager to remain decoupled from the specific implementation details
     * of the parent window while still providing full menu functionality.
     *
     * @param newSessionAction Callback for starting a new session (clearing all data)
     * @param openCsvFileAction Callback for opening CSV file dialog and loading data
     * @param exportPlotAction Callback for exporting plot data or images
     * @param toggleDataAction Callback for showing/hiding the data panel
     * @param toggleCoordinateDisplayAction Callback for enabling/disabling coordinate display
     * @param zoomInAction Callback for zooming in on the plot
     * @param zoomOutAction Callback for zooming out from the plot
     * @param zoomToFitAction Callback for fitting all data in the view
     * @param resetViewAction Callback for resetting the view to default state
     * @param toggleAutoYModeAction Callback for enabling/disabling automatic Y-axis scaling
     * @param showStatisticsAction Callback for displaying dataset statistics
     * @param showDataInfoAction Callback for showing detailed data information
     * @param showAboutAction Callback for displaying application information
     * @param showShortcutsAction Callback for showing keyboard shortcuts help
     * @param togglePrecision64Action Callback for toggling 64-bit precision preference
     */
    public void setupActionCallbacks(Runnable newSessionAction,
                                   Runnable openCsvFileAction,
                                   Runnable exportPlotAction,
                                   Runnable toggleDataAction,
                                   Runnable toggleCoordinateDisplayAction,
                                   Runnable zoomInAction,
                                   Runnable zoomOutAction,
                                   Runnable zoomToFitAction,
                                   Runnable resetViewAction,
                                   Runnable toggleAutoYModeAction,
                                   Runnable showStatisticsAction,
                                   Runnable showDataInfoAction,
                                   Runnable showAboutAction,
                                   Runnable showShortcutsAction,
                                   Runnable togglePrecision64Action) {
        this.newSessionAction = newSessionAction;
        this.openCsvFileAction = openCsvFileAction;
        this.exportPlotAction = exportPlotAction;
        this.toggleDataAction = toggleDataAction;
        this.toggleCoordinateDisplayAction = toggleCoordinateDisplayAction;
        this.zoomInAction = zoomInAction;
        this.zoomOutAction = zoomOutAction;
        this.zoomToFitAction = zoomToFitAction;
        this.resetViewAction = resetViewAction;
        this.toggleAutoYModeAction = toggleAutoYModeAction;
        this.showStatisticsAction = showStatisticsAction;
        this.showDataInfoAction = showDataInfoAction;
        this.showAboutAction = showAboutAction;
        this.showShortcutsAction = showShortcutsAction;
        this.togglePrecision64Action = togglePrecision64Action;
    }

    /**
     * Sets up state suppliers for menu item state management.
     */
    public void setupStateSuppliers(Supplier<Boolean> dataVisibleSupplier,
                                  Supplier<Boolean> autoYModeSupplier,
                                  Supplier<Boolean> coordinateDisplaySupplier,
                                  Supplier<Boolean> precision64Supplier) {
        this.dataVisibleSupplier = dataVisibleSupplier;
        this.autoYModeSupplier = autoYModeSupplier;
        this.coordinateDisplaySupplier = coordinateDisplaySupplier;
        this.precision64Supplier = precision64Supplier;
    }

    /**
     * Creates and sets up the complete menu bar for the FlowViz window.
     *
     * @return The configured menu bar
     */
    public JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(createMenuItem("New", e -> newSessionAction.run()));
        fileMenu.add(createMenuItem("Add CSV...", e -> openCsvFileAction.run()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Export Plot...", e -> exportPlotAction.run()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Close", e -> parentFrame.dispose()));

        // View menu
        JMenu viewMenu = new JMenu("View");
        dataToggle = new JCheckBoxMenuItem("Show Data", dataVisibleSupplier.get());
        dataToggle.addActionListener(e -> toggleDataAction.run());
        viewMenu.add(dataToggle);

        coordinateToggle = new JCheckBoxMenuItem("Show Coordinates", coordinateDisplaySupplier.get());
        coordinateToggle.addActionListener(e -> toggleCoordinateDisplayAction.run());
        viewMenu.add(coordinateToggle);

        // Zoom menu
        JMenu zoomMenu = new JMenu("Zoom");
        zoomMenu.add(createMenuItem("Zoom In", e -> zoomInAction.run()));
        zoomMenu.add(createMenuItem("Zoom Out", e -> zoomOutAction.run()));
        zoomMenu.addSeparator();
        zoomMenu.add(createMenuItem("Zoom to Fit", e -> zoomToFitAction.run()));
        zoomMenu.add(createMenuItem("Reset View", e -> resetViewAction.run()));
        zoomMenu.addSeparator();

        autoYToggle = new JCheckBoxMenuItem("Auto-Y", autoYModeSupplier.get());
        autoYToggle.addActionListener(e -> toggleAutoYModeAction.run());
        zoomMenu.add(autoYToggle);

        // Tools menu
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.add(createMenuItem("Statistics", e -> showStatisticsAction.run()));
        toolsMenu.add(createMenuItem("Data Info", e -> showDataInfoAction.run()));

        // Preferences menu
        JMenu preferencesMenu = new JMenu("Preferences");
        precision64Toggle = new JCheckBoxMenuItem("64bit Precision", precision64Supplier.get());
        precision64Toggle.addActionListener(e -> togglePrecision64Action.run());
        preferencesMenu.add(precision64Toggle);

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem("About FlowViz", e -> showAboutAction.run()));
        helpMenu.add(createMenuItem("Keyboard Shortcuts", e -> showShortcutsAction.run()));

        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(zoomMenu);
        menuBar.add(toolsMenu);
        menuBar.add(preferencesMenu);
        menuBar.add(helpMenu);

        return menuBar;
    }

    /**
     * Sets up all keyboard shortcuts for the FlowViz window.
     */
    public void setupKeyboardShortcuts() {
        JRootPane rootPane = parentFrame.getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        // Ctrl+N for New
        inputMap.put(KeyStroke.getKeyStroke("ctrl N"), "newSession");
        actionMap.put("newSession", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newSessionAction.run();
            }
        });

        // Ctrl+O for Add CSV
        inputMap.put(KeyStroke.getKeyStroke("ctrl O"), "openFile");
        actionMap.put("openFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openCsvFileAction.run();
            }
        });

        // Ctrl+W for Close
        inputMap.put(KeyStroke.getKeyStroke("ctrl W"), "closeWindow");
        actionMap.put("closeWindow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                parentFrame.dispose();
            }
        });

        // L for toggle data panel
        inputMap.put(KeyStroke.getKeyStroke("L"), "toggleData");
        actionMap.put("toggleData", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleDataAction.run();
            }
        });

        // Plus/Minus for zoom
        inputMap.put(KeyStroke.getKeyStroke("PLUS"), "zoomIn");
        actionMap.put("zoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomInAction.run();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke("MINUS"), "zoomOut");
        actionMap.put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomOutAction.run();
            }
        });
    }

    /**
     * Updates menu item states based on current application state.
     * Should be called whenever the underlying state changes.
     */
    public void updateMenuStates() {
        if (dataToggle != null && dataVisibleSupplier != null) {
            dataToggle.setSelected(dataVisibleSupplier.get());
        }

        if (autoYToggle != null && autoYModeSupplier != null) {
            autoYToggle.setSelected(autoYModeSupplier.get());
        }

        if (coordinateToggle != null && coordinateDisplaySupplier != null) {
            coordinateToggle.setSelected(coordinateDisplaySupplier.get());
        }

        if (precision64Toggle != null && precision64Supplier != null) {
            precision64Toggle.setSelected(precision64Supplier.get());
        }
    }

    /**
     * Loads menu-related preferences and updates menu states accordingly.
     */
    public void loadMenuPreferences() {
        // Load coordinate display preference (default: false)
        boolean showCoordinates = prefs.getBoolean(AppConstants.PREF_FLOWVIZ_SHOW_COORDINATES, false);

        // Update the menu checkbox to match
        if (coordinateToggle != null) {
            coordinateToggle.setSelected(showCoordinates);
        }
    }

    /**
     * Creates a menu item with the specified text and action listener.
     *
     * @param text The menu item text
     * @param listener The action listener
     * @return The configured menu item
     */
    private JMenuItem createMenuItem(String text, ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        return item;
    }

    /**
     * Gets the coordinate display toggle menu item for external state management.
     *
     * @return The coordinate toggle menu item
     */
    public JCheckBoxMenuItem getCoordinateToggle() {
        return coordinateToggle;
    }

    /**
     * Gets the data panel toggle menu item for external state management.
     *
     * @return The data toggle menu item
     */
    public JCheckBoxMenuItem getDataToggle() {
        return dataToggle;
    }

    /**
     * Gets the Auto-Y mode toggle menu item for external state management.
     *
     * @return The Auto-Y toggle menu item
     */
    public JCheckBoxMenuItem getAutoYToggle() {
        return autoYToggle;
    }
}