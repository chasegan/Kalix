package com.kalix.ide.managers;

import com.kalix.ide.cli.JsonStdioProtocol;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages asynchronous fetching and caching of timeseries data from kalixcli sessions.
 *
 * Key features:
 * - Sequential request processing (required by STDIO protocol)
 * - Intelligent caching to avoid redundant requests
 * - Automatic parsing of JSON responses with comma-separated timeseries data
 * - Progress callbacks for UI updates
 *
 * Usage:
 * 1. Create manager with session reference
 * 2. Call requestTimeSeries() for leaf node selections
 * 3. Receive callbacks when data is available
 * 4. Access cached data via getTimeSeriesFromCache()
 */
public class TimeSeriesRequestManager {
    private static final Logger logger = LoggerFactory.getLogger(TimeSeriesRequestManager.class);

    // Core dependencies
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Request queue (sequential processing)
    private final ExecutorService requestExecutor;
    private final BlockingQueue<TimeSeriesRequest> requestQueue;

    // Caching system
    private final Map<String, CompletableFuture<TimeSeriesData>> cache;
    private final Map<String, TimeSeriesData> completedCache;

    /**
     * Represents a timeseries fetch request
     */
    private static class TimeSeriesRequest {
        final String sessionKey;
        final String seriesName;
        final CompletableFuture<TimeSeriesData> future;

        TimeSeriesRequest(String sessionKey, String seriesName, CompletableFuture<TimeSeriesData> future) {
            this.sessionKey = sessionKey;
            this.seriesName = seriesName;
            this.future = future;
        }
    }

    public TimeSeriesRequestManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.requestExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TimeSeries-Request-Thread");
            t.setDaemon(true);
            return t;
        });
        this.requestQueue = new LinkedBlockingQueue<>();
        this.cache = new ConcurrentHashMap<>();
        this.completedCache = new ConcurrentHashMap<>();

        // Start the request processing loop
        startRequestProcessor();
    }

    /**
     * Request timeseries data for a specific output series.
     * Returns immediately with a CompletableFuture that will complete when data is available.
     *
     * @param sessionKey The session containing the model run
     * @param seriesName The full series name (e.g., "node.my_gr4j_node.dsflow")
     * @return CompletableFuture that completes with TimeSeriesData or fails with exception
     */
    public CompletableFuture<TimeSeriesData> requestTimeSeries(String sessionKey, String seriesName) {
        // Get the Kalixcli UID for consistent cache keys
        String kalixcliUid = getKalixcliUid(sessionKey);
        if (kalixcliUid == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Session not found or Kalixcli UID not available: " + sessionKey));
        }

        // Create cache key using Kalixcli UID
        String cacheKey = kalixcliUid + ":" + seriesName;

        // Check if already in cache or being processed
        CompletableFuture<TimeSeriesData> existingRequest = cache.get(cacheKey);
        if (existingRequest != null) {
            return existingRequest;
        }

        // Check completed cache
        TimeSeriesData cachedData = completedCache.get(cacheKey);
        if (cachedData != null) {
            return CompletableFuture.completedFuture(cachedData);
        }

        // Create new request
        CompletableFuture<TimeSeriesData> future = new CompletableFuture<>();
        cache.put(cacheKey, future);

        TimeSeriesRequest request = new TimeSeriesRequest(sessionKey, seriesName, future);

        try {
            requestQueue.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
            cache.remove(cacheKey);
        }

        return future;
    }

    /**
     * Get timeseries data from cache if available
     * @param sessionKey The session key (IDE identifier)
     * @param seriesName The series name
     * @return TimeSeriesData if cached, null otherwise
     */
    public TimeSeriesData getTimeSeriesFromCache(String sessionKey, String seriesName) {
        String kalixcliUid = getKalixcliUid(sessionKey);
        if (kalixcliUid == null) return null;

        String cacheKey = kalixcliUid + ":" + seriesName;
        return completedCache.get(cacheKey);
    }

    /**
     * Check if a timeseries is currently being fetched
     * @param sessionKey The session key (IDE identifier)
     * @param seriesName The series name
     * @return true if request is in progress
     */
    public boolean isRequestInProgress(String sessionKey, String seriesName) {
        String kalixcliUid = getKalixcliUid(sessionKey);
        if (kalixcliUid == null) return false;

        String cacheKey = kalixcliUid + ":" + seriesName;
        CompletableFuture<TimeSeriesData> future = cache.get(cacheKey);
        return future != null && !future.isDone();
    }

    /**
     * Helper method to get Kalixcli UID from sessionKey
     */
    private String getKalixcliUid(String sessionKey) {
        try {
            return sessionManager.getSession(sessionKey)
                    .map(SessionManager.KalixSession::getKalixcliUid)
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to get Kalixcli UID for session key: {}", sessionKey);
            return null;
        }
    }

    /**
     * Start the background thread that processes requests sequentially
     */
    private void startRequestProcessor() {
        requestExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeSeriesRequest request = requestQueue.take();
                    processRequest(request);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in request processor", e);
                }
            }
        });
    }

    /**
     * Process a single timeseries request
     */
    private void processRequest(TimeSeriesRequest request) {
        // Get Kalixcli UID for consistent cache key
        String kalixcliUid = getKalixcliUid(request.sessionKey);
        if (kalixcliUid == null) {
            request.future.completeExceptionally(new RuntimeException("Kalixcli UID not available for: " + request.sessionKey));
            return;
        }

        String cacheKey = kalixcliUid + ":" + request.seriesName;

        try {
            // Create the get_result command
            String command = JsonStdioProtocol.Commands.getResult(request.seriesName, "csv");

            // Send command
            sessionManager.sendCommand(request.sessionKey, command)
                .exceptionally(throwable -> {
                    logger.error("Failed to send timeseries request for {}", request.seriesName, throwable);
                    cache.remove(cacheKey);
                    request.future.completeExceptionally(throwable);
                    return null;
                });

        } catch (Exception e) {
            logger.error("Failed to process timeseries request for {}", request.seriesName, e);
            cache.remove(cacheKey);
            request.future.completeExceptionally(e);
        }
    }

    /**
     * Handle a JSON response from kalixcli and parse timeseries data if it's a get_result response
     * Call this method when you receive JSON responses from the session
     */
    public void handleJsonResponse(String jsonResponse) {
        try {
            JsonNode response = objectMapper.readTree(jsonResponse);

            // Handle compact protocol format first
            String messageType = response.path("m").asText();
            if ("res".equals(messageType)) {
                // Compact protocol format
                String command = response.path("cmd").asText();
                if (!"get_result".equals(command)) {
                    return;
                }

                // Check if the command was successful
                boolean success = response.path("ok").asBoolean(false);
                if (!success) {
                    logger.warn("get_result command failed");
                    return;
                }

                JsonNode result = response.path("r");
                String seriesName = result.path("series_name").asText();
                String dataString = result.path("data").asText();
                String kalixcliUid = response.path("uid").asText();

                handleTimeSeriesResult(seriesName, dataString, kalixcliUid);
            }

            // No legacy protocol support - all messages should be compact format

        } catch (Exception e) {
            logger.error("Failed to handle JSON response", e);
        }
    }

    /**
     * Handle the actual timeseries result data (common logic for both protocols)
     */
    private void handleTimeSeriesResult(String seriesName, String dataString, String kalixcliUid) {
        if (seriesName.isEmpty() || dataString.isEmpty()) {
            logger.warn("Invalid timeseries response: missing series_name or data");
            return;
        }

        // Parse the timeseries data
        TimeSeriesData timeSeriesData = parseTimeSeriesData(seriesName, dataString);

        // Update cache
        String cacheKey = kalixcliUid + ":" + seriesName;
        completedCache.put(cacheKey, timeSeriesData);

        CompletableFuture<TimeSeriesData> future = cache.remove(cacheKey);
        if (future != null) {
            future.complete(timeSeriesData);
        } else {
            logger.warn("No pending future found for cacheKey: '{}'", cacheKey);
        }
    }

    /**
     * Parse timeseries data from the comma-separated format
     * Format: "start_timestamp,timestep_seconds,value1,value2,value3,..."
     */
    private TimeSeriesData parseTimeSeriesData(String seriesName, String dataString) {
        String[] parts = dataString.split(",");

        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid timeseries data format: need at least start_timestamp, timestep, and one value");
        }

        // Parse start timestamp
        String startTimestampStr = parts[0].trim();
        LocalDateTime startTime = LocalDateTime.parse(startTimestampStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Parse timestep in seconds
        long timestepSeconds = Long.parseLong(parts[1].trim());

        // Parse values
        int valueCount = parts.length - 2;
        LocalDateTime[] dateTimes = new LocalDateTime[valueCount];
        double[] values = new double[valueCount];

        for (int i = 0; i < valueCount; i++) {
            // Calculate timestamp for this data point
            dateTimes[i] = startTime.plusSeconds(i * timestepSeconds);

            // Parse value
            try {
                values[i] = Double.parseDouble(parts[i + 2].trim());
            } catch (NumberFormatException e) {
                values[i] = Double.NaN; // Handle invalid values
            }
        }

        return new TimeSeriesData(seriesName, dateTimes, values);
    }
}