package com.kalix.gui.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and caches API information from kalixcli.
 * Handles API discovery, caching, and version compatibility.
 */
public class ApiDiscovery {
    
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final CliLogger logger;
    
    // Cache for API specifications
    private final Map<String, CachedApiSpec> apiCache = new ConcurrentHashMap<>();
    
    // Default cache expiration time (5 minutes)
    private long cacheExpirationMinutes = 5;
    
    /**
     * Represents a cached API specification with metadata.
     */
    private static class CachedApiSpec {
        private final ApiModel.ApiSpec apiSpec;
        private final LocalDateTime cacheTime;
        private final String cliVersion;
        private final Path cliPath;
        
        public CachedApiSpec(ApiModel.ApiSpec apiSpec, String cliVersion, Path cliPath) {
            this.apiSpec = apiSpec;
            this.cacheTime = LocalDateTime.now();
            this.cliVersion = cliVersion;
            this.cliPath = cliPath;
        }
        
        public ApiModel.ApiSpec getApiSpec() { return apiSpec; }
        public LocalDateTime getCacheTime() { return cacheTime; }
        public String getCliVersion() { return cliVersion; }
        public Path getCliPath() { return cliPath; }
        
        public boolean isExpired(long expirationMinutes) {
            return LocalDateTime.now().isAfter(cacheTime.plusMinutes(expirationMinutes));
        }
    }
    
    /**
     * Result of an API discovery operation.
     */
    public static class DiscoveryResult {
        private final boolean success;
        private final ApiModel.ApiSpec apiSpec;
        private final String cliVersion;
        private final Path cliPath;
        private final Exception error;
        private final boolean fromCache;
        
        private DiscoveryResult(boolean success, ApiModel.ApiSpec apiSpec, String cliVersion, 
                               Path cliPath, Exception error, boolean fromCache) {
            this.success = success;
            this.apiSpec = apiSpec;
            this.cliVersion = cliVersion;
            this.cliPath = cliPath;
            this.error = error;
            this.fromCache = fromCache;
        }
        
        public static DiscoveryResult success(ApiModel.ApiSpec apiSpec, String cliVersion, Path cliPath, boolean fromCache) {
            return new DiscoveryResult(true, apiSpec, cliVersion, cliPath, null, fromCache);
        }
        
        public static DiscoveryResult failure(Exception error) {
            return new DiscoveryResult(false, null, null, null, error, false);
        }
        
        public boolean isSuccess() { return success; }
        public ApiModel.ApiSpec getApiSpec() { return apiSpec; }
        public String getCliVersion() { return cliVersion; }
        public Path getCliPath() { return cliPath; }
        public Exception getError() { return error; }
        public boolean isFromCache() { return fromCache; }
    }
    
    /**
     * Creates a new ApiDiscovery instance.
     */
    public ApiDiscovery() {
        this.processExecutor = new ProcessExecutor();
        this.objectMapper = new ObjectMapper();
        this.logger = CliLogger.getInstance();
    }
    
    /**
     * Creates a new ApiDiscovery instance with custom executor.
     */
    public ApiDiscovery(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
        this.objectMapper = new ObjectMapper();
        this.logger = CliLogger.getInstance();
    }
    
    /**
     * Discovers the API specification from kalixcli.
     * Uses cache if available and not expired.
     * 
     * @return CompletableFuture containing the discovery result
     */
    public CompletableFuture<DiscoveryResult> discoverApi() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // First, try to find kalixcli (with user preferences)
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    return DiscoveryResult.failure(new RuntimeException("kalixcli not found on system"));
                }
                
                KalixCliLocator.CliLocation cli = cliLocation.get();
                Path cliPath = cli.getPath();
                String cliVersion = cli.getVersion();
                
                // Check cache first
                String cacheKey = cliPath.toString();
                CachedApiSpec cached = apiCache.get(cacheKey);
                if (cached != null && !cached.isExpired(cacheExpirationMinutes)) {
                    logger.debug("Using cached API spec for " + cliPath);
                    return DiscoveryResult.success(cached.getApiSpec(), cached.getCliVersion(), 
                                                 cached.getCliPath(), true);
                }
                
                // Discover API from CLI
                logger.info("Discovering API from kalixcli at: " + cliPath);
                return discoverApiFromCli(cliPath, cliVersion);
                
            } catch (Exception e) {
                logger.error("Error during API discovery", e);
                return DiscoveryResult.failure(e);
            }
        });
    }
    
    /**
     * Discovers API from a specific CLI path.
     */
    public CompletableFuture<DiscoveryResult> discoverApi(Path cliPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Validate CLI path
                if (!KalixCliLocator.validateKalixCli(cliPath)) {
                    return DiscoveryResult.failure(new RuntimeException("Invalid kalixcli path: " + cliPath));
                }
                
                // Get version
                String version = getCliVersion(cliPath);
                
                // Check cache
                String cacheKey = cliPath.toString();
                CachedApiSpec cached = apiCache.get(cacheKey);
                if (cached != null && !cached.isExpired(cacheExpirationMinutes)) {
                    logger.debug("Using cached API spec for " + cliPath);
                    return DiscoveryResult.success(cached.getApiSpec(), cached.getCliVersion(), 
                                                 cached.getCliPath(), true);
                }
                
                // Discover from CLI
                return discoverApiFromCli(cliPath, version);
                
            } catch (Exception e) {
                logger.error("Error discovering API from " + cliPath, e);
                return DiscoveryResult.failure(e);
            }
        });
    }
    
    /**
     * Performs the actual API discovery by calling kalixcli get-api.
     */
    private DiscoveryResult discoverApiFromCli(Path cliPath, String cliVersion) {
        try {
            logger.debug("Calling 'kalixcli get-api' at " + cliPath);
            
            ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig()
                .timeout(30) // 30 seconds should be plenty
                .onStderr(line -> logger.debug("CLI stderr: " + line));
            
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                cliPath.toString(), 
                List.of("get-api"), 
                config
            );
            
            if (!result.isSuccess()) {
                String errorMsg = String.format("kalixcli get-api failed with exit code %d: %s", 
                    result.getExitCode(), result.getStderr());
                return DiscoveryResult.failure(new RuntimeException(errorMsg));
            }
            
            String jsonOutput = result.getStdout().trim();
            if (jsonOutput.isEmpty()) {
                return DiscoveryResult.failure(new RuntimeException("kalixcli get-api returned empty output"));
            }
            
            logger.debug("Received JSON output: " + jsonOutput.substring(0, Math.min(100, jsonOutput.length())) + "...");
            
            // Parse JSON response
            ApiModel.ApiSpec apiSpec = objectMapper.readValue(jsonOutput, ApiModel.ApiSpec.class);
            
            // Validate the API spec
            if (apiSpec.getName() == null || apiSpec.getSubcommands() == null) {
                return DiscoveryResult.failure(new RuntimeException("Invalid API specification received"));
            }
            
            logger.info("Successfully discovered API: " + apiSpec.toString());
            
            // Cache the result
            CachedApiSpec cached = new CachedApiSpec(apiSpec, cliVersion, cliPath);
            apiCache.put(cliPath.toString(), cached);
            
            return DiscoveryResult.success(apiSpec, cliVersion, cliPath, false);
            
        } catch (Exception e) {
            logger.error("Failed to discover API from " + cliPath, e);
            return DiscoveryResult.failure(e);
        }
    }
    
    /**
     * Gets the CLI version for a specific path.
     */
    private String getCliVersion(Path cliPath) {
        try {
            ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig().timeout(10);
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                cliPath.toString(), 
                List.of("--version"), 
                config
            );
            
            if (result.isSuccess()) {
                return result.getStdout().trim();
            }
        } catch (Exception e) {
            logger.debug("Could not get version for " + cliPath, e);
        }
        return "unknown";
    }
    
    /**
     * Forces a refresh of the API cache for all cached CLIs.
     */
    public CompletableFuture<List<DiscoveryResult>> refreshAllApis() {
        List<CompletableFuture<DiscoveryResult>> futures = new ArrayList<>();
        
        // Get all cached paths and refresh them
        Set<String> cachedPaths = new HashSet<>(apiCache.keySet());
        
        for (String pathStr : cachedPaths) {
            Path path = Path.of(pathStr);
            futures.add(discoverApi(path));
        }
        
        // If no cached APIs, try to discover from scratch
        if (futures.isEmpty()) {
            futures.add(discoverApi());
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .toList());
    }
    
    /**
     * Clears the API cache.
     */
    public void clearCache() {
        apiCache.clear();
        logger.debug("API cache cleared");
    }
    
    /**
     * Gets all cached API specifications.
     */
    public Map<Path, ApiModel.ApiSpec> getCachedApis() {
        Map<Path, ApiModel.ApiSpec> result = new HashMap<>();
        for (CachedApiSpec cached : apiCache.values()) {
            result.put(cached.getCliPath(), cached.getApiSpec());
        }
        return result;
    }
    
    /**
     * Sets the cache expiration time in minutes.
     */
    public void setCacheExpirationMinutes(long minutes) {
        this.cacheExpirationMinutes = minutes;
    }
    
    /**
     * Gets cache statistics for debugging.
     */
    public String getCacheStats() {
        int totalCached = apiCache.size();
        int expired = (int) apiCache.values().stream()
                .filter(cached -> cached.isExpired(cacheExpirationMinutes))
                .count();
        
        return String.format("API Cache: %d total, %d expired, %d valid", 
            totalCached, expired, totalCached - expired);
    }
    
    /**
     * Finds a command by name in the API specification.
     */
    public static Optional<ApiModel.Command> findCommand(ApiModel.ApiSpec apiSpec, String commandName) {
        if (apiSpec == null || apiSpec.getSubcommands() == null) {
            return Optional.empty();
        }
        
        return apiSpec.getSubcommands().stream()
                .filter(cmd -> commandName.equals(cmd.getName()))
                .findFirst();
    }
    
    /**
     * Gets all available command names from an API specification.
     */
    public static List<String> getAvailableCommands(ApiModel.ApiSpec apiSpec) {
        if (apiSpec == null || apiSpec.getSubcommands() == null) {
            return List.of();
        }
        
        return apiSpec.getSubcommands().stream()
                .map(ApiModel.Command::getName)
                .sorted()
                .toList();
    }
    
    /**
     * Checks if a specific command is available in the API.
     */
    public static boolean isCommandAvailable(ApiModel.ApiSpec apiSpec, String commandName) {
        return findCommand(apiSpec, commandName).isPresent();
    }
}