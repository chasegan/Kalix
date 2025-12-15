package com.kalix.ide.windows;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.components.KalixPlainTextArea;
import com.kalix.ide.components.KalixTextArea;
import com.kalix.ide.themes.SyntaxTheme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal text editor window with Load/Save buttons.
 * Supports both plain text mode and Kalix INI mode with syntax highlighting.
 *
 * Uses KalixIniTextArea or KalixPlainTextArea depending on mode, which provide:
 * <ul>
 *   <li>Monospace font configuration</li>
 *   <li>Windows cursor alignment fix</li>
 *   <li>Theme-aware styling</li>
 * </ul>
 *
 * @see KalixIniTextArea for INI mode
 * @see KalixPlainTextArea for plain text mode
 */
public class MinimalEditorWindow extends JFrame {

    private static final Logger logger = LoggerFactory.getLogger(MinimalEditorWindow.class);

    private static final List<WeakReference<MinimalEditorWindow>> openWindows = new ArrayList<>();
    private static java.util.function.Supplier<File> baseDirectorySupplier;

    private KalixTextArea textArea;
    private RTextScrollPane scrollPane;
    private JButton loadButton;
    private JButton saveButton;
    private File currentFile;
    private final boolean useIniMode;

    /**
     * Sets the base directory supplier for file dialogs.
     *
     * @param supplier supplier that returns the base directory
     */
    public static void setBaseDirectorySupplier(java.util.function.Supplier<File> supplier) {
        baseDirectorySupplier = supplier;
    }

    /**
     * Creates a MinimalEditorWindow with empty content in plain text mode.
     */
    public MinimalEditorWindow() {
        this("", false);
    }

    /**
     * Creates a MinimalEditorWindow with the specified initial text content in plain text mode.
     *
     * @param initialContent the initial text to display
     */
    public MinimalEditorWindow(String initialContent) {
        this(initialContent, false);
    }

    /**
     * Creates a MinimalEditorWindow with the specified initial text content and editor mode.
     *
     * @param initialContent the initial text to display
     * @param useIniMode if true, enables Kalix INI syntax highlighting
     */
    public MinimalEditorWindow(String initialContent, boolean useIniMode) {
        this.useIniMode = useIniMode;
        setupWindow();
        initializeComponents();
        setupLayout();

        if (initialContent != null && !initialContent.isEmpty()) {
            textArea.setText(initialContent);
            textArea.setCaretPosition(0);
        }
    }

    /**
     * Creates a MinimalEditorWindow and loads content from the specified file in plain text mode.
     *
     * @param file the file to load
     */
    public MinimalEditorWindow(File file) {
        this(file, false);
    }

    /**
     * Creates a MinimalEditorWindow and loads content from the specified file.
     *
     * @param file the file to load
     * @param useIniMode if true, enables Kalix INI syntax highlighting
     */
    public MinimalEditorWindow(File file, boolean useIniMode) {
        this.useIniMode = useIniMode;
        setupWindow();
        initializeComponents();
        setupLayout();

        if (file != null && file.exists()) {
            loadFile(file);
        }
    }

    private void setupWindow() {
        setTitle("Kalix - Text Editor");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        synchronized (openWindows) {
            openWindows.add(new WeakReference<>(this));
        }
    }

    private void initializeComponents() {
        // Create appropriate text area based on mode
        if (useIniMode) {
            textArea = new KalixIniTextArea();
        } else {
            textArea = new KalixPlainTextArea();
        }

        scrollPane = new RTextScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        loadButton = new JButton("Load");
        loadButton.addActionListener(e -> showLoadDialog());

        saveButton = new JButton("Save");
        saveButton.addActionListener(e -> showSaveDialog());
    }

    private void setupLayout() {
        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(loadButton);
        buttonPanel.add(saveButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void showLoadDialog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load File");

        if (baseDirectorySupplier != null) {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        if (currentFile != null) {
            fileChooser.setCurrentDirectory(currentFile.getParentFile());
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            loadFile(fileChooser.getSelectedFile());
        }
    }

    private void showSaveDialog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save File");

        if (baseDirectorySupplier != null) {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        if (currentFile != null) {
            fileChooser.setSelectedFile(currentFile);
        }

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            saveFile(fileChooser.getSelectedFile());
        }
    }

    private void loadFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            textArea.setText(content);
            textArea.setCaretPosition(0);
            currentFile = file;
            setTitle("Kalix - Text Editor - " + file.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Failed to load file: " + e.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveFile(File file) {
        try {
            Files.writeString(file.toPath(), textArea.getText());
            currentFile = file;
            setTitle("Kalix - Text Editor - " + file.getName());
            JOptionPane.showMessageDialog(this,
                "File saved successfully",
                "Save Successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Failed to save file: " + e.getMessage(),
                "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Updates the font size for all open MinimalEditorWindow instances.
     *
     * @param fontSize the new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        synchronized (openWindows) {
            Iterator<WeakReference<MinimalEditorWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<MinimalEditorWindow> ref = iterator.next();
                MinimalEditorWindow window = ref.get();

                if (window == null) {
                    iterator.remove();
                } else {
                    window.textArea.updateFontSize(fontSize);
                }
            }
        }
    }

    /**
     * Updates the syntax theme for all open MinimalEditorWindow instances.
     *
     * @param syntaxTheme the new syntax theme to apply
     */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme syntaxTheme) {
        synchronized (openWindows) {
            Iterator<WeakReference<MinimalEditorWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<MinimalEditorWindow> ref = iterator.next();
                MinimalEditorWindow window = ref.get();

                if (window == null) {
                    iterator.remove();
                } else if (window.textArea instanceof KalixIniTextArea) {
                    ((KalixIniTextArea) window.textArea).updateSyntaxTheme(syntaxTheme);
                }
            }
        }
    }

    /**
     * Updates theme-dependent colors for all open MinimalEditorWindow instances.
     */
    public static void updateAllForThemeChange() {
        synchronized (openWindows) {
            Iterator<WeakReference<MinimalEditorWindow>> iterator = openWindows.iterator();
            while (iterator.hasNext()) {
                WeakReference<MinimalEditorWindow> ref = iterator.next();
                MinimalEditorWindow window = ref.get();

                if (window == null) {
                    iterator.remove();
                } else {
                    window.textArea.updateCurrentLineHighlight();
                }
            }
        }
    }
}
