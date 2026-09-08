package com.kalix.ide.dataview;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.io.CsvLineStylist;
import com.kalix.ide.managers.FontManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;
import com.kalix.ide.themes.ThemePreferences;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The ground-truth projection of a {@link DataViewSession}: a read-only,
 * virtual text view rendering the file's <em>physical lines</em> exactly as
 * they sit on disk — delimiters, quoting, scientific notation, everything.
 * The glyphs are never altered; the only interpretation is colour, which
 * declares the sniffed dialect's reading (delimiters, header row, the date
 * column, missing markers) via the shared {@link CsvLineStylist}, using the
 * same syntax-theme slots as the editors below the gate.
 *
 * <p>Virtual in the same way as the table: only visible lines are ever held or
 * drawn; an unloaded line paints as blank for a frame while its block is
 * fetched in the background, and the arrival repaints. The component's
 * preferred height grows live while the file is being indexed, extending the
 * scrollbar as the count streams in. A 1GB file scrolls like a 10KB one.
 *
 * <p>Interaction is deliberately viewer-shaped for V1: whole-line selection by
 * click-drag and copy (the platform copy shortcut). Copy fetches the selected
 * range on a background thread — never the EDT — and is capped at
 * {@value #MAX_COPY_LINES} lines.
 */
public final class VirtualTextArea extends JComponent implements Scrollable {

    static final int MAX_COPY_LINES = 100_000;
    private static final int H_PAD = 8;
    private static final int MIN_WIDTH = 400;

    /** Non-final: replaced with a fresh session after the file is rebuilt from disk. */
    private DataViewSession session;

    /** Selected line range; -1 anchor = no selection. */
    private long selectionAnchor = -1;
    private long selectionCaret = -1;

    private int maxLineWidthPx = 0;
    private boolean widthRevalidateQueued = false;

    private Color background;
    private Color foreground;
    private Color selectionBackground;
    private Color selectionForeground;

    // Dialect-aware colouring: same six syntax-theme slots as the RSTA
    // editors, so above- and below-gate text views match. Values keep the
    // plain foreground — only the structure is coloured.
    private CsvLineStylist stylist;
    private boolean headerOnLineZero;
    // Extended-header lines rendered above the indexed data region (empty for
    // plain CSV): the ground-truth view must show the whole file, and for
    // .res.csv the indexes deliberately cover only the region past EOH.
    private List<String> headerLines = List.of();
    private Color delimiterColor;
    private Color headerColor;
    private Color dateColor;
    private Color missingColor;

    /** Row header to revalidate when geometry changes (line count, font size). */
    private JComponent gutter;
    /** Opens the data views' unified Find (the editor's would search a hidden empty buffer). */
    private Runnable findHandler;
    /** Repeats the last find; the Boolean is the direction (true = forward). */
    private java.util.function.Consumer<Boolean> findAgainHandler;

    public VirtualTextArea(DataViewSession session) {
        this.session = session;
        adoptDialect(session);
        // The editor's font-size preference, like every text surface (a hardcoded
        // 13 made this view visibly larger than the editors it sits beside).
        setFont(FontManager.getMonospaceFont(PreferenceKeys.EDITOR_FONT_SIZE.get()));
        registerInstance(this);
        setOpaque(true);
        setFocusable(true);
        resolveColors();

        registerSessionListener();

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                long line = lineAt(e.getY());
                if (e.isShiftDown() && selectionAnchor >= 0) {
                    selectionCaret = line; // extend, editor-style — never restart
                } else {
                    selectionAnchor = line;
                    selectionCaret = line;
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                selectionCaret = lineAt(e.getY());
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, AppShortcut.menuMask()), "copy-lines");
        getActionMap().put("copy-lines", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelection();
            }
        });
        getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_A, AppShortcut.menuMask()), "select-all");
        getActionMap().put("select-all", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAll();
            }
        });
        // Find belongs to the data views (the editor's Find would search a hidden
        // empty buffer); WHEN_FOCUSED bindings also outrank the Edit-menu
        // accelerators while this view has focus.
        getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, AppShortcut.menuMask()), "data-find");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "data-find-next");
        getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "data-find-previous");
        getActionMap().put("data-find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (findHandler != null) {
                    findHandler.run();
                }
            }
        });
        getActionMap().put("data-find-next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (findAgainHandler != null) {
                    findAgainHandler.accept(true);
                }
            }
        });
        getActionMap().put("data-find-previous", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (findAgainHandler != null) {
                    findAgainHandler.accept(false);
                }
            }
        });

        // Read-only is announced, never silently enforced: a plain keystroke beeps.
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if ((e.getModifiersEx() & AppShortcut.menuMask()) == 0) {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
        });

        JPopupMenu menu = new JPopupMenu();
        JMenuItem find = new JMenuItem("Find…");
        find.addActionListener(e -> {
            if (findHandler != null) {
                findHandler.run();
            }
        });
        menu.add(find);
        menu.addSeparator();
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> copySelection());
        menu.add(copy);
        JMenuItem selectAll = new JMenuItem("Select all");
        selectAll.addActionListener(e -> selectAll());
        menu.add(selectAll);
        setComponentPopupMenu(menu);
    }

    /** Wires the data views' Find into this text side (dialog open + F3 repeats). */
    public void setFindHandlers(Runnable find, java.util.function.Consumer<Boolean> findAgain) {
        this.findHandler = find;
        this.findAgainHandler = findAgain;
    }

    /** The row header showing line numbers; revalidated when geometry changes. */
    public void attachGutter(JComponent gutterComponent) {
        this.gutter = gutterComponent;
    }

    private void selectAll() {
        long count = totalLines();
        if (count > 0) {
            selectLines(0, count - 1);
        }
    }

    /** Applies a new editor font size (the global preference push reaches here too). */
    public void updateFontSize(int fontSize) {
        setFont(FontManager.getMonospaceFont(fontSize));
        maxLineWidthPx = 0; // line widths re-measure under the new font
        refreshGeometry();
    }

    private void refreshGeometry() {
        revalidate();
        if (gutter != null) {
            gutter.revalidate();
            gutter.repaint();
        }
        repaint();
    }

    // --- Global preference pushes (weak registry, like the editor components') ---

    private static final List<WeakReference<VirtualTextArea>> instances = new ArrayList<>();

    private static void registerInstance(VirtualTextArea area) {
        synchronized (instances) {
            instances.add(new WeakReference<>(area));
        }
    }

    /** Pushes a font-size preference change to every live instance. */
    public static void updateAllFontSizes(int fontSize) {
        forEachInstance(area -> area.updateFontSize(fontSize));
    }

    /** Pushes a syntax-theme preference change (colours re-resolve) to every live instance. */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme theme) {
        forEachInstance(area -> {
            area.resolveColors();
            area.repaint();
        });
    }

    private static void forEachInstance(java.util.function.Consumer<VirtualTextArea> action) {
        synchronized (instances) {
            Iterator<WeakReference<VirtualTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                VirtualTextArea area = iterator.next().get();
                if (area == null) {
                    iterator.remove();
                } else {
                    action.accept(area);
                }
            }
        }
    }

    private void registerSessionListener() {
        session.addListener(new DataViewSession.Listener() {
            @Override
            public void onProgress(long rows, long lines, long indexedBytes, long totalBytes, boolean complete) {
                refreshGeometry(); // preferred height (and the gutter's digit count) grew
            }

            @Override
            public void onLineBlockLoaded(long firstLine, int count) {
                repaint();
            }
        });
    }

    /**
     * Swaps in a fresh session after the file was rebuilt from disk. EDT only;
     * the caller closes the old session (which drops its listeners) afterwards.
     */
    public void replaceSession(DataViewSession fresh) {
        this.session = fresh;
        adoptDialect(fresh);
        registerSessionListener();
        refreshGeometry();
    }

    private void adoptDialect(DataViewSession target) {
        CsvDialect dialect = target.dialect();
        stylist = new CsvLineStylist(dialect.delimiter(), dialect.quote());
        headerOnLineZero = target.headerRowInData();
        headerLines = target.headerTextLines();
    }

    /** Header lines + indexed data-region lines: the whole file — package-private for the gutter. */
    long totalLines() {
        return headerLines.size() + session.lineCount();
    }

    /** Scrolls the given DATA-REGION line into view (offset past any header lines) and selects it. EDT only. */
    public void showLine(long line) {
        long count = totalLines();
        if (count == 0) {
            return;
        }
        long target = Math.max(0, Math.min(line + headerLines.size(), count - 1));
        selectLines(target, target);
        int lineHeight = lineHeight();
        // Clamped like getPreferredSize: past the int-pixel ceiling (~100M+ lines)
        // this lands at the scrollable end rather than wrapping negative.
        long y = Math.max(0, (target - 2) * (long) lineHeight);
        scrollRectToVisible(new Rectangle(
            0, (int) Math.min(y, Integer.MAX_VALUE - 4096L), 1, lineHeight * 5));
        repaint();
    }

    /** Re-resolves theme colours; runs on every LaF switch via updateComponentTreeUI. */
    @Override
    public void updateUI() {
        super.updateUI();
        resolveColors();
        repaint();
    }

    private void resolveColors() {
        background = orElse(UIManager.getColor("TextArea.background"), Color.WHITE);
        foreground = orElse(UIManager.getColor("TextArea.foreground"), Color.BLACK);
        selectionBackground = orElse(UIManager.getColor("TextArea.selectionBackground"), new Color(51, 153, 255));
        selectionForeground = orElse(UIManager.getColor("TextArea.selectionForeground"), Color.WHITE);

        SyntaxTheme.Theme syntax;
        try {
            syntax = ThemePreferences.effectiveSyntaxTheme();
        } catch (Exception e) {
            syntax = SyntaxTheme.Theme.LIGHT;
        }
        delimiterColor = syntax.getWhitespaceColor(); // the recede slot: structure, barely there
        headerColor = syntax.getReservedWordColor();
        dateColor = syntax.getStringColor();
        missingColor = syntax.getCommentColor();
    }

    private static Color orElse(Color color, Color fallback) {
        return color != null ? color : fallback;
    }

    // --- Geometry ---

    int lineHeight() { // package-private: the gutter shares the row geometry
        return getFontMetrics(getFont()).getHeight();
    }

    long lineAt(int y) {
        long count = totalLines();
        if (count == 0) {
            return -1;
        }
        return Math.max(0, Math.min(y / lineHeight(), count - 1));
    }

    @Override
    public Dimension getPreferredSize() {
        long height = totalLines() * lineHeight();
        return new Dimension(
            Math.max(MIN_WIDTH, maxLineWidthPx + 2 * H_PAD),
            (int) Math.min(height, Integer.MAX_VALUE));
    }

    // --- Painting ---

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(background);
        Rectangle clip = g.getClipBounds();
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        FontMetrics fm = getFontMetrics(getFont());
        int lineHeight = fm.getHeight();
        long first = Math.max(0, clip.y / lineHeight);
        long last = Math.min(totalLines() - 1, (long) (clip.y + clip.height) / lineHeight + 1);
        long selStart = selectionStart();
        long selEnd = selectionEndExclusive();

        g.setFont(getFont());
        for (long line = first; line <= last; line++) {
            int y = (int) (line * lineHeight);
            boolean selected = line >= selStart && line < selEnd;
            if (selected) {
                g.setColor(selectionBackground);
                g.fillRect(0, y, getWidth(), lineHeight);
            }
            String text = line < headerLines.size()
                ? headerLines.get((int) line)
                : session.lineIfLoaded(line - headerLines.size());
            if (text == null) {
                session.requestLine(line - headerLines.size()); // blank for a frame; repaints on arrival
                continue;
            }
            if (selected) {
                g.setColor(selectionForeground);
                g.drawString(text, H_PAD, y + fm.getAscent());
            } else {
                paintStyledLine(g, fm, text, line, y);
            }
            trackLineWidth(fm.stringWidth(text));
        }
    }

    /** Draws one line span-by-span in the dialect's colours (selection paints flat). */
    private void paintStyledLine(Graphics g, FontMetrics fm, String text, long line, int y) {
        int x = H_PAD;
        int baseline = y + fm.getAscent();
        for (CsvLineStylist.Span span : stylist.style(text, headerOnLineZero && line == headerLines.size())) {
            String part = text.substring(span.start(), span.endExclusive());
            g.setColor(colorFor(span.role()));
            g.drawString(part, x, baseline);
            x += fm.stringWidth(part);
        }
    }

    private Color colorFor(CsvLineStylist.Role role) {
        return switch (role) {
            case DELIMITER -> delimiterColor;
            case HEADER, MARKER -> headerColor;
            case DATE_AXIS -> dateColor;
            case MISSING -> missingColor;
            case VALUE -> foreground; // ground truth stays plain; only structure is coloured
        };
    }

    /** Widens the preferred size as longer lines are seen (coalesced revalidate). */
    private void trackLineWidth(int widthPx) {
        if (widthPx > maxLineWidthPx) {
            maxLineWidthPx = widthPx;
            if (!widthRevalidateQueued) {
                widthRevalidateQueued = true;
                SwingUtilities.invokeLater(() -> {
                    widthRevalidateQueued = false;
                    revalidate();
                });
            }
        }
    }

    // --- Selection & copy ---

    long selectionStart() {
        return selectionAnchor < 0 ? -1 : Math.min(selectionAnchor, selectionCaret);
    }

    /** One past the last selected line; -1 when there is no selection. */
    long selectionEndExclusive() {
        return selectionAnchor < 0 ? -1 : Math.max(selectionAnchor, selectionCaret) + 1;
    }

    /** Package-private, for tests and future programmatic selection. */
    void selectLines(long fromInclusive, long toInclusive) {
        selectionAnchor = fromInclusive;
        selectionCaret = toInclusive;
        repaint();
    }

    /**
     * The selected lines joined with {@code \n}. Blocking I/O — background
     * threads only; {@code null} when nothing is selected or the selection
     * exceeds {@link #MAX_COPY_LINES}.
     */
    String selectedTextBlocking() {
        long start = selectionStart();
        long end = selectionEndExclusive();
        if (start < 0 || end - start > MAX_COPY_LINES) {
            return null;
        }
        // Stitch header lines (in memory) and data-region lines (fetched) so a
        // selection spanning the boundary copies seamlessly.
        int headerCount = headerLines.size();
        List<String> lines = new java.util.ArrayList<>();
        for (long i = start; i < Math.min(end, headerCount); i++) {
            lines.add(headerLines.get((int) i));
        }
        if (end > headerCount) {
            lines.addAll(session.linesBlocking(Math.max(0, start - headerCount), end - headerCount));
        }
        return String.join("\n", lines);
    }

    private void copySelection() {
        long start = selectionStart();
        long end = selectionEndExclusive();
        if (start < 0) {
            return;
        }
        if (end - start > MAX_COPY_LINES) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        Thread copier = new Thread(() -> {
            String text = selectedTextBlocking();
            if (text != null) {
                SwingUtilities.invokeLater(() ->
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null));
            }
        }, "kalix-dataview-copy");
        copier.setDaemon(true);
        copier.start();
    }

    // --- Scrollable ---

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return lineHeight();
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
            ? Math.max(lineHeight(), visibleRect.height - lineHeight())
            : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return false; // long lines scroll horizontally
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
