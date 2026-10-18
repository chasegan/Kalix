package com.kalix.ide.interaction;

import com.kalix.ide.MapPanel;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages a "Find Node" dialog for the map panel.
 * Provides a filterable list of nodes with keyboard-driven selection. The filter
 * matches a node's name or its aka, and a node with an aka is listed as
 * "aka (name)". The list holds nodes, never display strings (ADR-0003 §2.1).
 */
public class MapSearchManager {

    private final MapPanel mapPanel;
    private final HydrologicalModel model;

    private JDialog dialog;
    private JTextField searchField;
    private JList<ModelNode> nodeList;
    private DefaultListModel<ModelNode> listModel;

    public MapSearchManager(MapPanel mapPanel, HydrologicalModel model) {
        this.mapPanel = mapPanel;
        this.model = model;
    }

    /**
     * Shows the Find Node dialog. Creates it lazily on first call.
     * Refreshes the node list from the model each time.
     */
    public void showFindDialog() {
        if (dialog == null) {
            createDialog();
        }

        // Reset state and refresh node list
        searchField.setText("");
        refreshNodeList("");
        dialog.setLocationRelativeTo(mapPanel);
        dialog.setVisible(true);
        searchField.requestFocus();
    }

    private void createDialog() {
        Window window = SwingUtilities.getWindowAncestor(mapPanel);
        dialog = new JDialog(window instanceof Frame ? (Frame) window : null, "Find Node", false);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Search field at top
        JPanel topPanel = new JPanel(new BorderLayout(5, 0));
        topPanel.add(new JLabel("Find:"), BorderLayout.WEST);
        searchField = new JTextField(20);
        topPanel.add(searchField, BorderLayout.CENTER);
        panel.add(topPanel, BorderLayout.NORTH);

        // Filtered node list
        listModel = new DefaultListModel<>();
        nodeList = new JList<>(listModel);
        nodeList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, listText((ModelNode) value), index,
                        isSelected, cellHasFocus);
            }
        });
        nodeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeList.setVisibleRowCount(10);
        JScrollPane scrollPane = new JScrollPane(nodeList);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Filter as the user types
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { refreshNodeList(searchField.getText()); }
            @Override
            public void removeUpdate(DocumentEvent e) { refreshNodeList(searchField.getText()); }
            @Override
            public void changedUpdate(DocumentEvent e) { refreshNodeList(searchField.getText()); }
        });

        // Arrow keys in text field move list selection; Enter accepts
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    int idx = nodeList.getSelectedIndex();
                    if (idx < listModel.getSize() - 1) {
                        nodeList.setSelectedIndex(idx + 1);
                        nodeList.ensureIndexIsVisible(idx + 1);
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    int idx = nodeList.getSelectedIndex();
                    if (idx > 0) {
                        nodeList.setSelectedIndex(idx - 1);
                        nodeList.ensureIndexIsVisible(idx - 1);
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    acceptSelection();
                    e.consume();
                }
            }
        });

        // Enter on the list also accepts
        nodeList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    acceptSelection();
                    e.consume();
                }
            }
        });

        // Double-click on list accepts
        nodeList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    acceptSelection();
                }
            }
        });

        // Escape closes dialog
        dialog.getRootPane().registerKeyboardAction(
            e -> dialog.setVisible(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        dialog.add(panel);
        dialog.pack();
    }

    /**
     * Refreshes the list with the nodes whose name or aka contains the filter,
     * in name order.
     */
    private void refreshNodeList(String filter) {
        String lowerFilter = filter.toLowerCase();

        List<ModelNode> matches = model.getAllNodes().stream()
            .filter(node -> matches(node, lowerFilter))
            .sorted(Comparator.comparing(ModelNode::getName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());

        listModel.clear();
        for (ModelNode node : matches) {
            listModel.addElement(node);
        }

        if (!listModel.isEmpty()) {
            nodeList.setSelectedIndex(0);
        }
    }

    /** True if the node's name or aka contains the (already lower-cased) filter. */
    static boolean matches(ModelNode node, String lowerFilter) {
        return node.getName().toLowerCase().contains(lowerFilter)
            || (node.getAka() != null && node.getAka().toLowerCase().contains(lowerFilter));
    }

    /** The node's line in the list: its aka with the name in brackets, or just the name. */
    static String listText(ModelNode node) {
        return node.getAka() == null ? node.getName() : node.getAka() + " (" + node.getName() + ")";
    }

    /**
     * Accepts the currently selected node: selects it on the map, centers the view, and closes the dialog.
     */
    private void acceptSelection() {
        ModelNode selected = nodeList.getSelectedValue();
        if (selected != null) {
            dialog.setVisible(false);
            mapPanel.selectNodeFromEditor(selected.getName());
        }
    }
}
