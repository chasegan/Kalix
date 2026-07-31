package com.kalix.ide.filedialog;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.ThemeUtils;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Kalix's own file dialog — the replacement for {@code JFileChooser} across the IDE.
 *
 * <p>Two commitments define it. <b>Performance:</b> every listing is one batched background
 * enumeration ({@link DirectoryLister}), rendered with zero per-entry I/O — so network
 * drives behave like local ones instead of crawling through N+1 stats. <b>Language:</b>
 * entries render in the Kalix file visual language ({@code FileVisuals},
 * {@code manifestos/file-tree-colour.md}), so model files are as recognisable in the dialog
 * as they are in the project tree.
 *
 * <p>Layout: sidebar (pins / places / volumes / recents) on the left; a breadcrumb bar with
 * navigation, folder-action and view toggles on top; the listing centre — either the classic
 * list view or macOS-style Miller columns, the user's choice persisting across dialogs; one
 * name-or-path text field plus buttons in the footer.
 *
 * <p>Usage:
 * <pre>{@code
 * Optional<File> file = KalixFileDialog.openFile(window)
 *     .title("Open Kalix Model")
 *     .startIn(currentModelDir)
 *     .filters(FileDialogFilter.of("Kalix models (*.ini)", "ini"))
 *     .show();
 * }</pre>
 *
 * <p>Open dialogs can take several files at once with {@code .multiSelect()}, which is
 * shown with {@link #showAll()} rather than {@link #show()}:
 * <pre>{@code
 * List<File> files = KalixFileDialog.openFile(window)
 *     .multiSelect()
 *     .filters(FileDialogFilter.of("Source results (*.res.csv)", "res.csv"))
 *     .showAll();
 * }</pre>
 *
 * <p>Save dialogs show no filter combo: the typed name is taken verbatim, so a caller that
 * supports several formats reads the format back off the chosen file's extension rather
 * than off a filter selection.
 */
public final class KalixFileDialog implements FileViewHost {

    /** What the dialog is for; drives OK-button semantics and selectability. */
    public enum Mode {OPEN_FILE, SAVE_FILE, CHOOSE_FOLDER}

    // --- Builder state ---
    private final Mode mode;
    private final Window owner;
    private String title;
    private Path startDir;
    private final List<FileDialogFilter> filters = new ArrayList<>();
    private String suggestedName = "";
    private boolean multiSelect;

    // --- UI state ---
    private JDialog dialog;
    private final DirectoryLister lister = new DirectoryLister();
    private ListFileView listView;
    private ColumnsFileView columnsView;
    private FileDialogSidebar sidebar;
    private JPanel viewCards;
    private JPanel breadcrumb;
    private JToggleButton pinToggle;
    private JToggleButton hiddenToggle;
    private JComboBox<FileDialogFilter> filterCombo;
    private JLabel statusLabel;
    private JButton okButton;

    private JButton backButton;
    private JButton forwardButton;
    /**
     * The dialog's single text field: shows the selected entry's <em>name</em> while the
     * user works the views, doubles as the save-as name in save mode, and accepts typed or
     * pasted paths (Enter navigates). One box, standard dialog behaviour.
     */
    private JTextField pathField;
    /** Save mode: the file name to restore into the field after a pasted-path navigation. */
    private String pendingSaveName = "";

    private Path currentDir;
    private FsEntry selectedEntry;
    private boolean columnsMode;
    /** The accepted files: empty when cancelled, one entry unless multi-select is on. */
    private final List<File> results = new ArrayList<>();

    // Back/forward history of visited directories, same idiom as the editor's navigation.
    private final List<Path> visited = new ArrayList<>();
    private int visitIndex = -1;
    private boolean traversingHistory;

    /** Recent folders shared from the main window's Recent Files/Folders tracking. */
    private static java.util.function.Supplier<List<Path>> recentFoldersProvider;

    /** Notified after an in-dialog rename, so open editor tabs can re-point (may be null). */
    private static java.util.function.BiConsumer<Path, Path> pathMovedListener;

    /**
     * Registers the app-wide source of the sidebar's Recent section. Called once at startup
     * with a supplier backed by the main window's Recent Files / Recent Folders managers, so
     * the dialogs and the File menu share one navigation memory.
     */
    public static void setRecentFoldersProvider(java.util.function.Supplier<List<Path>> provider) {
        recentFoldersProvider = provider;
    }

    /**
     * Registers the app-wide listener for in-dialog renames (old path, new path), so
     * documents open in editor tabs survive being renamed from inside a file dialog —
     * the same re-pointing the project tree's rename does.
     */
    public static void setPathMovedListener(java.util.function.BiConsumer<Path, Path> listener) {
        pathMovedListener = listener;
    }

    private KalixFileDialog(Mode mode, java.awt.Component owner) {
        this.mode = mode;
        // Call sites hold whatever they hold — a frame, a panel, a table. Resolving the
        // window here spares every one of them the getWindowAncestor dance.
        this.owner = owner instanceof Window window
            ? window
            : owner != null ? javax.swing.SwingUtilities.getWindowAncestor(owner) : null;
        this.title = switch (mode) {
            case OPEN_FILE -> "Open";
            case SAVE_FILE -> "Save";
            case CHOOSE_FOLDER -> "Choose Folder";
        };
    }

    // --- Builder API ---

    /** @param owner any component in the owning window (or the window itself); may be null */
    public static KalixFileDialog openFile(java.awt.Component owner) {
        return new KalixFileDialog(Mode.OPEN_FILE, owner);
    }

    /** @param owner any component in the owning window (or the window itself); may be null */
    public static KalixFileDialog saveFile(java.awt.Component owner) {
        return new KalixFileDialog(Mode.SAVE_FILE, owner);
    }

    /** @param owner any component in the owning window (or the window itself); may be null */
    public static KalixFileDialog chooseFolder(java.awt.Component owner) {
        return new KalixFileDialog(Mode.CHOOSE_FOLDER, owner);
    }

    public KalixFileDialog title(String title) {
        this.title = title;
        return this;
    }

    /** Starting directory; a file resolves to its parent. Null is ignored (last-used wins). */
    public KalixFileDialog startIn(File fileOrDir) {
        if (fileOrDir != null) {
            File dir = fileOrDir.isDirectory() ? fileOrDir : fileOrDir.getParentFile();
            if (dir != null && dir.isDirectory()) {
                this.startDir = dir.toPath();
            }
        }
        return this;
    }

    public KalixFileDialog filters(FileDialogFilter... specs) {
        filters.addAll(List.of(specs));
        return this;
    }

    /** Pre-filled file name for save dialogs. */
    public KalixFileDialog suggestedName(String name) {
        if (name != null) {
            this.suggestedName = name;
        }
        return this;
    }

    /**
     * Lets the user pick several files at once. Open mode only — saving and folder
     * choosing each have exactly one answer by definition. Pair with {@link #showAll()};
     * {@link #show()} rejects a multi-select dialog rather than silently dropping picks.
     */
    public KalixFileDialog multiSelect() {
        if (mode != Mode.OPEN_FILE) {
            throw new IllegalStateException("multiSelect() applies to open dialogs only, not " + mode);
        }
        this.multiSelect = true;
        return this;
    }

    /**
     * Shows the modal dialog and blocks until it closes.
     *
     * @return the chosen file/folder, or empty if cancelled
     * @throws IllegalStateException if {@link #multiSelect()} was requested — use
     *     {@link #showAll()}, which can express more than one answer
     */
    public Optional<File> show() {
        if (multiSelect) {
            throw new IllegalStateException("multiSelect() dialogs must be shown with showAll()");
        }
        return runDialog().stream().findFirst();
    }

    /**
     * Shows the modal dialog and blocks until it closes. The multi-select counterpart of
     * {@link #show()}; works in single-select open mode too, yielding at most one file.
     *
     * @return the chosen files in view order, or empty if cancelled
     */
    public List<File> showAll() {
        return runDialog();
    }

    private List<File> runDialog() {
        buildUi();
        Path start = startDir != null ? startDir : FileDialogHistory.lastDirectory();
        if (start == null || !Files.isDirectory(start)) {
            start = Path.of(System.getProperty("user.home"));
        }
        navigateTo(start);
        if (mode == Mode.SAVE_FILE) {
            pendingSaveName = suggestedName;
            pathField.setText(suggestedName);
            pathField.requestFocusInWindow();
            int dot = suggestedName.lastIndexOf('.');
            pathField.select(0, dot > 0 ? dot : suggestedName.length());
        }
        dialog.setVisible(true); // modal; blocks until accept/cancel disposes
        lister.dispose();
        return List.copyOf(results);
    }

    // --- FileViewHost ---

    @Override
    public DirectoryLister lister() {
        return lister;
    }

    @Override
    public boolean showHidden() {
        return hiddenToggle.isSelected();
    }

    @Override
    public boolean passesFilter(FsEntry entry) {
        return activeFilter().accepts(entry);
    }

    @Override
    public boolean directoriesOnly() {
        return mode == Mode.CHOOSE_FOLDER;
    }

    @Override
    public boolean allowsMultiSelect() {
        return multiSelect;
    }

    @Override
    public void directoryShown(Path dir) {
        currentDir = dir;
        clearStatus();
        rebuildBreadcrumb();
        pinToggle.setSelected(FileDialogSidebar.isPinned(dir));
        recordVisit(dir);
        reflectNavigation();
        updateOkEnablement();
    }

    @Override
    public void selectionChanged(FsEntry entry) {
        selectedEntry = entry;
        if (mode == Mode.SAVE_FILE && entry != null && !entry.directory()) {
            pendingSaveName = entry.name();
        }
        // The field shows the selected entry's NAME (standard dialog behaviour); it never
        // updates while the user is typing in it. Under a multi-selection no single name
        // is the truth, so it reports the count instead.
        if (pathField != null && !pathField.isFocusOwner() && entry != null) {
            int count = multiSelect ? selectedFiles().size() : 1;
            pathField.setText(count > 1 ? count + " files selected" : entry.name());
        }
        updateOkEnablement();
    }

    @Override
    public void entryActivated(FsEntry entry) {
        if (mode == Mode.SAVE_FILE) {
            pendingSaveName = entry.name();
            pathField.setText(entry.name());
            return;
        }
        selectedEntry = entry;
        accept();
    }

    @Override
    public void listingFailed(String message) {
        showStatus(message);
    }

    @Override
    public void showEntryContextMenu(FsEntry entry, java.awt.Component invoker, int x, int y) {
        // Same grammar as the project tree's menu (context-menu-style §1 skeleton: create,
        // then modify, then the destructive item isolated with its landmark icon), scoped
        // to what makes sense mid-dialog.
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem newFolder = new javax.swing.JMenuItem("New folder…");
        newFolder.addActionListener(e -> createNewFolderIn(
            entry.directory() ? entry.path() : entry.path().getParent()));
        menu.add(newFolder);
        menu.addSeparator();
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename…");
        rename.addActionListener(e -> {
            Path renamedTo = EntryOperations.rename(dialog, entry);
            if (renamedTo != null) {
                if (pathMovedListener != null) {
                    pathMovedListener.accept(entry.path(), renamedTo);
                }
                refreshAfterMutation(entry);
            }
        });
        menu.add(rename);
        menu.addSeparator();
        javax.swing.JMenuItem delete =
            new javax.swing.JMenuItem("Delete", com.kalix.ide.icons.MenuIcons.delete());
        delete.addActionListener(e -> {
            if (EntryOperations.delete(dialog, entry)) {
                refreshAfterMutation(entry);
            }
        });
        menu.add(delete);
        menu.show(invoker, x, y);
    }

    @Override
    public void showContainerContextMenu(Path dir, java.awt.Component invoker, int x, int y) {
        // Empty space acts on the containing folder (context-menu-style §4): the create
        // verb, then the view group. Identity-changing/destructive verbs never appear for
        // the container the user is standing in.
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem newFolder = new javax.swing.JMenuItem("New folder…");
        newFolder.addActionListener(e -> createNewFolderIn(dir));
        menu.add(newFolder);
        menu.addSeparator();
        javax.swing.JMenuItem refresh = new javax.swing.JMenuItem("Refresh");
        refresh.addActionListener(e -> {
            if (columnsMode) {
                columnsView.reloadColumn(dir, null);
            } else {
                listView.refresh();
            }
        });
        menu.add(refresh);
        menu.show(invoker, x, y);
    }

    /**
     * Reflects an in-dialog mutation (rename/delete) back into the views. The dialog has no
     * filesystem watcher, so what it changes it must also redraw.
     */
    private void refreshAfterMutation(FsEntry entry) {
        selectedEntry = null;
        Path parent = entry.path().getParent();
        if (columnsMode) {
            columnsView.reloadColumn(parent, null);
            if (entry.directory() && parent != null) {
                navigateTo(parent); // collapse any trail rooted in the renamed/deleted folder
            }
        } else {
            listView.refresh();
        }
        updateOkEnablement();
    }

    // --- UI assembly ---

    private void buildUi() {
        dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        listView = new ListFileView(this);
        columnsView = new ColumnsFileView(this);
        sidebar = new FileDialogSidebar(this::navigateFromSidebar, recentFoldersProvider);

        viewCards = new JPanel(new CardLayout());
        viewCards.add(listView.component(), "list");
        viewCards.add(columnsView.component(), "columns");
        columnsMode = "columns".equals(PreferenceKeys.FILE_DIALOG_VIEW.get());

        // The footer lives inside the split's right side, so the path field and buttons
        // align with the listing area's left edge (the sidebar frames the full height).
        JPanel listingRegion = new JPanel(new BorderLayout());
        listingRegion.add(viewCards, BorderLayout.CENTER);
        listingRegion.add(buildFooter(), BorderLayout.SOUTH);

        // Adjustable sidebar, width persisted (same split idiom as the main workspace).
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT, sidebar.component(), listingRegion);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setResizeWeight(0.0); // the listing absorbs dialog resizes; the sidebar keeps its width
        split.setDividerLocation(PreferenceKeys.FILE_DIALOG_SIDEBAR_WIDTH.get());
        split.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY,
            e -> PreferenceKeys.FILE_DIALOG_SIDEBAR_WIDTH.set(split.getDividerLocation()));

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildToolbar(), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        dialog.setContentPane(content);

        ((CardLayout) viewCards.getLayout()).show(viewCards, columnsMode ? "columns" : "list");

        // Esc cancels; Enter accepts via the default button.
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancel();
            }
        });

        dialog.setSize(880, 540);
        dialog.setMinimumSize(new Dimension(640, 400));
        dialog.setLocationRelativeTo(owner);
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        breadcrumb = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        breadcrumb.setOpaque(false);

        // Back/forward use the main toolbar's navigation glyphs, so the idiom carries over.
        backButton = iconButton(FontAwesomeSolid.ARROW_LEFT, "Back");
        backButton.addActionListener(e -> goBack());
        forwardButton = iconButton(FontAwesomeSolid.ARROW_RIGHT, "Forward");
        forwardButton.addActionListener(e -> goForward());
        updateNavButtons();

        JButton upButton = iconButton(FontAwesomeSolid.LEVEL_UP_ALT, "Parent folder");
        upButton.addActionListener(e -> {
            Path parent = currentDir != null ? currentDir.getParent() : null;
            if (parent != null) {
                navigateTo(parent);
            }
        });

        JButton newFolderButton = iconButton(FontAwesomeSolid.FOLDER_PLUS, "New folder…");
        newFolderButton.addActionListener(e -> createNewFolderIn(currentDir));

        pinToggle = iconToggle(FontAwesomeSolid.THUMBTACK, "Pin this folder to the sidebar");
        pinToggle.addActionListener(e -> {
            FileDialogSidebar.togglePin(currentDir);
            sidebar.rebuild();
        });

        // One "show hidden files" truth across the app: the dialog reads and writes the
        // same preference as the main window's project tree (View menu / tree context
        // menu), so the two never disagree at next open.
        hiddenToggle = iconToggle(FontAwesomeSolid.EYE_SLASH, "Show hidden files");
        hiddenToggle.setSelected(PreferenceKeys.TREE_SHOW_HIDDEN_FILES.get());
        hiddenToggle.addActionListener(e -> {
            PreferenceKeys.TREE_SHOW_HIDDEN_FILES.set(hiddenToggle.isSelected());
            refilterViews();
        });

        JToggleButton listToggle = iconToggle(FontAwesomeSolid.LIST, "List view");
        JToggleButton columnsToggle = iconToggle(FontAwesomeSolid.COLUMNS, "Columns view");
        ButtonGroup viewGroup = new ButtonGroup();
        viewGroup.add(listToggle);
        viewGroup.add(columnsToggle);
        (columnsMode ? columnsToggle : listToggle).setSelected(true);
        listToggle.addActionListener(e -> switchView(false));
        columnsToggle.addActionListener(e -> switchView(true));

        // All controls sit right of the breadcrumb, in three groups:
        // navigation | folder actions | view.
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(backButton);
        right.add(forwardButton);
        right.add(upButton);
        right.add(Box.createHorizontalStrut(8));
        right.add(newFolderButton);
        right.add(pinToggle);
        right.add(hiddenToggle);
        right.add(Box.createHorizontalStrut(8));
        right.add(listToggle);
        right.add(columnsToggle);

        bar.add(breadcrumb, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildFooter() {
        // Stacked rows, top to bottom: status (collapsed while silent), the save-as name
        // row (save mode only), and the controls row — path field stretching left,
        // filter/buttons right. The whole footer sits right of the sidebar, so the path
        // field's left edge aligns with the listing area.
        javax.swing.Box footer = javax.swing.Box.createVerticalBox();
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));

        statusLabel = new JLabel();
        statusLabel.setVisible(false);
        Color muted = UIManager.getColor("Kalix.tree.mutedForeground");
        if (muted != null) {
            statusLabel.setForeground(muted);
        }
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusRow.add(statusLabel, BorderLayout.CENTER);
        footer.add(statusRow);

        JPanel controlsRow = new JPanel(new BorderLayout(8, 0));
        controlsRow.setOpaque(false);

        // The one text field (see the field's javadoc): selection name, save-as name, and
        // pasteable path entry all in one, standard-dialog style.
        pathField = new JTextField();
        pathField.putClientProperty("JTextField.placeholderText",
            mode == Mode.SAVE_FILE ? "File name, or a path to navigate to" : "Name or path");
        pathField.addActionListener(e -> onPathEntered());
        pathField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateOkEnablement();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateOkEnablement();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateOkEnablement();
            }
        });
        controlsRow.add(pathField, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        if (!filters.isEmpty() && mode == Mode.OPEN_FILE) {
            List<FileDialogFilter> all = new ArrayList<>(filters);
            all.add(FileDialogFilter.ALL_FILES);
            filterCombo = new JComboBox<>(all.toArray(new FileDialogFilter[0]));
            // Filtering starts OFF: all files visible, with the specific filters one click
            // away. Modellers live among mixed inputs/outputs; hiding everything but .ini
            // by default made the folder look emptier than it is.
            filterCombo.setSelectedIndex(all.size() - 1);
            filterCombo.addActionListener(e -> refilterViews());
            right.add(filterCombo);
            right.add(Box.createHorizontalStrut(6));
        }
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> cancel());
        okButton = new JButton(switch (mode) {
            case OPEN_FILE -> "Open";
            case SAVE_FILE -> "Save";
            case CHOOSE_FOLDER -> "Choose";
        });
        okButton.addActionListener(e -> accept());
        right.add(cancelButton);
        right.add(okButton);
        controlsRow.add(right, BorderLayout.EAST);
        footer.add(controlsRow);

        return footer;
    }

    /**
     * Enter in the field. Text resolving (against the current folder, so bare names and
     * relative paths work too) to an existing directory navigates there; to an existing
     * file, selects it in its parent — focus then moves to the OK button so Enter again
     * accepts. In save mode a bare non-path name means "save as this" and accepts
     * directly. Anything else changes nothing and keeps focus in the field, with the
     * reason in the status line.
     */
    private void onPathEntered() {
        String text = pathField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        Path typed = resolveTyped(text);
        if (typed != null && Files.isDirectory(typed)) {
            navigateTo(typed);
            if (mode == Mode.SAVE_FILE) {
                pathField.setText(pendingSaveName); // back to being the name box
            }
            if (okButton.isEnabled()) {
                okButton.requestFocusInWindow();
            }
        } else if (typed != null && Files.isRegularFile(typed) && typed.getParent() != null) {
            navigateTo(typed.getParent());
            String name = typed.getFileName().toString();
            if (columnsMode) {
                columnsView.selectName(name);
            } else {
                listView.selectName(name);
            }
            if (mode == Mode.SAVE_FILE) {
                pendingSaveName = name;
                pathField.setText(name);
            }
            if (okButton.isEnabled()) {
                okButton.requestFocusInWindow();
            }
        } else if (mode == Mode.SAVE_FILE && !looksLikePath(text)) {
            accept(); // a fresh file name: Enter means save
        } else {
            showStatus("Path not found: " + text);
            // Focus deliberately stays in the field for correction.
        }
    }

    /** Resolves typed text against the current folder (absolute input wins), or null. */
    private Path resolveTyped(String text) {
        try {
            Path base = currentDir != null ? currentDir : Path.of(System.getProperty("user.home"));
            return base.resolve(text).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException ex) {
            return null;
        }
    }

    private static boolean looksLikePath(String text) {
        return text.contains("/") || text.contains(File.separator);
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setVisible(true);
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.setVisible(false);
    }

    private JButton iconButton(org.kordamp.ikonli.Ikon glyph, String tooltip) {
        JButton button = new JButton(FontIcon.of(glyph, 13,
            ThemeUtils.iconColor(UIManager.getColor("Panel.background"))));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
    }

    private JToggleButton iconToggle(org.kordamp.ikonli.Ikon glyph, String tooltip) {
        JToggleButton button = new JToggleButton(FontIcon.of(glyph, 13,
            ThemeUtils.iconColor(UIManager.getColor("Panel.background"))));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
    }

    // --- Navigation & state ---

    private void navigateTo(Path dir) {
        currentDir = dir;
        selectedEntry = null;
        clearStatus();
        rebuildBreadcrumb();
        pinToggle.setSelected(FileDialogSidebar.isPinned(dir));
        recordVisit(dir);
        reflectNavigation();
        if (columnsMode) {
            columnsView.navigateTo(dir);
        } else {
            listView.navigateTo(dir);
        }
        updateOkEnablement();
    }

    // --- Back / forward history ---

    private void recordVisit(Path dir) {
        if (traversingHistory || dir == null) {
            return;
        }
        if (visitIndex >= 0 && visited.get(visitIndex).equals(dir)) {
            return; // same place (e.g. breadcrumb refresh); not a new hop
        }
        // A new hop truncates any forward history, as in every browser.
        while (visited.size() > visitIndex + 1) {
            visited.remove(visited.size() - 1);
        }
        visited.add(dir);
        visitIndex = visited.size() - 1;
        updateNavButtons();
    }

    private void goBack() {
        if (visitIndex > 0) {
            visitIndex--;
            traverseTo(visited.get(visitIndex));
        }
    }

    private void goForward() {
        if (visitIndex < visited.size() - 1) {
            visitIndex++;
            traverseTo(visited.get(visitIndex));
        }
    }

    private void traverseTo(Path dir) {
        traversingHistory = true;
        try {
            navigateTo(dir);
        } finally {
            traversingHistory = false;
        }
        updateNavButtons();
    }

    private void updateNavButtons() {
        backButton.setEnabled(visitIndex > 0);
        forwardButton.setEnabled(visitIndex < visited.size() - 1);
    }

    private void navigateFromSidebar(Path dir) {
        if (Files.isDirectory(dir)) {
            navigateTo(dir);
        } else {
            showStatus("Folder no longer exists: " + dir);
            sidebar.clearSelection();
        }
    }

    private void switchView(boolean columns) {
        if (columnsMode == columns) {
            return;
        }
        columnsMode = columns;
        PreferenceKeys.FILE_DIALOG_VIEW.set(columns ? "columns" : "list");
        ((CardLayout) viewCards.getLayout()).show(viewCards, columns ? "columns" : "list");
        // Sync the newly shown view to where the user is.
        if (currentDir != null) {
            if (columns) {
                columnsView.navigateTo(currentDir);
                columnsView.focusView();
            } else {
                listView.navigateTo(currentDir);
                listView.focusView();
            }
        }
    }

    private void refilterViews() {
        listView.refilter();
        columnsView.refilter();
    }

    private void rebuildBreadcrumb() {
        breadcrumb.removeAll();
        if (currentDir == null) {
            return;
        }
        List<Path> chain = new ArrayList<>();
        for (Path p = currentDir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            chain.add(0, p);
        }
        int first = Math.max(0, chain.size() - 5);
        if (first > 0) {
            JLabel ellipsis = new JLabel("…");
            ellipsis.setToolTipText(chain.get(first - 1).toString());
            breadcrumb.add(ellipsis);
            breadcrumb.add(separator());
        }
        for (int i = first; i < chain.size(); i++) {
            Path p = chain.get(i);
            Path name = p.getFileName();
            boolean isRoot = name == null; // "/" on Unix, "C:\" on Windows
            JButton segment = new JButton(isRoot ? p.toString() : name.toString());
            segment.putClientProperty("JButton.buttonType", "toolBarButton");
            segment.setFocusable(false);
            segment.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            segment.setContentAreaFilled(false);
            segment.addActionListener(e -> navigateTo(p));
            breadcrumb.add(segment);
            // No separator straight after the root: its own label already IS the path
            // separator ("/"), so adding one produced the "/ / Users" double-slash.
            if (i < chain.size() - 1 && !isRoot) {
                breadcrumb.add(separator());
            }
        }
        breadcrumb.revalidate();
        breadcrumb.repaint();
    }

    /** After navigation the field returns to being a name box: empty, or the save name. */
    private void reflectNavigation() {
        if (pathField == null || pathField.isFocusOwner()) {
            return;
        }
        pathField.setText(mode == Mode.SAVE_FILE ? pendingSaveName : "");
    }

    /** The breadcrumb separator: a path-style slash in the muted tone, full label size. */
    private static JLabel separator() {
        JLabel slash = new JLabel("/");
        Color muted = UIManager.getColor("Kalix.tree.mutedForeground");
        if (muted != null) {
            slash.setForeground(muted);
        }
        return slash;
    }

    private FileDialogFilter activeFilter() {
        if (filterCombo != null && filterCombo.getSelectedItem() instanceof FileDialogFilter f) {
            return f;
        }
        return filters.isEmpty() ? FileDialogFilter.ALL_FILES : filters.get(0);
    }

    private void updateOkEnablement() {
        okButton.setEnabled(switch (mode) {
            case OPEN_FILE -> multiSelect
                ? !selectedFiles().isEmpty()
                : selectedEntry != null && !selectedEntry.directory();
            case SAVE_FILE -> pathField != null && !pathField.getText().isBlank();
            case CHOOSE_FOLDER -> chosenFolder() != null;
        });
    }

    /**
     * The active view's selection, narrowed to actual files. Directories can be caught up
     * in a rubber-band or ctrl-click multi-selection; they are never an open-file answer.
     */
    private List<FsEntry> selectedFiles() {
        List<FsEntry> entries = columnsMode ? columnsView.selectedEntries() : listView.selectedEntries();
        return entries.stream().filter(entry -> !entry.directory()).toList();
    }

    /** Folder mode's answer: the selected directory, else the current one. */
    private Path chosenFolder() {
        if (selectedEntry != null && selectedEntry.directory()) {
            return selectedEntry.path();
        }
        if (columnsMode && columnsView.deepestDirectory() != null) {
            return columnsView.deepestDirectory();
        }
        return currentDir;
    }

    // --- Accept / cancel ---

    private void accept() {
        switch (mode) {
            case OPEN_FILE -> {
                if (multiSelect) {
                    List<FsEntry> chosen = selectedFiles();
                    if (chosen.isEmpty()) {
                        return;
                    }
                    finishAll(chosen.stream().map(entry -> entry.path().toFile()).toList(),
                        chosen.get(0).path().getParent());
                    return;
                }
                if (selectedEntry == null || selectedEntry.directory()) {
                    return;
                }
                finish(selectedEntry.path().toFile(), selectedEntry.path().getParent());
            }
            case SAVE_FILE -> {
                // The typed name is taken verbatim — any extension, or none, is accepted
                // (the suggested name carries the conventional one; the modeller decides).
                // A pasted path resolves too, so saving to an absolute target just works.
                String name = pathField.getText().trim();
                if (name.isEmpty() || currentDir == null) {
                    return;
                }
                Path target = resolveTyped(name);
                if (target == null) {
                    showStatus("Not a valid file name: " + name);
                    return;
                }
                if (Files.isDirectory(target)) {
                    navigateTo(target); // Enter on a folder name browses into it, never saves onto it
                    return;
                }
                if (Files.exists(target)) {
                    int choice = JOptionPane.showConfirmDialog(dialog,
                        "\"" + name + "\" already exists. Replace it?",
                        "Replace File", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (choice != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                finish(target.toFile(), target.getParent() != null ? target.getParent() : currentDir);
            }
            case CHOOSE_FOLDER -> {
                Path folder = chosenFolder();
                if (folder == null) {
                    return;
                }
                finish(folder.toFile(), folder);
            }
        }
    }

    private void finish(File chosen, Path rememberDir) {
        finishAll(List.of(chosen), rememberDir);
    }

    private void finishAll(List<File> chosen, Path rememberDir) {
        results.clear();
        results.addAll(chosen);
        FileDialogHistory.recordAccepted(rememberDir);
        dialog.dispose();
    }

    private void cancel() {
        results.clear();
        dialog.dispose();
    }

    /** Creates a folder inside {@code base} (toolbar: the current folder; context menu:
     *  the clicked folder, or the clicked file's parent) and steps into it. */
    private void createNewFolderIn(Path base) {
        if (base == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(dialog, "New folder name:", "New Folder",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            Path created = Files.createDirectory(base.resolve(name.trim()));
            // No watcher in the dialog: explicitly refresh the parent's (cached) column so
            // the new folder appears in the trail, then step into it.
            if (columnsMode) {
                columnsView.reloadColumn(base, created.getFileName().toString());
            }
            navigateTo(created);
        } catch (IOException ex) {
            showStatus("Could not create folder: " + ex.getMessage());
        }
    }
}
