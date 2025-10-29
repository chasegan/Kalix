package com.kalix.ide.linter.performance;

import com.kalix.ide.linter.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * High-performance LRU cache for validation results with TTL support.
 * Provides fast lookup for previously validated content.
 */
public class ValidationResultCache {

    private static final Logger logger = LoggerFactory.getLogger(ValidationResultCache.class);

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    // Configuration
    private final int maxSize;
    private final long ttlMs;

    // Performance counters
    private volatile long hits = 0;
    private volatile long misses = 0;
    private volatile long evictions = 0;

    private static class CacheEntry {
        final ValidationResult result;
        final long timestamp;
        final String contentHash;
        volatile long lastAccessed;

        CacheEntry(ValidationResult result, String contentHash) {
            this.result = result;
            this.contentHash = contentHash;
            this.timestamp = System.currentTimeMillis();
            this.lastAccessed = timestamp;
        }

        boolean isExpired(long ttlMs) {
            return (System.currentTimeMillis() - timestamp) > ttlMs;
        }

        void updateAccess() {
            this.lastAccessed = System.currentTimeMillis();
        }
    }

    public ValidationResultCache(int maxSize, long ttlMs) {
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;

        // Schedule periodic cleanup
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ValidationCache-Cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Create default cache with reasonable defaults.
     */
    public static ValidationResultCache createDefault() {
        return new ValidationResultCache(100, TimeUnit.MINUTES.toMillis(5)); // 100 entries, 5 min TTL
    }

    /**
     * Get cached validation result if available and not expired.
     */
    public ValidationResult get(String content) {
        String contentHash = computeContentHash(content);
        CacheEntry entry = cache.get(contentHash);

        if (entry == null) {
            misses++;
            return null;
        }

        if (entry.isExpired(ttlMs)) {
            cache.remove(contentHash);
            misses++;
            evictions++;
            return null;
        }

        entry.updateAccess();
        hits++;        return cloneValidationResult(entry.result); // Return defensive copy
    }

    /**
     * Store validation result in cache.
     */
    public void put(String content, ValidationResult result) {
        if (result == null) {
            return;
        }

        String contentHash = computeContentHash(content);
        CacheEntry entry = new CacheEntry(cloneValidationResult(result), contentHash);

        cache.put(contentHash, entry);

        // Enforce size limit with LRU eviction
        if (cache.size() > maxSize) {
            evictOldest();
        }    }

    /**
     * Clear all cached results.
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
    }

    /**
     * Get cache hit ratio for monitoring.
     */
    public double getHitRatio() {
        long totalRequests = hits + misses;
        return totalRequests > 0 ? (double) hits / totalRequests : 0.0;
    }

    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getStats() {
        return new CacheStats(cache.size(), hits, misses, evictions, getHitRatio());
    }

    public static class CacheStats {
        public final int size;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final double hitRatio;

        public CacheStats(int size, long hits, long misses, long evictions, double hitRatio) {
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.hitRatio = hitRatio;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{size=%d, hits=%d, misses=%d, evictions=%d, hitRatio=%.2f}",
                               size, hits, misses, evictions, hitRatio);
        }
    }

    private void cleanup() {
        try {
            long now = System.currentTimeMillis();
            int initialSize = cache.size();

            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(ttlMs));

            int removed = initialSize - cache.size();
            if (removed > 0) {
                evictions += removed;
            }
        } catch (Exception e) {
            logger.warn("Error during cache cleanup", e);
        }
    }

    private void evictOldest() {
        // Find and remove the least recently accessed entry
        String oldestKey = null;
        long oldestAccess = Long.MAX_VALUE;

        for (var entry : cache.entrySet()) {
            if (entry.getValue().lastAccessed < oldestAccess) {
                oldestAccess = entry.getValue().lastAccessed;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            cache.remove(oldestKey);
            evictions++;        }
    }

    private String computeContentHash(String content) {
        // Simple but effective hash for content deduplication
        // Note: For production, consider using a proper hash like SHA-256
        return String.valueOf(content.hashCode());
    }

    private ValidationResult cloneValidationResult(ValidationResult original) {
        // Create a defensive copy to prevent cache corruption
        ValidationResult copy = new ValidationResult();
        for (var issue : original.getIssues()) {
            copy.addIssue(issue);
        }
        return copy;
    }

    /**
     * Shutdown the cache and cleanup resources.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        clear();
    }
}