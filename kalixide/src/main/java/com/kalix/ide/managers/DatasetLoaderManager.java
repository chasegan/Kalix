package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.TimeSeriesCsvImporter;
import com.kalix.ide.io.SourceResCsvFormat;
import com.kalix.ide.io.SourceResCsvImporter;
import com.kalix.ide.io.PixieReader;
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
 * Manages dataset file loading (CSV and Pixie formats) for RunManager.
 *
 * Responsibilities:
 * - Drag-and-drop file handling
 * - CSV file import with progress dialogs
 * - Pixie format (.pxt/.pxb) loading
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
    private final Map<DatasetSeries, TimeSeriesData> datasetSeriesCache;
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
            Map<DatasetSeries, TimeSeriesData> datasetSeriesCache,
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
                        statusUpdater.accept("Drop CSV or Pixie files to load them...");
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

                        // Filter for supported files (CSV and Pixie)
                        List<File> supportedFiles = files.stream()
                            .filter(file -> {
                                String name = file.getName().toLowerCase();
                                return name.endsWith(".csv") || name.endsWith(".pxt") || name.endsWith(".pxb");
                            })
                            .toList();

                        if (supportedFiles.isEmpty()) {
                            if (statusUpdater != null) {
                                statusUpdater.accept("No supported files found in drop");
                            }
                            JOptionPane.showMessageDialog(parentFrame,
                                "Please drop CSV or Pixie files only.",
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
     * Loads a dataset file (CSV or Pixie) and adds it to the loaded datasets tree.
     * Automatically detects file type and uses the appropriate importer.
     *
     * @param file The dataset file to load (.csv, .pxt, or .pxb)
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

        // ".res.csv" must be tested before ".csv" — the latter would otherwise swallow it.
        if (SourceResCsvFormat.isResCsv(fileName)) {
            loadResCsvDataset(file);
        } else if (fileName.endsWith(".csv")) {
            loadCsvDataset(file);
        } else if (fileName.endsWith(".pxt") || fileName.endsWith(".pxb")) {
            loadPixieDataset(file);
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
            if (datasetNode.getUserObject() instanceof LoadedDatasetInfo info) {
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

        for (com.kalix.ide.io.NamedSeries ns : importResult.getSeries()) {
            // Create hierarchical series name from the series' path segments
            String seriesName = composeDatasetSeriesName(csvFile, ns.path());

            logger.info("Loading CSV series: columnName='{}' -> seriesName='{}'", ns.name(), seriesName);

            // The importer already returns nameless data — store it directly. Identity is
            // the DatasetSeries ref; the absolute path qualifies the entry so two files
            // whose names sanitize to the same identifier stay separate.
            DatasetSeries ref = new DatasetSeries(csvFile.getAbsolutePath(), seriesName);
            datasetSeriesCache.put(ref, ns.data());
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
                fileName, seriesAdded, importResult.getTotalPointCount(), stats.getParseTimeMs()));
        }

        // Notify callback
        if (onDatasetLoadedCallback != null) {
            onDatasetLoadedCallback.run();
        }
    }

    /**
     * Loads a Source result CSV ({@code .res.csv}) dataset with progress dialog.
     *
     * @param resCsvFile The .res.csv file to load
     */
    private void loadResCsvDataset(File resCsvFile) {
        if (statusUpdater != null) {
            statusUpdater.accept("Loading res.csv dataset...");
        }

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Parsing res.csv...");

        JDialog progressDialog = new JDialog(parentFrame, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + resCsvFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);

        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(parentFrame);

        SourceResCsvImporter.ResCsvImportTask importTask = new SourceResCsvImporter.ResCsvImportTask(resCsvFile) {
            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int progress = chunks.get(chunks.size() - 1);
                    progressBar.setValue(progress);
                    progressBar.setString(String.format("Importing res.csv... %d%%", progress));
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();

                try {
                    handleResCsvImportResult(resCsvFile, get());
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "Error loading res.csv file:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Error loading res.csv file");
                    }
                    logger.error("Error loading res.csv file: " + resCsvFile.getName(), e);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            importTask.cancel(true);
            progressDialog.dispose();
            if (statusUpdater != null) {
                statusUpdater.accept("res.csv import cancelled");
            }
        });

        importTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Handles the result of a res.csv import operation.
     *
     * @param resCsvFile The .res.csv file that was imported
     * @param importResult The import result containing data and warnings
     */
    private void handleResCsvImportResult(File resCsvFile,
                                          SourceResCsvImporter.ResCsvImportResult importResult) {
        if (importResult.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder("Failed to load res.csv file:\n\n");
            for (String error : importResult.getErrors()) {
                errorMessage.append("• ").append(error).append("\n");
            }

            JOptionPane.showMessageDialog(parentFrame, errorMessage.toString(),
                "res.csv Load Error", JOptionPane.ERROR_MESSAGE);
            if (statusUpdater != null) {
                statusUpdater.accept("Failed to load res.csv file");
            }
            return;
        }

        String fileName = resCsvFile.getName();
        int seriesAdded = 0;

        for (com.kalix.ide.io.NamedSeries ns : importResult.getSeries()) {
            String seriesName = composeDatasetSeriesName(resCsvFile, ns.path());
            DatasetSeries ref = new DatasetSeries(resCsvFile.getAbsolutePath(), seriesName);
            datasetSeriesCache.put(ref, ns.data());
            seriesAdded++;
        }

        addLoadedDatasetToTree(resCsvFile);

        if (importResult.hasWarnings()) {
            StringBuilder warningMessage = new StringBuilder("res.csv loaded successfully with warnings:\n\n");
            for (String warning : importResult.getWarnings()) {
                warningMessage.append("• ").append(warning).append("\n");
            }

            JOptionPane.showMessageDialog(parentFrame, warningMessage.toString(),
                "Load Warnings", JOptionPane.WARNING_MESSAGE);
        }

        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Loaded %s: %d series, %,d total points",
                fileName, seriesAdded, importResult.getTotalPointCount()));
        }

        if (onDatasetLoadedCallback != null) {
            onDatasetLoadedCallback.run();
        }
    }

    /**
     * Loads a Pixie dataset (.pxt + .pxb) with progress dialog.
     *
     * @param pixieFile Either the .pxt or .pxb file; the matching pair is located automatically.
     */
    private void loadPixieDataset(File pixieFile) {
        if (statusUpdater != null) {
            statusUpdater.accept("Loading Pixie dataset...");
        }

        // Locate the .pxt/.pxb pair regardless of which one was provided
        String basePath = pixieFile.getAbsolutePath().replaceAll("\\.(pxt|pxb)$", "");
        File pxtFile = new File(basePath + ".pxt");
        File pxbFile = new File(basePath + ".pxb");

        // Verify both files exist
        if (!pxtFile.exists() || !pxbFile.exists()) {
            JOptionPane.showMessageDialog(parentFrame,
                "Both .pxt and .pxb files are required.\n" +
                "Missing: " + (!pxtFile.exists() ? pxtFile.getName() : pxbFile.getName()),
                "Load Error",
                JOptionPane.ERROR_MESSAGE);
            if (statusUpdater != null) {
                statusUpdater.accept("Failed to load Pixie dataset");
            }
            return;
        }

        // Create progress dialog
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading Pixie dataset...");

        JDialog progressDialog = new JDialog(parentFrame, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + pxtFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);

        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(parentFrame);

        // Create background loading task
        SwingWorker<List<com.kalix.ide.io.NamedSeries>, Integer> loadTask = new SwingWorker<>() {
            @Override
            protected List<com.kalix.ide.io.NamedSeries> doInBackground() throws Exception {
                publish(25);
                PixieReader reader = new PixieReader();
                publish(50);
                List<com.kalix.ide.io.NamedSeries> seriesList = reader.readAllSeries(basePath);
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
                    List<com.kalix.ide.io.NamedSeries> seriesList = get();

                    // Add all series to cache using hierarchical naming scheme
                    for (com.kalix.ide.io.NamedSeries ns : seriesList) {
                        // Create hierarchical series name from the series' path segments
                        String seriesName = composeDatasetSeriesName(pxtFile, ns.path());

                        logger.info("Loading Pixie series: originalName='{}' -> seriesName='{}'", ns.name(), seriesName);

                        // The importer already returns nameless data — store it directly.
                        // Key by DatasetSeries ref so the absolute path qualifies the entry.
                        DatasetSeries ref = new DatasetSeries(pxtFile.getAbsolutePath(), seriesName);
                        datasetSeriesCache.put(ref, ns.data());
                    }

                    // Add file to loaded datasets tree
                    addLoadedDatasetToTree(pxtFile);

                    // Update status
                    if (statusUpdater != null) {
                        statusUpdater.accept(String.format("Loaded %s: %d series",
                            pxtFile.getName(), seriesList.size()));
                    }

                    // Notify callback
                    if (onDatasetLoadedCallback != null) {
                        onDatasetLoadedCallback.run();
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "Error loading Pixie dataset:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Error loading Pixie dataset");
                    }
                    logger.error("Error loading Pixie dataset: " + pxtFile.getName(), e);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            loadTask.cancel(true);
            progressDialog.dispose();
            if (statusUpdater != null) {
                statusUpdater.accept("Pixie dataset load cancelled");
            }
        });

        loadTask.execute();
        progressDialog.setVisible(true);
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
     * Composes a hierarchical, dot-delimited series name from a file and the series' raw
     * hierarchy segments. Each segment is sanitised individually and the segments are joined
     * with {@code .} so the Run Manager tree ({@link OutputsTreeBuilder}) nests them as levels.
     *
     * <p>Format: {@code file.{sanitized_filename}.{sanitized_seg1}.{sanitized_seg2}…}. A
     * single-segment path (the default for flat formats like CSV/Pixie) therefore reproduces
     * the historical {@code file.<filename>.<column>} two-level name exactly.</p>
     *
     * <p>This is the one generic place where structure is assembled: the segmentation (how
     * many levels and what they are) is the importer's format-specific responsibility, carried
     * on {@link com.kalix.ide.io.NamedSeries#path()}; sanitisation and joining are uniform.</p>
     *
     * @param file The data file
     * @param segments The series' raw hierarchy segments (each trimmed and sanitised here)
     * @return The dot-delimited tree key
     */
    private String composeDatasetSeriesName(File file, List<String> segments) {
        StringBuilder sb = new StringBuilder("file.").append(sanitizeToIdentifier(file.getName()));
        for (String segment : segments) {
            String sanitized = sanitizeToIdentifier(segment.trim());
            if (!sanitized.isEmpty()) {
                sb.append('.').append(sanitized);
            }
        }
        return sb.toString();
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
