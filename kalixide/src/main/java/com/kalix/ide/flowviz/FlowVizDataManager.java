package com.kalix.ide.flowviz;

import com.kalix.ide.io.TimeSeriesCsvImporter;
import com.kalix.ide.io.KalixTimeSeriesReader;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;

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
    private Consumer<File> currentFileUpdater;
    private Runnable titleUpdater;
    private Runnable zoomToFitAction;
    private java.util.function.Supplier<File> baseDirectorySupplier;

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
     * the parent FlowViz window by providing callback functions for file tracking,
     * UI updates, and plot operations. This design allows the data manager to operate
     * independently while still triggering necessary updates in the parent window.
     *
     * @param currentFileUpdater Consumer function to update the current file reference for title display
     * @param titleUpdater Runnable to refresh the window title after data changes
     * @param zoomToFitAction Runnable to trigger zoom-to-fit operation after data import
     */
    public void setupCallbacks(Consumer<File> currentFileUpdater,
                             Runnable titleUpdater,
                             Runnable zoomToFitAction) {
        this.currentFileUpdater = currentFileUpdater;
        this.titleUpdater = titleUpdater;
        this.zoomToFitAction = zoomToFitAction;
    }

    /**
     * Opens a file chooser dialog and loads selected files with support for multiple selection.
     * Supports both CSV files and Kalix compressed timeseries files (.kai).
     *
     * <p>This method presents a standard file chooser dialog filtered for supported file types, allowing
     * users to select one or multiple files for import. The method automatically detects file types and
     * delegates to the appropriate loading method for optimal user experience and progress tracking.
     */
    public void openCsvFiles() {
        JFileChooser fileChooser = new JFileChooser();

        // Add file filters for different formats
        FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV Files (*.csv)", "csv");
        FileNameExtensionFilter ktmFilter = new FileNameExtensionFilter("Kalix Timeseries Files (*.kai)", "kai");
        FileNameExtensionFilter allFilter = new FileNameExtensionFilter("All Supported (*.csv, *.kai)", "csv", "kai");

        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.addChoosableFileFilter(ktmFilter);
        fileChooser.addChoosableFileFilter(allFilter);
        fileChooser.setFileFilter(allFilter); // Default to all supported

        // Set initial directory to model directory if available, otherwise use user home
        if (baseDirectorySupplier != null) {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            } else {
                fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
            }
        } else {
            fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        }
        fileChooser.setMultiSelectionEnabled(true);  // Enable multi-select

        int result = fileChooser.showOpenDialog(parentFrame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();

            if (selectedFiles.length == 1) {
                // Single file - use appropriate method based on type
                loadFile(selectedFiles[0]);
            } else if (selectedFiles.length > 1) {
                // Multiple files - load them sequentially
                loadMultipleFiles(selectedFiles);
            }
        }
    }

    /**
     * Loads a single file with progress dialog. Automatically detects file type.
     *
     * @param file The file to load (CSV or KTM)
     */
    public void loadFile(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".csv")) {
            loadCsvFile(file);
        } else if (fileName.endsWith(".kai")) {
            loadKtmFile(file);
        } else {
            JOptionPane.showMessageDialog(parentFrame,
                "Unsupported file type: " + file.getName() + "\nSupported types: .csv, .kai",
                "Load Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Loads multiple files with batch progress dialog. Automatically detects file types.
     *
     * @param files Array of files to load (CSV or KAI)
     */
    public void loadMultipleFiles(File[] files) {
        // Create progress dialog for multiple files
        JProgressBar progressBar = new JProgressBar(0, files.length);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading files: 0 of " + files.length);

        JDialog progressDialog = new JDialog(parentFrame, "Loading Multiple Files", true);
        progressDialog.setLayout(new BorderLayout());
        progressDialog.add(new JLabel("Loading files...", JLabel.CENTER), BorderLayout.NORTH);
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
                for (int i = 0; i < files.length && !cancelled && !isCancelled(); i++) {
                    final int fileIndex = i;
                    final File file = files[i];

                    publish(fileIndex);

                    SwingUtilities.invokeLater(() -> {
                        try {
                            loadFile(file);
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(parentFrame,
                                "Failed to load " + file.getName() + ": " + e.getMessage(),
                                "Load Error", JOptionPane.ERROR_MESSAGE);
                        }
                    });

                    // Small delay between files to prevent overwhelming the UI
                    Thread.sleep(100);
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int fileIndex = chunks.get(chunks.size() - 1);
                    progressBar.setValue(fileIndex);
                    progressBar.setString("Loading files: " + (fileIndex + 1) + " of " + files.length);
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();
            }

            public void cancel() {
                cancelled = true;
                cancel(true);
            }
        };

        cancelButton.addActionListener(e -> {
            multiLoadTask.cancel(true);
            progressDialog.dispose();
        });

        multiLoadTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Loads a single CSV file with progress dialog.
     *
     * @param csvFile The CSV file to load
     */
    public void loadCsvFile(File csvFile) {
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
                }
            }
        };

        cancelButton.addActionListener(e -> {
            importTask.cancel(true);
            progressDialog.dispose();
        });

        importTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Loads a single Kalix timeseries file (.kai + .kaz) with progress dialog.
     *
     * @param ktmFile The KAI metadata file to load
     */
    public void loadKtmFile(File ktmFile) {
        // Verify the corresponding .kaz file exists
        String basePath = ktmFile.getAbsolutePath().replaceAll("\\.kai$", "");
        File kazFile = new File(basePath + ".kaz");

        if (!kazFile.exists()) {
            JOptionPane.showMessageDialog(parentFrame,
                "Binary data file not found: " + kazFile.getName() + "\nBoth .kai and .kaz files are required.",
                "Load Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Create progress dialog
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading Kalix timeseries...");

        JDialog progressDialog = new JDialog(parentFrame, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + ktmFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);

        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(parentFrame);

        // Create background loading task
        SwingWorker<List<TimeSeriesData>, Integer> loadTask = new SwingWorker<List<TimeSeriesData>, Integer>() {
            @Override
            protected List<TimeSeriesData> doInBackground() throws Exception {
                publish(25);
                KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
                publish(50);
                List<TimeSeriesData> seriesList = reader.readAllSeries(basePath);
                publish(100);
                return seriesList;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int progress = chunks.get(chunks.size() - 1);
                    progressBar.setValue(progress);
                    progressBar.setString(String.format("Loading Kalix timeseries... %d%%", progress));
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();

                try {
                    List<TimeSeriesData> seriesList = get();
                    handleKtmImportResult(ktmFile, seriesList);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "Error loading Kalix timeseries file:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            loadTask.cancel(true);
            progressDialog.dispose();
        });

        loadTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Processes the result of a Kalix timeseries import operation.
     *
     * @param ktmFile The source KAI file
     * @param seriesList The loaded time series data
     */
    private void handleKtmImportResult(File ktmFile, List<TimeSeriesData> seriesList) {
        if (seriesList == null || seriesList.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame,
                "No time series data found in file: " + ktmFile.getName(),
                "Load Warning",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Add new data (don't clear existing data)
        String fileName = ktmFile.getName();
        int addedCount = 0;

        for (TimeSeriesData series : seriesList) {
            // Create display name: "filename.kai: SeriesName"
            String originalName = series.getName();
            String displayName = fileName + ": " + originalName;
            String uniqueName = getUniqueSeriesName(displayName);

            // Create new series with the display name
            TimeSeriesData namedSeries = new TimeSeriesData(
                uniqueName,
                convertTimestampsToLocalDateTime(series.getTimestamps()),
                series.getValues()
            );
            dataSet.addSeries(namedSeries);
            addedCount++;
        }

        currentFileUpdater.accept(ktmFile);
        titleUpdater.run();

        // Zoom to fit all data (including existing + new)
        if (zoomToFitAction != null) {
            zoomToFitAction.run();
        }
    }

    /**
     * Loads multiple CSV files with batch progress dialog.
     *
     * @param csvFiles Array of CSV files to load
     */
    public void loadMultipleCsvFiles(File[] csvFiles) {
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
            }

            public void cancel() {
                cancelled = true;
                cancel(true);
            }
        };

        cancelButton.addActionListener(e -> {
            multiLoadTask.cancel(true);
            progressDialog.dispose();
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
     * @param timestampsMillis Array of millisecond timestamps
     * @return Array of LocalDateTime objects
     */
    private java.time.LocalDateTime[] convertTimestampsToLocalDateTime(long[] timestampsMillis) {
        java.time.LocalDateTime[] result = new java.time.LocalDateTime[timestampsMillis.length];
        for (int i = 0; i < timestampsMillis.length; i++) {
            result[i] = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestampsMillis[i]),
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
                // Do nothing
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

                        // Filter for supported files (CSV and KAI)
                        List<File> supportedFiles = files.stream()
                            .filter(file -> {
                                String name = file.getName().toLowerCase();
                                return name.endsWith(".csv") || name.endsWith(".kai");
                            })
                            .toList();

                        if (supportedFiles.isEmpty()) {
                            JOptionPane.showMessageDialog(parentFrame,
                                "Please drop CSV or KAI files only.",
                                "Invalid File Type",
                                JOptionPane.WARNING_MESSAGE);
                        } else if (supportedFiles.size() == 1) {
                            // Single file - use file-type detection method
                            loadFile(supportedFiles.get(0));
                        } else {
                            // Multiple files - use batch loading method
                            loadMultipleFiles(supportedFiles.toArray(new File[0]));
                        }

                        dtde.dropComplete(true);
                    } catch (Exception e) {
                        dtde.dropComplete(false);
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