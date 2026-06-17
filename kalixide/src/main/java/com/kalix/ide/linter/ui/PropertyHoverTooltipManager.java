package com.kalix.ide.linter.ui;

import com.kalix.ide.editor.EditorPosition;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.schema.ParameterDefinition;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Manages tooltip display for property hover help.
 * Shows parameter descriptions, types, constraints, and required/optional status
 * when hovering over property keys in node sections.
 *
 * Defers to validation tooltips when both would be shown on the same line.
 */
public class PropertyHoverTooltipManager {

    private static final Logger logger = LoggerFactory.getLogger(PropertyHoverTooltipManager.class);

    private final RSyntaxTextArea textArea;
    private final LinterSchema schema;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final IntPredicate hasValidationIssue; // Function to check if a line has validation issue

    // Delay before tooltip appears once the pointer settles on a property
    private static final int SHOW_DELAY_MS = 200;

    // Tooltip components
    private JWindow tooltipWindow;
    private JPanel tooltipPanel;
    private Timer hideTimer;
    private Timer showTimer;

    // Cached icon for property help tooltips
    private final FontIcon helpIcon = FontIcon.of(FontAwesomeSolid.INFO_CIRCLE, 12, new Color(70, 130, 180));

    // Track which property is currently displayed
    private String currentlyDisplayedProperty = null;
    private int currentlyDisplayedLine = -1;

    // Track pending tooltip
    private String pendingProperty = null;
    private int pendingLine = -1;

    public PropertyHoverTooltipManager(RSyntaxTextArea textArea,
                                      LinterSchema schema,
                                      Supplier<INIModelParser.ParsedModel> modelSupplier,
                                      IntPredicate hasValidationIssue) {
        this.textArea = textArea;
        this.schema = schema;
        this.modelSupplier = modelSupplier;
        this.hasValidationIssue = hasValidationIssue;
        setupTooltipComponents();
        setupMouseListeners();
    }

    private void setupTooltipComponents() {
        tooltipWindow = new JWindow();
        tooltipWindow.setType(Window.Type.POPUP);
        tooltipWindow.setFocusableWindowState(false);

        tooltipPanel = new JPanel();
        tooltipPanel.setLayout(new BoxLayout(tooltipPanel, BoxLayout.X_AXIS));
        tooltipPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        tooltipPanel.setBackground(new Color(255, 255, 240)); // Light ivory background

        tooltipWindow.add(tooltipPanel);
    }

    private void setupMouseListeners() {
        textArea.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                PropertyInfo propertyInfo = getPropertyInfoForPosition(e.getPoint());

                if (propertyInfo != null) {
                    String propertyKey = propertyInfo.propertyKey + "@" + propertyInfo.line;

                    // Already showing this property's tooltip: keep it visible
                    if (propertyKey.equals(currentlyDisplayedProperty)) {
                        stopTimer(hideTimer);
                        return;
                    }

                    // A show for this same property is already scheduled: let it fire
                    if (propertyKey.equals(pendingProperty) && showTimer != null && showTimer.isRunning()) {
                        return;
                    }

                    // Moved onto a different property: hide current, schedule new show
                    stopTimer(hideTimer);
                    if (currentlyDisplayedProperty != null) {
                        hideCustomTooltip();
                    }
                    scheduleShow(propertyInfo, e.getLocationOnScreen());
                } else {
                    // Moved off a property: cancel any pending show, hide after delay
                    stopTimer(showTimer);
                    pendingProperty = null;
                    pendingLine = -1;
                    if (currentlyDisplayedProperty != null && (hideTimer == null || !hideTimer.isRunning())) {
                        hideTimer = new Timer(100, evt -> hideCustomTooltip());
                        hideTimer.setRepeats(false);
                        hideTimer.start();
                    }
                }
            }
        });

        textArea.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hideCustomTooltip();
            }
        });
    }

    /**
     * Property information extracted from mouse position.
     */
    private static class PropertyInfo {
        String propertyKey;
        String nodeType;
        String sectionName;
        int line; // 1-based
        boolean isRequired;
        boolean isOptional;
        boolean isDsnode;

        PropertyInfo(String propertyKey, String nodeType, String sectionName, int line,
                    boolean isRequired, boolean isOptional, boolean isDsnode) {
            this.propertyKey = propertyKey;
            this.nodeType = nodeType;
            this.sectionName = sectionName;
            this.line = line;
            this.isRequired = isRequired;
            this.isOptional = isOptional;
            this.isDsnode = isDsnode;
        }
    }

    /**
     * Checks if mouse is over a property KEY (not value), and returns property info if so.
     * Returns null if validation tooltip should take precedence or if not over a property key.
     */
    private PropertyInfo getPropertyInfoForPosition(Point point) {
        try {
            int offset = textArea.viewToModel2D(point);
            int line = textArea.getLineOfOffset(offset) + 1; // Convert to 1-based

            // Check if validation tooltip should take precedence
            if (hasValidationIssue.test(line)) {
                return null; // Let validation tooltip show instead
            }

            // Use EditorPosition to analyze context
            String fullText = textArea.getText();
            INIModelParser.ParsedModel model = modelSupplier.get();
            EditorPosition position = EditorPosition.analyze(fullText, offset, model);

            // Check if we're on a property header and NOT in value position
            if (!position.isOnPropertyHeader() || position.isInValuePosition()) {
                return null; // Not on property key
            }

            String propertyKey = position.getPropertyKey();
            String nodeType = position.getNodeType();
            String sectionName = position.getSectionName();

            if (propertyKey == null || sectionName == null) {
                return null;
            }

            // Only show tooltips for node sections with a valid type
            if (!sectionName.startsWith("node.") || nodeType == null) {
                return null;
            }

            // Get node type definition to determine required/optional status
            NodeTypeDefinition nodeDef = schema.getNodeType(nodeType);
            if (nodeDef == null) {
                return null;
            }

            boolean isRequired = nodeDef.requiredParams.contains(propertyKey);
            boolean isOptional = nodeDef.optionalParams.contains(propertyKey);
            boolean isDsnode = nodeDef.dsnodeParams.contains(propertyKey);

            // Only show tooltips for known properties
            if (!isRequired && !isOptional && !isDsnode) {
                return null;
            }

            return new PropertyInfo(propertyKey, nodeType, sectionName, line,
                                  isRequired, isOptional, isDsnode);

        } catch (BadLocationException e) {
            return null;
        }
    }

    /**
     * Schedule the tooltip for the given property to appear after the dwell delay.
     */
    private void scheduleShow(PropertyInfo propertyInfo, Point screenLocation) {
        stopTimer(showTimer);
        pendingProperty = propertyInfo.propertyKey + "@" + propertyInfo.line;
        pendingLine = propertyInfo.line;
        showTimer = new Timer(SHOW_DELAY_MS, evt -> {
            showCustomTooltip(propertyInfo, screenLocation);
            currentlyDisplayedProperty = pendingProperty;
            currentlyDisplayedLine = pendingLine;
            pendingProperty = null;
            pendingLine = -1;
        });
        showTimer.setRepeats(false);
        showTimer.start();
    }

    private static void stopTimer(Timer timer) {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    private void showCustomTooltip(PropertyInfo propertyInfo, Point screenLocation) {
        buildTooltipContent(propertyInfo);
        tooltipWindow.pack();

        // Position tooltip slightly offset from mouse
        int x = screenLocation.x + 10;
        int y = screenLocation.y + 20;

        // Adjust position if tooltip would go off screen
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension tooltipSize = tooltipWindow.getSize();

        if (x + tooltipSize.width > screenSize.width) {
            x = screenLocation.x - tooltipSize.width - 10;
        }
        if (y + tooltipSize.height > screenSize.height) {
            y = screenLocation.y - tooltipSize.height - 10;
        }

        tooltipWindow.setLocation(x, y);
        tooltipWindow.setVisible(true);
    }

    private void hideCustomTooltip() {
        if (tooltipWindow != null) {
            tooltipWindow.setVisible(false);
        }
        currentlyDisplayedProperty = null;
        currentlyDisplayedLine = -1;
    }

    private void buildTooltipContent(PropertyInfo propertyInfo) {
        // Clear previous content
        tooltipPanel.removeAll();

        // Add help icon
        JLabel iconLabel = new JLabel(helpIcon);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        tooltipPanel.add(iconLabel);

        // Build tooltip HTML content
        String htmlContent = formatPropertyTooltip(propertyInfo);

        // Create label with HTML content
        JLabel contentLabel = new JLabel(htmlContent);
        contentLabel.setFont(contentLabel.getFont().deriveFont(12f));
        tooltipPanel.add(contentLabel);
    }

    /**
     * Formats the property tooltip content with description, type, constraints, and status.
     */
    private String formatPropertyTooltip(PropertyInfo propertyInfo) {
        StringBuilder html = new StringBuilder("<html>");

        // Property name and status badge
        html.append("<b>").append(propertyInfo.propertyKey).append("</b>");

        String status;
        if (propertyInfo.isRequired) {
            status = "required";
        } else if (propertyInfo.isDsnode) {
            status = "downstream link";
        } else {
            status = "optional";
        }
        html.append(" <i>(").append(status).append(")</i>");

        // Get parameter definition from schema
        NodeTypeDefinition nodeDef = schema.getNodeType(propertyInfo.nodeType);
        if (nodeDef != null) {
            ParameterDefinition paramDef = nodeDef.getParameterDefinition(propertyInfo.propertyKey);
            if (paramDef != null) {
                // Description
                if (paramDef.description != null && !paramDef.description.isEmpty()) {
                    html.append("<br><br>").append(paramDef.description);
                }

                // Type
                if (paramDef.type != null && !paramDef.type.isEmpty()) {
                    html.append("<br>Type: <code>").append(paramDef.type).append("</code>");
                }

                // Min constraint
                if (paramDef.min != null) {
                    html.append("<br>Min: ").append(formatNumber(paramDef.min));
                }

                // Max constraint
                if (paramDef.max != null) {
                    html.append("<br>Max: ").append(formatNumber(paramDef.max));
                }

                // Expected count
                if (paramDef.count != null) {
                    html.append("<br>Expected values: ").append(paramDef.count);
                }
            }
        }

        html.append("</html>");
        return html.toString();
    }

    /**
     * Formats a number for display, removing unnecessary decimal places.
     */
    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.format("%d", (long) value);
        } else {
            return String.format("%s", value);
        }
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        stopTimer(hideTimer);
        stopTimer(showTimer);
        if (tooltipWindow != null) {
            tooltipWindow.dispose();
        }
    }
}
