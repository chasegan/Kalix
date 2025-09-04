package com.kalix.gui.flowviz.data;

import javax.swing.SwingWorker;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

public class CsvParser {
    
    // Common date/time patterns to try
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE
    };
    
    // Pattern to detect missing values
    private static final Set<String> MISSING_VALUE_PATTERNS = Set.of(
        "", "na", "nan", "null", "n/a", "#n/a", "missing", "-", "--", "?"
    );
    
    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
        "^[+-]?([0-9]*[.])?[0-9]+([eE][+-]?[0-9]+)?$"
    );
    
    public static class CsvParseResult {
        private final DataSet dataSet;
        private final List<String> warnings;
        private final List<String> errors;
        private final ParseStatistics statistics;
        
        public CsvParseResult(DataSet dataSet, List<String> warnings, List<String> errors, ParseStatistics statistics) {
            this.dataSet = dataSet;
            this.warnings = new ArrayList<>(warnings);
            this.errors = new ArrayList<>(errors);
            this.statistics = statistics;
        }
        
        public DataSet getDataSet() { return dataSet; }
        public List<String> getWarnings() { return warnings; }
        public List<String> getErrors() { return errors; }
        public ParseStatistics getStatistics() { return statistics; }
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
    
    public static class ParseStatistics {
        private final int totalRows;
        private final int validRows;
        private final int headerRows;
        private final int seriesCount;
        private final long parseTimeMs;
        private final DateTimeFormatter detectedDateFormat;
        
        public ParseStatistics(int totalRows, int validRows, int headerRows, int seriesCount, 
                             long parseTimeMs, DateTimeFormatter detectedDateFormat) {
            this.totalRows = totalRows;
            this.validRows = validRows;
            this.headerRows = headerRows;
            this.seriesCount = seriesCount;
            this.parseTimeMs = parseTimeMs;
            this.detectedDateFormat = detectedDateFormat;
        }
        
        public int getTotalRows() { return totalRows; }
        public int getValidRows() { return validRows; }
        public int getHeaderRows() { return headerRows; }
        public int getSeriesCount() { return seriesCount; }
        public long getParseTimeMs() { return parseTimeMs; }
        public DateTimeFormatter getDetectedDateFormat() { return detectedDateFormat; }
    }
    
    public static abstract class CsvParseTask extends SwingWorker<CsvParseResult, Integer> {
        protected final File csvFile;
        protected final CsvParseOptions options;
        
        public CsvParseTask(File csvFile, CsvParseOptions options) {
            this.csvFile = csvFile;
            this.options = options != null ? options : new CsvParseOptions();
        }
        
        @Override
        protected CsvParseResult doInBackground() throws Exception {
            return parseFile();
        }
        
        private CsvParseResult parseFile() throws IOException {
            long startTime = System.currentTimeMillis();
            
            List<String> warnings = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            DataSet dataSet = new DataSet();
            
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(csvFile, StandardCharsets.UTF_8))) {
                
                // Read all lines first to determine size for progress reporting
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                
                if (lines.isEmpty()) {
                    errors.add("CSV file is empty");
                    return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
                }
                
                // Detect delimiter
                char delimiter = detectDelimiter(lines.subList(0, Math.min(10, lines.size())));
                
                // Parse header
                String[] headers = parseLine(lines.get(0), delimiter);
                if (headers.length < 2) {
                    errors.add("CSV must have at least 2 columns (time + at least one data series)");
                    return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
                }
                
                // Detect date format using first few data rows
                DateTimeFormatter dateFormat = detectDateFormat(lines, delimiter, warnings);
                if (dateFormat == null) {
                    errors.add("Could not detect date format in first column");
                    return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
                }
                
                // Prepare data structures
                int seriesCount = headers.length - 1;
                List<LocalDateTime> dateTimes = new ArrayList<>();
                List<List<Double>> seriesValues = new ArrayList<>();
                
                for (int i = 0; i < seriesCount; i++) {
                    seriesValues.add(new ArrayList<>());
                }
                
                // Parse data rows
                int validRows = 0;
                int totalRows = lines.size();
                
                for (int lineNum = 1; lineNum < lines.size(); lineNum++) {
                    if (isCancelled()) {
                        return createResult(dataSet, warnings, errors, startTime, dateFormat, totalRows, validRows, 0);
                    }
                    
                    // Report progress
                    int progress = (int) ((lineNum * 100.0) / lines.size());
                    setProgress(progress);
                    
                    String dataLine = lines.get(lineNum);
                    if (dataLine.trim().isEmpty()) continue;
                    
                    String[] values = parseLine(dataLine, delimiter);
                    if (values.length != headers.length) {
                        warnings.add(String.format("Line %d: Expected %d columns, found %d", 
                            lineNum + 1, headers.length, values.length));
                        continue;
                    }
                    
                    // Parse timestamp
                    LocalDateTime dateTime = parseDateTime(values[0].trim(), dateFormat);
                    if (dateTime == null) {
                        warnings.add(String.format("Line %d: Could not parse date/time '%s'", 
                            lineNum + 1, values[0]));
                        continue;
                    }
                    
                    // Parse data values
                    boolean hasValidData = false;
                    List<Double> rowValues = new ArrayList<>();
                    
                    for (int i = 1; i < values.length; i++) {
                        Double value = parseNumericValue(values[i].trim());
                        rowValues.add(value);
                        if (value != null && !value.isNaN()) {
                            hasValidData = true;
                        }
                    }
                    
                    if (hasValidData || !options.skipRowsWithAllMissingValues) {
                        dateTimes.add(dateTime);
                        for (int i = 0; i < seriesCount; i++) {
                            seriesValues.get(i).add(rowValues.get(i));
                        }
                        validRows++;
                    }
                }
                
                // Create TimeSeriesData objects
                if (validRows > 0) {
                    LocalDateTime[] dateTimeArray = dateTimes.toArray(new LocalDateTime[0]);
                    
                    for (int i = 0; i < seriesCount; i++) {
                        String seriesName = headers[i + 1].trim();
                        if (seriesName.isEmpty()) {
                            seriesName = "Series " + (i + 1);
                        }
                        
                        double[] valueArray = seriesValues.get(i).stream()
                            .mapToDouble(v -> v != null ? v : Double.NaN)
                            .toArray();
                        
                        TimeSeriesData series = new TimeSeriesData(seriesName, dateTimeArray, valueArray);
                        dataSet.addSeries(series);
                        
                        // Validate series
                        if (series.getValidPointCount() == null || series.getValidPointCount() == 0) {
                            warnings.add("Series '" + seriesName + "' contains no valid data points");
                        }
                    }
                } else {
                    errors.add("No valid data rows found");
                }
                
                return createResult(dataSet, warnings, errors, startTime, dateFormat, totalRows, validRows, seriesCount);
                
            } catch (Exception e) {
                errors.add("Parse error: " + e.getMessage());
                return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
            }
        }
        
        private CsvParseResult createResult(DataSet dataSet, List<String> warnings, List<String> errors,
                                          long startTime, DateTimeFormatter dateFormat, int totalRows,
                                          int validRows, int seriesCount) {
            long parseTimeMs = System.currentTimeMillis() - startTime;
            ParseStatistics stats = new ParseStatistics(totalRows, validRows, 1, seriesCount, parseTimeMs, dateFormat);
            return new CsvParseResult(dataSet, warnings, errors, stats);
        }
    }
    
    private static char detectDelimiter(List<String> sampleLines) {
        char[] candidates = {',', ';', '\t', '|'};
        int maxCount = 0;
        char bestDelimiter = ',';
        
        for (char delimiter : candidates) {
            int totalCount = 0;
            int consistentLines = 0;
            Integer expectedColumns = null;
            
            for (String line : sampleLines) {
                if (line.trim().isEmpty()) continue;
                
                int count = countDelimiters(line, delimiter);
                totalCount += count;
                
                int columns = count + 1;
                if (expectedColumns == null) {
                    expectedColumns = columns;
                    consistentLines = 1;
                } else if (expectedColumns.equals(columns)) {
                    consistentLines++;
                }
            }
            
            // Prefer delimiter with consistent column count across lines
            int score = totalCount * consistentLines;
            if (score > maxCount) {
                maxCount = score;
                bestDelimiter = delimiter;
            }
        }
        
        return bestDelimiter;
    }
    
    private static int countDelimiters(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                count++;
            }
        }
        
        return count;
    }
    
    private static String[] parseLine(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        
        tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }
    
    private static DateTimeFormatter detectDateFormat(List<String> lines, char delimiter, List<String> warnings) {
        if (lines.size() < 2) return null;
        
        // Try parsing first few data rows with different formats
        for (int lineNum = 1; lineNum < Math.min(6, lines.size()); lineNum++) {
            String[] values = parseLine(lines.get(lineNum), delimiter);
            if (values.length == 0) continue;
            
            String dateString = values[0].trim();
            if (dateString.isEmpty()) continue;
            
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                LocalDateTime result = parseDateTime(dateString, formatter);
                if (result != null) {
                    return formatter; // Found working format
                }
            }
        }
        
        return null; // No format worked
    }
    
    private static LocalDateTime parseDateTime(String dateString, DateTimeFormatter formatter) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }
        
        try {
            // Try parsing as LocalDateTime first
            return LocalDateTime.parse(dateString, formatter);
        } catch (DateTimeParseException e) {
            try {
                // If that fails, try parsing as LocalDate and convert to LocalDateTime at start of day
                java.time.LocalDate localDate = java.time.LocalDate.parse(dateString, formatter);
                return localDate.atStartOfDay();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
    
    private static Double parseNumericValue(String valueString) {
        if (valueString == null || isMissingValue(valueString)) {
            return Double.NaN;
        }
        
        // Handle common numeric formats
        String cleaned = valueString.replace(",", "").replace(" ", "");
        
        if (!NUMERIC_PATTERN.matcher(cleaned).matches()) {
            return Double.NaN;
        }
        
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
    
    private static boolean isMissingValue(String value) {
        return MISSING_VALUE_PATTERNS.contains(value.toLowerCase().trim());
    }
    
    public static class CsvParseOptions {
        public boolean skipRowsWithAllMissingValues = true;
        public boolean strictDateParsing = false;
        public int maxPreviewRows = 10000;
        
        public CsvParseOptions() {}
        
        public CsvParseOptions skipRowsWithAllMissingValues(boolean skip) {
            this.skipRowsWithAllMissingValues = skip;
            return this;
        }
        
        public CsvParseOptions strictDateParsing(boolean strict) {
            this.strictDateParsing = strict;
            return this;
        }
        
        public CsvParseOptions maxPreviewRows(int maxRows) {
            this.maxPreviewRows = maxRows;
            return this;
        }
    }
}