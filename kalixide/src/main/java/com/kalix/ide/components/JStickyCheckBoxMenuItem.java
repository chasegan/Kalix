package com.kalix.ide.components;

/**
 * A checkbox menu item that leaves its popup open when activated, so a user can
 * pick items in one visit. Swing dismisses the menu on activation, which
 * is right for one-shot commands and wrong for a multi-select value list.
 */
public class JStickyCheckBoxMenuItem extends javax.swing.JCheckBoxMenuItem {

    /** The menu path captured while armed, reinstated once activation has cleared it. */
    private javax.swing.MenuElement[] selectionPath;

    public JStickyCheckBoxMenuItem(String s) {
        super(s);
        // Arming precedes the UI clearing the selected path, so this is the last
        // chance to record where in the menu hierarchy we were.
        getModel().addChangeListener(_ -> {
            if (getModel().isArmed() && isShowing()) {
                selectionPath = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
            }
        });
    }

    @Override
    protected void processMouseEvent(java.awt.event.MouseEvent e) {
        // The look and feel's own mouseReleased handler dismisses the popup before
        // it fires the item. Swallow the release and do the work here instead, so
        // the dismissal never happens on a mouse click.
        if (e.getID() == java.awt.event.MouseEvent.MOUSE_RELEASED && contains(e.getPoint())) {
            doClick();
            setArmed(true);   // stay highlighted; the cursor is still over us
            return;
        }
        super.processMouseEvent(e);
    }

    @Override
    public void doClick(int pressTime) {
        // Keyboard activation still goes through the UI, which has cleared the
        // selected path by now; reinstating it keeps the popup open for Space/Enter.
        super.doClick(pressTime);
        if (selectionPath != null) {
            javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(selectionPath);
        }
    }
}
