package com.kalix.ide.managers.optimisation;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.windows.optimisation.OptimisationUIConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Builds and manages the UI panels for the optimisation window.
 * Handles construction of the main panels, tabs, and button panels.
 */
public class OptimisationPanelBuilder {

    // Main panels
    private JPanel rightPanel;
    private CardLayout rightPanelLayout;
    private JPanel optimisationPanel;
    private JTabbedPane mainTabbedPane;

    // Config tab components
    private JButton generateConfigButton;
    private JLabel configStatusLabel;

    // Config INI tab components
    private JButton loadConfigButton;
    private JButton saveConfigButton;

    // Results tab components
    private JButton copyToMainButton;
    private JButton compareButton;
    private JButton saveAsButton;

    // Control buttons
    private JButton runButton;
    private JButton stopButton;

    // Labels
    private JLabel startTimeLabel;
    private JLabel elapsedTimeLabel;
    private JLabel bestObjectiveLabel;
    private JLabel evaluationProgressLabel;

    /**
     * Creates a new OptimisationPanelBuilder.
     */
    public OptimisationPanelBuilder() {
    }

    /**
     * Builds the right panel with CardLayout for switching between message and optimisation views.
     *
     * @return The constructed right panel
     */
    public JPanel buildRightPanel() {
        rightPanel = new JPanel();
        rightPanelLayout = new CardLayout();
        rightPanel.setLayout(rightPanelLayout);

        // Add message panel (shown when no optimization selected)
        JPanel messagePanel = buildMessagePanel();
        rightPanel.add(messagePanel, OptimisationUIConstants.CARD_MESSAGE);

        // Add optimisation panel (shown when optimization selected)
        optimisationPanel = buildOptimisationPanel();
        rightPanel.add(optimisationPanel, OptimisationUIConstants.CARD_OPTIMISATION);

        // Show message panel by default
        rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE);

        return rightPanel;
    }

    /**
     * Builds the message panel shown when no optimisation is selected.
     */
    private JPanel buildMessagePanel() {
        JPanel messagePanel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel("<html><center>Click \"New Optimisation\" to create an optimisation<br><br>" +
            "Or select an existing optimisation from the tree</center></html>");
        messageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        messagePanel.add(messageLabel, BorderLayout.CENTER);
        return messagePanel;
    }

    /**
     * Builds the main optimisation panel with tabs.
     */
    private JPanel buildOptimisationPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create main tabbed pane
        mainTabbedPane = new JTabbedPane();

        // Add to optimisation panel
        panel.add(mainTabbedPane, BorderLayout.CENTER);

        // Add control buttons at bottom
        JPanel buttonPanel = buildButtonPanel();
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Builds the button panel at the bottom of the optimisation panel.
     */
    private JPanel buildButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        loadConfigButton = new JButton("Load Config");
        buttonPanel.add(loadConfigButton);

        saveConfigButton = new JButton("Save Config");
        buttonPanel.add(saveConfigButton);

        runButton = new JButton("Start");
        buttonPanel.add(runButton);

        return buttonPanel;
    }

    /**
     * Adds the Config GUI tab to the main tabbed pane.
     *
     * @param guiBuilder The GUI builder component to add as a tab
     */
    public void addConfigGuiTab(JComponent guiBuilder) {
        if (mainTabbedPane != null && guiBuilder != null) {
            mainTabbedPane.addTab(OptimisationUIConstants.TAB_CONFIG, guiBuilder);
        }
    }

    /**
     * Builds and adds the Config INI tab.
     *
     * @param configEditor The config editor text area
     * @param configScrollPane The scroll pane for the config editor
     * @param generateAction Action listener for generate button
     * @return The config status label
     */
    public JLabel buildConfigIniTab(KalixIniTextArea configEditor,
                                    RTextScrollPane configScrollPane,
                                    ActionListener generateAction) {
        // Create container panel for Config INI tab
        JPanel configIniPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;

        // Button panel at top - fixed height
        gbc.gridy = 0;
        gbc.weighty = 0.0;
        gbc.insets = new Insets(5, 5, 5, 5);
        JPanel configIniButtonPanel = new JPanel(new BorderLayout(10, 0));

        // Left side: Generate button
        JPanel leftButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        generateConfigButton = new JButton("Generate Config INI");
        generateConfigButton.addActionListener(generateAction);
        leftButtonPanel.add(generateConfigButton);

        // Right side: Status label
        JPanel rightLabelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT,
            OptimisationUIConstants.PADDING_SMALL, 0));
        configStatusLabel = new JLabel(OptimisationUIConstants.CONFIG_STATUS_ORIGINAL);
        rightLabelPanel.add(configStatusLabel);

        // Add left and right panels to button panel
        configIniButtonPanel.add(leftButtonPanel, BorderLayout.WEST);
        configIniButtonPanel.add(rightLabelPanel, BorderLayout.EAST);
        configIniPanel.add(configIniButtonPanel, gbc);

        // Text editor at bottom - expands vertically
        gbc.gridy = 1;
        gbc.weighty = 1.0;
        gbc.insets = new Insets(0, 5, 5, 5);
        configIniPanel.add(configScrollPane, gbc);

        // Add Config INI tab
        mainTabbedPane.addTab(OptimisationUIConstants.TAB_CONFIG_INI, configIniPanel);

        return configStatusLabel;
    }

    /**
     * Builds and adds the Results tab.
     *
     * @param optimisedModelEditor The optimised model editor
     * @param convergencePlotPanel The convergence plot panel
     * @param progressManager The progress manager for labels
     * @param copyAction Action for copy to main button
     * @param compareAction Action for compare button
     * @param saveAction Action for save as button
     */
    public void buildResultsTab(KalixIniTextArea optimisedModelEditor,
                                JPanel convergencePlotPanel,
                                OptimisationProgressManager progressManager,
                                ActionListener copyAction,
                                ActionListener compareAction,
                                ActionListener saveAction) {
        // Create results panel
        JPanel resultsPanel = new JPanel(new BorderLayout(0, 5));

        // Get labels from progress manager
        startTimeLabel = progressManager.getStartTimeLabel();
        elapsedTimeLabel = progressManager.getElapsedTimeLabel();
        bestObjectiveLabel = progressManager.getBestObjectiveLabel();
        evaluationProgressLabel = progressManager.getEvaluationProgressLabel();

        // Build labels panel
        JPanel allLabelsPanel = new JPanel(new BorderLayout());

        // Timing labels on left
        JPanel timingLabelsPanel = new JPanel();
        timingLabelsPanel.setLayout(new BoxLayout(timingLabelsPanel, BoxLayout.Y_AXIS));
        timingLabelsPanel.add(startTimeLabel);
        timingLabelsPanel.add(Box.createVerticalStrut(2));
        timingLabelsPanel.add(elapsedTimeLabel);

        // Convergence labels on right
        JPanel convergenceLabelsPanel = new JPanel();
        convergenceLabelsPanel.setLayout(new BoxLayout(convergenceLabelsPanel, BoxLayout.Y_AXIS));
        convergenceLabelsPanel.add(evaluationProgressLabel);
        convergenceLabelsPanel.add(Box.createVerticalStrut(2));
        convergenceLabelsPanel.add(bestObjectiveLabel);

        allLabelsPanel.add(timingLabelsPanel, BorderLayout.WEST);
        allLabelsPanel.add(convergenceLabelsPanel, BorderLayout.EAST);
        resultsPanel.add(allLabelsPanel, BorderLayout.NORTH);

        // Create split pane for plot and text
        JSplitPane resultsSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        resultsSplitPane.setDividerLocation(250);
        resultsSplitPane.setResizeWeight(0.4);
        resultsSplitPane.setTopComponent(convergencePlotPanel);

        // Scroll pane for optimised model text
        JScrollPane modelScrollPane = new JScrollPane(optimisedModelEditor);
        resultsSplitPane.setBottomComponent(modelScrollPane);

        // Button panel
        JPanel resultsButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        copyToMainButton = new JButton("Copy to Main Window");
        copyToMainButton.addActionListener(copyAction);
        resultsButtonPanel.add(copyToMainButton);

        compareButton = new JButton("Show Model Changes");
        compareButton.addActionListener(compareAction);
        resultsButtonPanel.add(compareButton);

        saveAsButton = new JButton("Save As");
        saveAsButton.addActionListener(saveAction);
        resultsButtonPanel.add(saveAsButton);

        resultsPanel.add(resultsButtonPanel, BorderLayout.SOUTH);
        resultsPanel.add(resultsSplitPane, BorderLayout.CENTER);

        // Add Results tab
        mainTabbedPane.addTab(OptimisationUIConstants.TAB_RESULTS, resultsPanel);
    }

    public CardLayout getRightPanelLayout() {
        return rightPanelLayout;
    }

    public JTabbedPane getMainTabbedPane() {
        return mainTabbedPane;
    }

    public JButton getRunButton() {
        return runButton;
    }

    public JButton getStopButton() {
        return stopButton;
    }

    public JButton getLoadConfigButton() {
        return loadConfigButton;
    }

    public JButton getSaveConfigButton() {
        return saveConfigButton;
    }
}