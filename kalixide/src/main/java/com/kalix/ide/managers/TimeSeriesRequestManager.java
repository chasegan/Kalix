package com.kalix.ide.managers;

import com.kalix.ide.cli.JsonStdioProtocol;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.compression.gorilla.GorillaCompressor;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/* Wire formats accepted on get_result responses. */

/**
 * Manages asynchronous fetching and caching of timeseries data from kalixcli sessions.
 *
 * <h2>Caching Strategy</h2>
 * Cache keys use format: {@code kalixcliUid:seriesName}
 * <ul>
 *   <li>{@code kalixcliUid} - Unique ID of the Kalix CLI process (persists across runs on same session)</li>
 *   <li>{@code seriesName} - Full series name (e.g., "node.mygr4j.ds_1")</li>
 * </ul>
 *
 * <h2>IMPORTANT: Cache Invalidation</h2>
 * Because {@code kalixcliUid} persists when a session runs multiple simulations, cached data
 * becomes stale when a new run completes. Callers MUST call {@link #clearCacheForSession}
 * when a run completes to invalidate stale data. This is done by
 * {@link com.kalix.ide.windows.RunManager#refreshLastSeries}.
 *
 * <h2>Two-Level Cache</h2>
 * <ul>
 *   <li>{@code cache} - In-progress requests (CompletableFuture)</li>
 *   <li>{@code completedCache} - Completed data (TimeSeriesData)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Call {@link #requestTimeSeries} - returns CompletableFuture</li>
 *   <li>On completion, callback receives TimeSeriesData</li>
 *   <li>Data is automatically cached for future requests</li>
 *   <li>Call {@link #clearCacheForSession} when run completes to invalidate</li>
 * </ol>
 *
 * @see com.kalix.ide.windows.RunManager#refreshLastSeries
 */
public class TimeSeriesRequestManager {
    private static final Logger logger = LoggerFactory.getLogger(TimeSeriesRequestManager.class);

    // Core dependencies
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        // Lift the 20MB default string limit so large get_result payloads parse.
        m.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build());
        return m;
    }

    // Request queue - sequential processing required by STDIO protocol
    private final ExecutorService requestExecutor;
    private final BlockingQueue<TimeSeriesRequest> requestQueue;

    // === TWO-LEVEL CACHE ===
    // Keys are "kalixcliUid:seriesName" - NOTE: kalixcliUid persists across runs!
    // Must call clearCacheForSession() when a run completes to invalidate stale data
    private final Map<String, CompletableFuture<TimeSeriesData>> cache;         // In-progress requests
    private final Map<String, TimeSeriesData> completedCache;                    // Completed data

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
     * Clear all cached timeseries data for a session.
     * Call this when a new run completes on the session to invalidate stale data.
     * @param sessionKey The session key (IDE identifier)
     */
    public void clearCacheForSession(String sessionKey) {
        String kalixcliUid = getKalixcliUid(sessionKey);
        if (kalixcliUid == null) return;

        String prefix = kalixcliUid + ":";

        // Remove from completed cache
        completedCache.keySet().removeIf(key -> key.startsWith(prefix));

        // Remove from in-progress cache (cancel pending futures)
        cache.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().cancel(false);
                return true;
            }
            return false;
        });

        logger.debug("Cleared cache for session {} (kalixcliUid: {})", sessionKey, kalixcliUid);
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
            // Wire format is user-configurable via Preferences > Data & Visualization.
            String format = PreferenceManager.getFileString(PreferenceKeys.STDIO_DATA_FORMAT, "pixie");
            if (!"pixie".equals(format) && !"csv".equals(format)) {
                format = "pixie"; // defensive: unknown saved value falls back to default
            }
            String command = JsonStdioProtocol.Commands.getResult(request.seriesName, format);

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
        JsonNode response;
        try {
            response = objectMapper.readTree(jsonResponse);
        } catch (Exception e) {
            logger.error("Failed to parse JSON response", e);
            return;
        }

        String messageType = response.path("m").asText();
        if (!"res".equals(messageType)) {
            return;
        }

        String command = response.path("cmd").asText();
        if (!"get_result".equals(command)) {
            return;
        }

        JsonNode result = response.path("r");
        String seriesName = result.path("series_name").asText();
        String kalixcliUid = response.path("uid").asText();
        String cacheKey = kalixcliUid + ":" + seriesName;

        boolean success = response.path("ok").asBoolean(false);
        if (!success) {
            String errMsg = response.path("msg").asText("get_result command failed");
            logger.warn("get_result failed for '{}': {}", seriesName, errMsg);
            failPendingFuture(cacheKey, new RuntimeException("get_result failed: " + errMsg));
            return;
        }

        String dataString = result.path("data").asText();
        // Format defaults to "pixie" if the field is absent (older responses or defensive fallback).
        String format = result.path("format").asText("pixie");
        try {
            handleTimeSeriesResult(seriesName, dataString, kalixcliUid, format);
        } catch (Exception e) {
            logger.error("Failed to process get_result for '{}'", seriesName, e);
            failPendingFuture(cacheKey, e);
        }
    }

    /**
     * Remove the in-progress future for the given cacheKey and complete it exceptionally.
     * Safe to call when no future is pending.
     */
    private void failPendingFuture(String cacheKey, Throwable cause) {
        CompletableFuture<TimeSeriesData> future = cache.remove(cacheKey);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    /**
     * Handle the actual timeseries result data (common logic for both protocols)
     */
    private void handleTimeSeriesResult(String seriesName, String dataString, String kalixcliUid, String format) throws java.io.IOException {
        if (seriesName.isEmpty() || dataString.isEmpty()) {
            throw new IllegalArgumentException("Invalid timeseries response: missing series_name or data");
        }

        TimeSeriesData timeSeriesData;
        switch (format) {
            case "pixie":
                timeSeriesData = decodePixiePayload(seriesName, dataString);
                break;
            case "csv":
                timeSeriesData = decodeCsvPayload(seriesName, dataString);
                break;
            default:
                throw new IllegalArgumentException("Unsupported get_result format: '" + format + "'");
        }

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
     * Decode a base64-encoded Gorilla-compressed timeseries (Pixie wire format) into TimeSeriesData.
     * The compressed bitstream carries the timestep, count, and per-point timestamps
     * (Unix seconds), so no additional metadata is needed.
     *
     * <p>The {@code seriesName} argument is retained for diagnostic context only — it is
     * not stored on the returned {@link TimeSeriesData}. Identity in the ref-keyed pool
     * comes from the {@link com.kalix.ide.flowviz.data.SeriesRef} the caller writes under.</p>
     */
    static TimeSeriesData decodePixiePayload(String seriesName, String base64Data) throws java.io.IOException {
        // Constructor's timestep arg is only used by the encoder; decoder reads it from the bitstream.
        GorillaCompressor codec = new GorillaCompressor(0);
        List<GorillaCompressor.TimeValueDouble> points = codec.decompressDoubleBase64(base64Data);

        int n = points.size();
        LocalDateTime[] dateTimes = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            GorillaCompressor.TimeValueDouble p = points.get(i);
            // Kalix stores timestamps in offset-binary u64: signed = bits ^ 2^63
            // (mirrors Rust's wrap_to_i64 in src/tid/utils.rs).
            long signedSeconds = p.timestamp ^ Long.MIN_VALUE;
            dateTimes[i] = LocalDateTime.ofEpochSecond(signedSeconds, 0, ZoneOffset.UTC);
            values[i] = p.value;
        }
        return new TimeSeriesData(dateTimes, values);
    }

    /**
     * Decode a CSV-format timeseries payload into TimeSeriesData.
     * Format: "start_timestamp,timestep_seconds,value1,value2,..."
     *
     * <p>The {@code seriesName} argument is retained for diagnostic context only — it is
     * not stored on the returned {@link TimeSeriesData}. Identity in the ref-keyed pool
     * comes from the {@link com.kalix.ide.flowviz.data.SeriesRef} the caller writes under.</p>
     */
    static TimeSeriesData decodeCsvPayload(String seriesName, String dataString) {
        String[] parts = dataString.split(",");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid timeseries data format: need at least start_timestamp, timestep, and one value");
        }

        LocalDateTime startTime =
            LocalDateTime.parse(parts[0].trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        long timestepSeconds = Long.parseLong(parts[1].trim());

        int valueCount = parts.length - 2;
        LocalDateTime[] dateTimes = new LocalDateTime[valueCount];
        double[] values = new double[valueCount];

        for (int i = 0; i < valueCount; i++) {
            dateTimes[i] = startTime.plusSeconds(i * timestepSeconds);
            try {
                values[i] = Double.parseDouble(parts[i + 2].trim());
            } catch (NumberFormatException e) {
                values[i] = Double.NaN;
            }
        }
        return new TimeSeriesData(dateTimes, values);
    }
}