package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.TimeSeriesCsvImporter;
import com.kalix.ide.io.KalixTimeSeriesReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.dnd.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Manages dataset file loading (CSV and Kalix compressed formats) for RunManager.
 *
 * Responsibilities:
 * - Drag-and-drop file handling
 * - CSV file import with progress dialogs
 * - Kalix compressed format (.kai/.kaz) loading
 * - File duplicate detection
 * - Hierarchical series naming (file.filename.column)
 * - Adding datasets to tree structure
 *
 * Usage:
 * 1. Create manager with required dependencies
 * 2. Call setupDragAndDrop() on target component
 * 3. Manager handles all loading and UI feedback
 */
public class DatasetLoaderManager {

    private static final Logger logger = LoggerFactory.getLogger(DatasetLoaderManager.class);

    // Dependencies
    private final JFrame parentFrame;
    private final Map<String, TimeSeriesData> datasetSeriesCache;
    private final DefaultMutableTreeNode loadedDatasetsNode;
    private final DefaultTreeModel treeModel;
    private final Consumer<String> statusUpdater;

    // Callbacks
    private final Runnable onDatasetLoadedCallback;

    /**
     * Creates a new DatasetLoaderManager.
     *
     * @param parentFrame Parent frame for dialogs
     * @param datasetSeriesCache Cache to store loaded series
     * @param loadedDatasetsNode Tree node for loaded datasets
     * @param treeModel Tree model for updates
     * @param statusUpdater Status bar updater
     * @param onDatasetLoadedCallback Callback after dataset is loaded
     */
    public DatasetLoaderManager(
            JFrame parentFrame,
            Map<String, TimeSeriesData> datasetSeriesCache,
            DefaultMutableTreeNode loadedDatasetsNode,
            DefaultTreeModel treeModel,
            Consumer<String> statusUpdater,
            Runnable onDatasetLoadedCallback) {
        this.parentFrame = parentFrame;
        this.datasetSeriesCache = datasetSeriesCache;
        this.loadedDatasetsNode = loadedDatasetsNode;
        this.treeModel = treeModel;
        this.statusUpdater = statusUpdater;
        this.onDatasetLoadedCallback = onDatasetLoadedCallback;
    }

    /**
     * Sets up drag-and-drop file loading on a component.
     *
     * @param component The component to enable drag-and-drop on
     */
    public void setupDragAndDrop(Component component) {
        new DropTarget(component, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Drop CSV or KAI/KAZ files to load them...");
                    }
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
                if (statusUpdater != null) {
                    statusUpdater.accept("Ready");
                }
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

                        // Filter for supported files (CSV, KAI, and KAZ)
                        List<File> supportedFiles = files.stream()
                            .filter(file -> {
                                String name = file.getName().toLowerCase();
                                return name.endsWith(".csv") || name.endsWith(".kai") || name.endsWith(".kaz");
                            })
                            .toList();

                        if (supportedFiles.isEmpty()) {
                            if (statusUpdater != null) {
                                statusUpdater.accept("No supported files found in drop");
                            }
                            JOptionPane.showMessageDialog(parentFrame,
                                "Please drop CSV or KAI/KAZ files only.",
                                "Invalid File Type",
                                JOptionPane.WARNING_MESSAGE);
                        } else {
                            // Load all dropped files
                            for (File file : supportedFiles) {
                                loadDatasetFile(file);
                            }
                        }

                        dtde.dropComplete(true);
                    } catch (Exception e) {
                        dtde.dropComplete(false);
                        if (statusUpdater != null) {
                            statusUpdater.accept("Failed to load dropped files");
                        }
                        JOptionPane.showMessageDialog(parentFrame,
                            "Failed to load dropped files: " + e.getMessage(),
                            "Drop Error",
                            JOptionPane.ERROR_MESSAGE);
                        logger.error("Failed to load dropped files", e);
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
     * Loads a dataset file (CSV or KAI/KAZ) and adds it to the loaded datasets tree.
     * Automatically detects file type and uses the appropriate importer.
     *
     * @param file The dataset file to load (.csv, .kai, or .kaz)
     */
    public void loadDatasetFile(File file) {
        // Check if this file is already loaded
        if (isFileAlreadyLoaded(file)) {
            logger.info("File already loaded, skipping: {}", file.getName());
            if (statusUpdater != null) {
                statusUpdater.accept("File already loaded: " + file.getName());
            }
            JOptionPane.showMessageDialog(parentFrame,
                "This file is already loaded:\n" + file.getName(),
                "File Already Loaded",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".csv")) {
            loadCsvDataset(file);
        } else if (fileName.endsWith(".kai") || fileName.endsWith(".kaz")) {
            loadKalixDataset(file);
        } else {
            logger.warn("Unsupported file type: " + fileName);
            if (statusUpdater != null) {
                statusUpdater.accept("Unsupported file type: " + fileName);
            }
        }
    }

    /**
     * Checks if a file is already loaded in the dataset tree.
     */
    private boolean isFileAlreadyLoaded(File file) {
        String absolutePath = file.getAbsolutePath();

        // Check all children of loadedDatasetsNode
        for (int j = 0; j < loadedDatasetsNode.getChildCount(); j++) {
            DefaultMutableTreeNode datasetNode = (DefaultMutableTreeNode) loadedDatasetsNode.getChildAt(j);
            if (datasetNode.getUserObject() instanceof LoadedDatasetInfo) {
                LoadedDatasetInfo info = (LoadedDatasetInfo) datasetNode.getUserObject();
                if (info.file.getAbsolutePath().equals(absolutePath)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Loads a CSV dataset file with progress dialog.
     *
     * @param csvFile The CSV file to load
     */
    private void loadCsvDataset(File csvFile) {
        if (statusUpdater != null) {
            statusUpdater.accept("Loading CSV dataset...");
        }

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
                    handleCsvImportResult(csvFile, importResult);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "Error loading CSV file:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Error loading CSV file");
                    }
                    logger.error("Error loading CSV file: " + csvFile.getName(), e);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            importTask.cancel(true);
            progressDialog.dispose();
            if (statusUpdater != null) {
                statusUpdater.accept("CSV import cancelled");
            }
        });

        importTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Handles the result of a CSV import operation.
     *
     * @param csvFile The CSV file that was imported
     * @param importResult The import result containing data and statistics
     */
    private void handleCsvImportResult(File csvFile, TimeSeriesCsvImporter.CsvImportResult importResult) {
        if (importResult.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder("Failed to load CSV file:\n\n");
            for (String error : importResult.getErrors()) {
                errorMessage.append("• ").append(error).append("\n");
            }

            JOptionPane.showMessageDialog(parentFrame, errorMessage.toString(),
                "CSV Load Error", JOptionPane.ERROR_MESSAGE);
            if (statusUpdater != null) {
                statusUpdater.accept("Failed to load CSV file");
            }
            return;
        }

        // Add new data to cache using hierarchical naming scheme
        String fileName = csvFile.getName();
        int seriesAdded = 0;

        for (TimeSeriesData series : importResult.getDataSet().getAllSeries()) {
            // Create hierarchical series name: file.sanitized_filename.sanitized_column
            String columnName = series.getName();
            String seriesName = createDatasetSeriesName(csvFile, columnName);

            logger.info("Loading CSV series: columnName='{}' -> seriesName='{}'", columnName, seriesName);

            // Create new series with hierarchical name
            TimeSeriesData namedSeries = new TimeSeriesData(
                seriesName,
                convertTimestampsToLocalDateTime(series.getTimestamps()),
                series.getValues()
            );

            // Store in cache (NOT in plotDataSet yet - added when plotted, like runs)
            datasetSeriesCache.put(seriesName, namedSeries);
            seriesAdded++;
        }

        // Add file to loaded datasets tree
        addLoadedDatasetToTree(csvFile);

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
        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Loaded %s: %d series, %,d total points in %d ms",
                fileName, seriesAdded, importResult.getDataSet().getTotalPointCount(), stats.getParseTimeMs()));
        }

        // Notify callback
        if (onDatasetLoadedCallback != null) {
            onDatasetLoadedCallback.run();
        }
    }

    /**
     * Loads a Kalix compressed dataset file (.kai + .kaz) with progress dialog.
     *
     * @param ktmFile The KAI metadata file to load (or KAZ file, will find the .kai)
     */
    private void loadKalixDataset(File ktmFile) {
        if (statusUpdater != null) {
            statusUpdater.accept("Loading Kalix dataset...");
        }

        // Ensure we're working with the .kai file
        String basePath = ktmFile.getAbsolutePath().replaceAll("\\.(kai|kaz)$", "");
        File kaiFile = new File(basePath + ".kai");
        File kazFile = new File(basePath + ".kaz");

        // Verify both files exist
        if (!kaiFile.exists() || !kazFile.exists()) {
            JOptionPane.showMessageDialog(parentFrame,
                "Both .kai and .kaz files are required.\n" +
                "Missing: " + (!kaiFile.exists() ? kaiFile.getName() : kazFile.getName()),
                "Load Error",
                JOptionPane.ERROR_MESSAGE);
            if (statusUpdater != null) {
                statusUpdater.accept("Failed to load Kalix dataset");
            }
            return;
        }

        // Create progress dialog
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading Kalix dataset...");

        JDialog progressDialog = new JDialog(parentFrame, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + kaiFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);

        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(parentFrame);

        // Create background loading task
        SwingWorker<List<TimeSeriesData>, Integer> loadTask = new SwingWorker<>() {
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
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();

                try {
                    List<TimeSeriesData> seriesList = get();

                    // Add all series to cache using hierarchical naming scheme
                    for (TimeSeriesData series : seriesList) {
                        // Create hierarchical series name: file.sanitized_filename.sanitized_series
                        String originalSeriesName = series.getName();
                        String seriesName = createDatasetSeriesName(kaiFile, originalSeriesName);

                        logger.info("Loading KAI series: originalName='{}' -> seriesName='{}'", originalSeriesName, seriesName);

                        TimeSeriesData namedSeries = new TimeSeriesData(
                            seriesName,
                            convertTimestampsToLocalDateTime(series.getTimestamps()),
                            series.getValues()
                        );

                        // Store in cache (NOT in plotDataSet yet - added when plotted, like runs)
                        datasetSeriesCache.put(seriesName, namedSeries);
                    }

                    // Add file to loaded datasets tree
                    addLoadedDatasetToTree(kaiFile);

                    // Update status
                    if (statusUpdater != null) {
                        statusUpdater.accept(String.format("Loaded %s: %d series",
                            kaiFile.getName(), seriesList.size()));
                    }

                    // Notify callback
                    if (onDatasetLoadedCallback != null) {
                        onDatasetLoadedCallback.run();
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "Error loading Kalix dataset:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Error loading Kalix dataset");
                    }
                    logger.error("Error loading Kalix dataset: " + kaiFile.getName(), e);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            loadTask.cancel(true);
            progressDialog.dispose();
            if (statusUpdater != null) {
                statusUpdater.accept("Kalix dataset load cancelled");
            }
        });

        loadTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Converts millisecond timestamps to LocalDateTime array.
     *
     * @param timestampsMillis Array of timestamps in milliseconds since epoch
     * @return Array of LocalDateTime objects in UTC
     */
    private LocalDateTime[] convertTimestampsToLocalDateTime(long[] timestampsMillis) {
        LocalDateTime[] result = new LocalDateTime[timestampsMillis.length];
        for (int i = 0; i < timestampsMillis.length; i++) {
            result[i] = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestampsMillis[i]),
                ZoneOffset.UTC);
        }
        return result;
    }

    /**
     * Sanitizes a string by converting all non-alphanumeric characters to underscores.
     * Used for creating valid hierarchical series names from filenames and column headers.
     *
     * @param input The string to sanitize
     * @return Sanitized string with only alphanumeric characters and underscores
     */
    private String sanitizeToIdentifier(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[^a-zA-Z0-9]", "_");
    }

    /**
     * Creates a hierarchical series name from a file and column name.
     * Format: file.{sanitized_filename}.{sanitized_column}
     *
     * @param file The data file
     * @param columnName The column name (will be trimmed and sanitized)
     * @return Hierarchical series name in format: file.filename_csv.column_name
     */
    private String createDatasetSeriesName(File file, String columnName) {
        String sanitizedFilename = sanitizeToIdentifier(file.getName());
        String sanitizedColumn = sanitizeToIdentifier(columnName.trim());
        return "file." + sanitizedFilename + "." + sanitizedColumn;
    }

    /**
     * Adds a loaded dataset file to the loaded datasets tree.
     * Uses treeModel.nodesWereInserted to preserve current selection.
     *
     * @param file The dataset file that was loaded
     */
    private void addLoadedDatasetToTree(File file) {
        // Create new dataset info and tree node
        LoadedDatasetInfo datasetInfo = new LoadedDatasetInfo(file.getName(), file);
        DefaultMutableTreeNode datasetNode = new DefaultMutableTreeNode(datasetInfo);

        // Add to loadedDatasetsNode
        int insertIndex = loadedDatasetsNode.getChildCount();
        loadedDatasetsNode.add(datasetNode);

        // Notify tree model to preserve selection
        int[] childIndices = new int[] { insertIndex };
        Object[] children = new Object[] { datasetNode };
        treeModel.nodesWereInserted(loadedDatasetsNode, childIndices);

        logger.info("Added dataset to tree: " + file.getName());
    }

    /**
     * Data class for loaded dataset information.
     */
    public static class LoadedDatasetInfo {
        public final String fileName;
        public final File file;

        public LoadedDatasetInfo(String fileName, File file) {
            this.fileName = fileName;
            this.file = file;
        }

        @Override
        public String toString() {
            return fileName;
        }
    }
}
