package com.kalix.ide.linter.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * Shared dwell-tooltip machinery for the linter's hover tooltips: a borderless POPUP
 * {@link JWindow} that appears once the pointer settles ({@link #SHOW_DELAY_MS}) on a target
 * line, hides shortly after the pointer leaves, and is positioned beside the pointer and clamped
 * to the monitor the pointer is on.
 *
 * <p>Subclasses supply only what varies: the cheap hot-path hit test ({@link #probe}), run on
 * every mouse move, and the content build ({@link #populate}), run inside the dwell timer so
 * expensive analysis never touches the raw mouse-move path. The panel's layout axis and
 * background are passed to the constructor.
 */
abstract class DwellTooltipSupport {

    /** A tooltip target sitting under the pointer; carries at least its 1-based line number. */
    protected interface Target {
        int line();
    }

    // Delay before a tooltip appears once the pointer settles on a target line. Transient
    // crossings (sweeping the pointer past several lines) cancel the pending show before it
    // fires, so nothing is built for lines the pointer merely passes over.
    private static final int SHOW_DELAY_MS = 200;
    private static final int HIDE_DELAY_MS = 100;

    protected final RSyntaxTextArea textArea;

    private final JWindow tooltipWindow;
    /** The content panel; subclasses clear and fill it in {@link #populate}. */
    protected final JPanel tooltipPanel;

    private Timer hideTimer;
    private Timer showTimer;

    // 1-based line whose tooltip is currently shown / scheduled to show, or -1 if none. Used to
    // avoid rebuilding while the pointer stays on the same line.
    private int currentlyDisplayedLine = -1;
    private int pendingLine = -1;

    // Listeners retained so dispose() can detach them (the text area outlives this manager when
    // the linter is re-initialised).
    private MouseMotionAdapter mouseMotionListener;
    private java.awt.event.MouseAdapter mouseListener;

    protected DwellTooltipSupport(RSyntaxTextArea textArea, int boxLayoutAxis, Color background) {
        this.textArea = textArea;

        tooltipWindow = new JWindow();
        tooltipWindow.setType(Window.Type.POPUP);
        tooltipWindow.setFocusableWindowState(false);

        tooltipPanel = new JPanel();
        tooltipPanel.setLayout(new BoxLayout(tooltipPanel, boxLayoutAxis));
        tooltipPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        tooltipPanel.setBackground(background);
        tooltipWindow.add(tooltipPanel);

        installListeners();
    }

    /**
     * Cheap hot-path hit test, run on every mouse move: returns a target if one sits under the
     * pointer, else {@code null}. Must stay inexpensive — the costly work belongs in
     * {@link #populate}.
     */
    protected abstract Target probe(Point point);

    /**
     * Builds the tooltip content for {@code target} into {@link #tooltipPanel}, returning true if
     * there is something to show. Runs inside the dwell timer, so any expensive analysis here
     * never touches the raw mouse-move path; returning false aborts the show.
     */
    protected abstract boolean populate(Target target);

    private void installListeners() {
        mouseMotionListener = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Target target = probe(e.getPoint());
                if (target != null) {
                    // Pointer still over the line whose tooltip is showing: keep it visible.
                    if (target.line() == currentlyDisplayedLine) {
                        stopTimer(hideTimer);
                        return;
                    }
                    // A show for this same line is already scheduled: let it fire.
                    if (target.line() == pendingLine && showTimer != null && showTimer.isRunning()) {
                        return;
                    }
                    // Moved onto a different target line: hide anything shown, then schedule a
                    // single build+show after the dwell delay (cancelled if the pointer moves on).
                    stopTimer(hideTimer);
                    if (currentlyDisplayedLine != -1) {
                        hideCustomTooltip();
                    }
                    scheduleShow(target, e.getLocationOnScreen());
                } else {
                    // Moved off any target: cancel any pending show, hide after a short delay to
                    // avoid flicker. Guard against restarting the hide on every event.
                    stopTimer(showTimer);
                    pendingLine = -1;
                    if (currentlyDisplayedLine != -1 && (hideTimer == null || !hideTimer.isRunning())) {
                        hideTimer = new Timer(HIDE_DELAY_MS, evt -> hideCustomTooltip());
                        hideTimer.setRepeats(false);
                        hideTimer.start();
                    }
                }
            }
        };
        textArea.addMouseMotionListener(mouseMotionListener);

        mouseListener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hideCustomTooltip();
            }
        };
        textArea.addMouseListener(mouseListener);
    }

    private void scheduleShow(Target target, Point screenLocation) {
        stopTimer(showTimer);
        pendingLine = target.line();
        showTimer = new Timer(SHOW_DELAY_MS, evt -> {
            pendingLine = -1;
            if (populate(target)) {
                showAt(screenLocation);
                currentlyDisplayedLine = target.line();
            }
        });
        showTimer.setRepeats(false);
        showTimer.start();
    }

    private void showAt(Point screenLocation) {
        tooltipWindow.pack();

        // Offset the tooltip from the pointer, then flip it to the other side if it would spill
        // off the monitor the pointer is on.
        Rectangle screen = screenBoundsFor(screenLocation);
        Dimension tip = tooltipWindow.getSize();
        int x = screenLocation.x + 10;
        int y = screenLocation.y + 20;
        if (x + tip.width > screen.x + screen.width) {
            x = screenLocation.x - tip.width - 10;
        }
        if (y + tip.height > screen.y + screen.height) {
            y = screenLocation.y - tip.height - 10;
        }
        tooltipWindow.setLocation(x, y);
        tooltipWindow.setVisible(true);
    }

    /**
     * Bounds of the monitor containing {@code screenLocation}, falling back to the primary
     * monitor's bounds. Using the pointer's monitor (not {@code Toolkit.getScreenSize()}) is what
     * makes the off-screen clamp correct on multi-monitor setups.
     */
    private static Rectangle screenBoundsFor(Point screenLocation) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : ge.getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            if (bounds.contains(screenLocation)) {
                return bounds;
            }
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private void hideCustomTooltip() {
        if (tooltipWindow != null) {
            tooltipWindow.setVisible(false);
        }
        currentlyDisplayedLine = -1;
    }

    protected static void stopTimer(Timer timer) {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
    }

    /**
     * Clean up: stop timers, close the tooltip window, and detach the mouse listeners added to
     * the shared text area in the constructor. Subclasses that acquire their own resources may
     * override, but must call {@code super.dispose()}.
     */
    public void dispose() {
        stopTimer(hideTimer);
        stopTimer(showTimer);
        if (tooltipWindow != null) {
            tooltipWindow.dispose();
        }
        if (mouseMotionListener != null) {
            textArea.removeMouseMotionListener(mouseMotionListener);
            mouseMotionListener = null;
        }
        if (mouseListener != null) {
            textArea.removeMouseListener(mouseListener);
            mouseListener = null;
        }
    }
}
