package com.kalix.ide.windows;

import com.kalix.ide.components.KalixPlainTextArea;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.utils.DialogUtils;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Read-only window showing a run's mass balance report as plain text, with
 * "Validate…" and "Save…" buttons. Modelled on {@link MinimalEditorWindow}.
 *
 * <p>"Validate…" asks for a reference report file and hands its text to
 * {@code onValidate}, along with this window as the parent for whatever the
 * caller shows. How the two reports are compared, and how the result is shown,
 * is the caller's business.
 */
public class MassBalanceReportWindow extends JFrame {

    // The name the regression suite's reference reports use.
    private static final String SUGGESTED_FILE_NAME = "mbal.txt";

    private final String report;
    private final Supplier<File> startDirectory;
    private final BiConsumer<Component, String> onValidate;

    /**
     * @param runName        the run the report belongs to, shown in the title
     * @param report         the mass balance report text
     * @param startDirectory where the file dialogs open; may be null or supply null
     * @param onValidate     called on the EDT with this window and the text of the
     *                       reference file the user chose; not called if they cancel
     *                       or it cannot be read
     */
    public MassBalanceReportWindow(String runName, String report,
                                   Supplier<File> startDirectory,
                                   BiConsumer<Component, String> onValidate) {
        this.report = report;
        this.startDirectory = startDirectory;
        this.onValidate = onValidate;

        setTitle("Mass Balance Report - " + runName);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        KalixPlainTextArea textArea = KalixPlainTextArea.createReadOnly(20, 60);
        textArea.setText(report);
        textArea.setCaretPosition(0);

        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Ellipses: both open a file dialog before they act (ADR-0002 §2.4).
        JButton validateButton = new JButton("Validate…");
        validateButton.addActionListener(e -> showValidateDialog());

        JButton saveButton = new JButton("Save…");
        saveButton.addActionListener(e -> showSaveDialog());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(validateButton);
        buttonPanel.add(saveButton);

        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private File startDirectory() {
        return startDirectory != null ? startDirectory.get() : null;
    }

    private void showValidateDialog() {
        KalixFileDialog.openFile(this)
            .title("Choose Reference Mass Balance Report")
            .startIn(startDirectory())
            .show()
            .ifPresent(this::validateAgainst);
    }

    private void validateAgainst(File referenceFile) {
        String reference;
        try {
            reference = Files.readString(referenceFile.toPath());
        } catch (IOException e) {
            DialogUtils.showError(this,
                "Failed to read reference report: " + e.getMessage(),
                "Validation Error");
            return;
        }
        onValidate.accept(this, reference);
    }

    private void showSaveDialog() {
        KalixFileDialog.saveFile(this)
            .title("Save Mass Balance Report")
            .startIn(startDirectory())
            .suggestedName(SUGGESTED_FILE_NAME)
            .show()
            .ifPresent(this::saveFile);
    }

    private void saveFile(File file) {
        try {
            Files.writeString(file.toPath(), report);
            JOptionPane.showMessageDialog(this,
                "Report saved to " + file.getName(),
                "Save Successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            DialogUtils.showError(this,
                "Failed to save report: " + e.getMessage(),
                "Save Error");
        }
    }
}
