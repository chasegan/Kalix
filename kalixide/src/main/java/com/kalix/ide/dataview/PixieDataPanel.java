package com.kalix.ide.dataview;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.constants.UIConstants;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * The table side of a Pixie data document: a {@link JTable} over the decoded
 * union index, styled like the CSV data table (house grid lines), with a
 * status strip declaring what was decoded — series count, rows, and any
 * refusal (the pre-decode gate, a missing {@code .pxb}) in the same honest,
 * no-dialog style as the CSV viewer.
 */
public final class PixieDataPanel extends JPanel {

    private final PixieDataSession session;
    private final JTable table;
    private final JLabel status = new JLabel("Decoding pixie…");
    private final JPanel statusBar = new JPanel(new BorderLayout());
    /** The unified Find — the same controller the CSV table uses, via FindableData. */
    private final DataFindController findController;

    public PixieDataPanel(PixieDataSession session) {
        super(new BorderLayout());
        this.session = session;

        table = new JTable(new PixieTableModel(session));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // wide files scroll horizontally
        table.setFillsViewportHeight(true);
        table.setRowHeight(UIConstants.TableView.ROW_HEIGHT);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(1, 1));
        applyGridColor();
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);
        this.findController = new DataFindController(table, () -> session);
        installInteractions();
        add(new JScrollPane(table), BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        statusBar.add(status, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Registered AFTER the model's listener, so the columns exist when this runs.
        session.addListener(this::onLoaded);
    }

    /** Context menu + keyboard: the unified Find (same grammar as the CSV table) and Copy. */
    private void installInteractions() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem find = new JMenuItem("Find…");
        find.addActionListener(e -> findController.openFind());
        menu.add(find);
        menu.addSeparator();
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> {
            Action builtIn = table.getActionMap().get("copy");
            if (builtIn != null) {
                builtIn.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });
        menu.add(copy);
        table.setComponentPopupMenu(menu);

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, AppShortcut.menuMask()), "data-find");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "data-find-next");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "data-find-previous");
        table.getActionMap().put("data-find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findController.openFind();
            }
        });
        table.getActionMap().put("data-find-next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findController.repeatFind(true);
            }
        });
        table.getActionMap().put("data-find-previous", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findController.repeatFind(false);
            }
        });
    }

    private void onLoaded() {
        refreshStatus();
        if (table.getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setPreferredWidth(140); // dates are wide
        }
    }

    private void refreshStatus() {
        if (!session.isLoaded()) {
            status.setText("Decoding pixie…");
        } else if (session.refusal() != null) {
            status.setText(session.refusal());
        } else {
            status.setText(String.format("%,d series  ·  %,d rows  ·  pixie (Gorilla-compressed)",
                session.seriesCount(), session.rowCount()));
        }
    }

    /** Installs a leading accessory in the status strip (the viz mount's note). */
    public void setStatusAccessory(JComponent accessory) {
        statusBar.add(accessory, BorderLayout.WEST);
        statusBar.revalidate();
    }

    /** Re-resolves the theme's grid colour after a LaF switch. Null-guarded for the super ctor. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (table != null) {
            applyGridColor();
        }
    }

    private void applyGridColor() {
        Color gridColor = UIManager.getColor("Table.gridColor");
        table.setGridColor(gridColor != null ? gridColor : UIConstants.TableView.FALLBACK_GRID_COLOR);
    }

    /** The underlying table — package-private, for tests and the viz mount. */
    JTable getTable() {
        return table;
    }

    /** The status strip's current text — package-private, for tests. */
    String getStatusText() {
        return status.getText();
    }
}
