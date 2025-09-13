package com.kalix.gui.flowviz;

import com.kalix.gui.io.TimeSeriesCsvImporter;
import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages all data import/export operations for the FlowViz window including
 * CSV file loading, drag-and-drop functionality, and data processing.
 *
 * This class handles:
 * - CSV file import with progress dialogs
 * - Multiple file loading with batch processing
 * - Drag-and-drop file operations
 * - Import result processing and error handling
 * - File validation and filtering
 * - Progress feedback and status updates
 */
public class FlowVizDataManager {

    private final JFrame parentFrame;
    private final DataSet dataSet;

    // Callbacks for communication with parent window
    private Consumer<String> statusUpdater;
    private Consumer<File> currentFileUpdater;
    private Runnable titleUpdater;
    private Runnable zoomToFitAction;

    /**
     * Creates a new FlowViz data manager for comprehensive data import/export operations.
     *
     * <p>This manager handles all data-related operations for the FlowViz window including:
     * <ul>
     * <li>CSV file import with progress tracking and error handling</li>
     * <li>Multiple file batch loading with sequential processing</li>
     * <li>Drag-and-drop file operations with validation</li>
     * <li>Data processing and series name management</li>
     * <li>Import result handling with user feedback</li>
     * <li>File validation and format verification</li>
     * </ul>
     *
     * @param parentFrame The parent JFrame window for dialog positioning and drag-and-drop target
     * @param dataSet The DataSet instance to manage and populate with imported data
     */
    public FlowVizDataManager(JFrame parentFrame, DataSet dataSet) {
        this.parentFrame = parentFrame;
        this.dataSet = dataSet;
    }

    /**
     * Sets up the callback functions for communication with the parent window components.
     *
     * <p>This method establishes the communication bridge between the data manager and
     * the parent FlowViz window by providing callback functions for status updates,
     * file tracking, UI updates, and plot operations. This design allows the data manager
     * to operate independently while still providing feedback and triggering necessary
     * updates in the parent window.
     *
     * @param statusUpdater Consumer function to update the status bar with progress messages
     * @param currentFileUpdater Consumer function to update the current file reference for title display
     * @param titleUpdater Runnable to refresh the window title after data changes
     * @param zoomToFitAction Runnable to trigger zoom-to-fit operation after data import
     */
    public void setupCallbacks(Consumer<String> statusUpdater,
                             Consumer<File> currentFileUpdater,
                             Runnable titleUpdater,
                             Runnable zoomToFitAction) {
        this.statusUpdater = statusUpdater;
        this.currentFileUpdater = currentFileUpdater;
        this.titleUpdater = titleUpdater;
        this.zoomToFitAction = zoomToFitAction;
    }

    /**
     * Opens a file chooser dialog and loads selected CSV files with support for multiple selection.
     *
     * <p>This method presents a standard file chooser dialog filtered for CSV files, allowing
     * users to select one or multiple files for import. The method automatically detects whether
     * single or multiple files were selected and delegates to the appropriate loading method
     * for optimal user experience and progress tracking.
     */
    public void openCsvFiles() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        fileChooser.setMultiSelectionEnabled(true);  // Enable multi-select

        int result = fileChooser.showOpenDialog(parentFrame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();

            if (selectedFiles.length == 1) {
                // Single file - use existing method
                loadCsvFile(selectedFiles[0]);
            } else if (selectedFiles.length > 1) {
                // Multiple files - load them sequentially
                loadMultipleCsvFiles(selectedFiles);
            }
        }
    }

    /**
     * Loads a single CSV file with progress dialog.
     *
     * @param csvFile The CSV file to load
     */
    public void loadCsvFile(File csvFile) {
        statusUpdater.accept("Loading CSV file...");

        // Create progress dialog
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Parsing CSV...");

        JDialog progressDialog = new JDialog(parentFrame, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + csvFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);

        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(parentFrame);

        // Create CSV import task
        TimeSeriesCsvImporter.CsvImportTask importTask = new TimeSeriesCsvImporter.CsvImportTask(csvFile, new TimeSeriesCsvImporter.CsvImportOptions()) {
            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int progress = chunks.get(chunks.size() - 1);
                    progressBar.setValue(progress);
                    progressBar.setString(String.format("Importing CSV... %d%%", progress));
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();

                try {
                    TimeSeriesCsvImporter.CsvImportResult importResult = get();
                    handleImportResult(csvFile, importResult);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "Error loading CSV file:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    statusUpdater.accept("Error loading CSV file");
                }
            }
        };

        cancelButton.addActionListener(e -> {
            importTask.cancel(true);
            progressDialog.dispose();
            statusUpdater.accept("CSV import cancelled");
        });

        importTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Loads multiple CSV files with batch progress dialog.
     *
     * @param csvFiles Array of CSV files to load
     */
    public void loadMultipleCsvFiles(File[] csvFiles) {
        statusUpdater.accept("Loading " + csvFiles.length + " CSV files...");

        // Create progress dialog for multiple files
        JProgressBar progressBar = new JProgressBar(0, csvFiles.length);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading files: 0 of " + csvFiles.length);

        JDialog progressDialog = new JDialog(parentFrame, "Loading Multiple Files", true);
        progressDialog.setLayout(new BorderLayout());
        progressDialog.add(new JLabel("Loading CSV files...", JLabel.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);
        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(parentFrame);

        // Load files sequentially in background thread
        SwingWorker<Void, Integer> multiLoadTask = new SwingWorker<Void, Integer>() {
            private volatile boolean cancelled = false;

            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i < csvFiles.length && !cancelled && !isCancelled(); i++) {
                    final int fileIndex = i;
                    final File csvFile = csvFiles[i];

                    publish(fileIndex);

                    // Import file synchronously
                    TimeSeriesCsvImporter.CsvImportTask importTask = new TimeSeriesCsvImporter.CsvImportTask(csvFile, new TimeSeriesCsvImporter.CsvImportOptions()) {
                        @Override
                        protected void done() {
                            try {
                                TimeSeriesCsvImporter.CsvImportResult result = get();
                                SwingUtilities.invokeLater(() -> {
                                    handleImportResult(csvFile, result);
                                });
                            } catch (Exception e) {
                                SwingUtilities.invokeLater(() -> {
                                    JOptionPane.showMessageDialog(parentFrame,
                                        "Failed to load " + csvFile.getName() + ": " + e.getMessage(),
                                        "Load Error", JOptionPane.ERROR_MESSAGE);
                                });
                            }
                        }
                    };

                    try {
                        // Execute and wait for completion
                        importTask.execute();
                        importTask.get(); // Wait for completion
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(parentFrame,
                                "Failed to load " + csvFile.getName() + ": " + e.getMessage(),
                                "Load Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int fileIndex = chunks.get(chunks.size() - 1);
                    progressBar.setValue(fileIndex);
                    progressBar.setString("Loading files: " + (fileIndex + 1) + " of " + csvFiles.length);
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                statusUpdater.accept("Loaded " + csvFiles.length + " CSV files");
            }

            public void cancel() {
                cancelled = true;
                cancel(true);
            }
        };

        cancelButton.addActionListener(e -> {
            multiLoadTask.cancel(true);
            progressDialog.dispose();
            statusUpdater.accept("File loading cancelled");
        });

        multiLoadTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Processes the result of a CSV import operation.
     *
     * @param csvFile The source CSV file
     * @param importResult The import result with data and statistics
     */
    private void handleImportResult(File csvFile, TimeSeriesCsvImporter.CsvImportResult importResult) {
        if (importResult.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder("Failed to load CSV file:\n\n");
            for (String error : importResult.getErrors()) {
                errorMessage.append("• ").append(error).append("\n");
            }

            JOptionPane.showMessageDialog(parentFrame, errorMessage.toString(),
                "CSV Load Error", JOptionPane.ERROR_MESSAGE);
            statusUpdater.accept("Failed to load CSV file");
            return;
        }

        // Add new data (don't clear existing data)
        String fileName = csvFile.getName();

        for (TimeSeriesData series : importResult.getDataSet().getAllSeries()) {
            // Create display name: "filename.csv: ColumnName"
            String columnName = series.getName();
            String displayName = fileName + ": " + columnName;
            String uniqueName = getUniqueSeriesName(displayName);

            // Create new series with the display name
            TimeSeriesData namedSeries = new TimeSeriesData(
                uniqueName,
                convertTimestampsToLocalDateTime(series.getTimestamps()),
                series.getValues()
            );
            dataSet.addSeries(namedSeries);
        }

        currentFileUpdater.accept(csvFile);
        titleUpdater.run();

        // Show warnings if any
        if (importResult.hasWarnings()) {
            StringBuilder warningMessage = new StringBuilder("CSV loaded successfully with warnings:\n\n");
            for (String warning : importResult.getWarnings()) {
                warningMessage.append("• ").append(warning).append("\n");
            }

            JOptionPane.showMessageDialog(parentFrame, warningMessage.toString(),
                "Load Warnings", JOptionPane.WARNING_MESSAGE);
        }

        // Update status with statistics
        TimeSeriesCsvImporter.ImportStatistics stats = importResult.getStatistics();
        int newSeriesCount = importResult.getDataSet().getSeriesCount();
        statusUpdater.accept(String.format("Added %d new series (%,d total series, %,d total points) in %d ms",
            newSeriesCount, dataSet.getSeriesCount(), dataSet.getTotalPointCount(), stats.getParseTimeMs()));

        // Zoom to fit all data (including existing + new)
        if (zoomToFitAction != null) {
            zoomToFitAction.run();
        }
    }

    /**
     * Generates a unique series name by appending a counter if the base name already exists.
     *
     * @param baseName The base name to make unique
     * @return A unique series name
     */
    private String getUniqueSeriesName(String baseName) {
        if (!dataSet.hasSeries(baseName)) {
            return baseName;
        }

        // Find unique name by appending number
        int counter = 2;
        String candidateName;
        do {
            candidateName = baseName + " (" + counter + ")";
            counter++;
        } while (dataSet.hasSeries(candidateName));

        return candidateName;
    }

    /**
     * Converts millisecond timestamps to LocalDateTime objects.
     *
     * @param timestamps Array of millisecond timestamps
     * @return Array of LocalDateTime objects
     */
    private java.time.LocalDateTime[] convertTimestampsToLocalDateTime(long[] timestamps) {
        java.time.LocalDateTime[] result = new java.time.LocalDateTime[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            result[i] = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                java.time.ZoneOffset.UTC);
        }
        return result;
    }

    /**
     * Sets up comprehensive drag-and-drop functionality for CSV files on the parent frame.
     *
     * <p>This method configures the parent frame as a drop target for CSV files, providing
     * visual feedback during drag operations and automatic file validation. The implementation
     * handles multiple file drops, filters non-CSV files, and provides appropriate user feedback
     * for invalid file types or failed operations.
     *
     * <p>Supported drag-and-drop operations:
     * <ul>
     * <li>Single CSV file drag and drop</li>
     * <li>Multiple CSV file batch processing</li>
     * <li>Visual feedback during drag operations</li>
     * <li>Automatic file type validation and filtering</li>
     * <li>Error handling for failed drop operations</li>
     * </ul>
     */
    public void setupDragAndDrop() {
        // Enable drag-and-drop for CSV files on the entire window
        new DropTarget(parentFrame, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    statusUpdater.accept("Drop CSV files to load them...");
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                statusUpdater.accept("Ready");
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                if (isDropAcceptable(dtde)) {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);

                    try {
                        Transferable transferable = dtde.getTransferable();
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);

                        // Filter for CSV files
                        List<File> csvFiles = files.stream()
                            .filter(file -> file.getName().toLowerCase().endsWith(".csv"))
                            .toList();

                        if (csvFiles.isEmpty()) {
                            statusUpdater.accept("No CSV files found in drop");
                            JOptionPane.showMessageDialog(parentFrame,
                                "Please drop CSV files only.",
                                "Invalid File Type",
                                JOptionPane.WARNING_MESSAGE);
                        } else if (csvFiles.size() == 1) {
                            // Single file - use existing method
                            loadCsvFile(csvFiles.get(0));
                        } else {
                            // Multiple files - use batch loading method
                            loadMultipleCsvFiles(csvFiles.toArray(new File[0]));
                        }

                        dtde.dropComplete(true);
                    } catch (Exception e) {
                        dtde.dropComplete(false);
                        statusUpdater.accept("Failed to load dropped files");
                        JOptionPane.showMessageDialog(parentFrame,
                            "Failed to load dropped files: " + e.getMessage(),
                            "Drop Error",
                            JOptionPane.ERROR_MESSAGE);
                    }
                } else {
                    dtde.rejectDrop();
                }
            }

            private boolean isDragAcceptable(DropTargetDragEvent dtde) {
                return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            private boolean isDropAcceptable(DropTargetDropEvent dtde) {
                return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
        });
    }

    /**
     * Clears all data in the data set (for new session functionality).
     */
    public void clearAllData() {
        dataSet.removeAllSeries();
    }
}