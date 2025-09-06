package com.kalix.gui.cli;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Test class for Phase 2 API Discovery functionality.
 */
public class ApiDiscoveryTest {
    
    public static void main(String[] args) {
        CliLogger logger = CliLogger.getInstance();
        logger.setLogLevel(CliLogger.LogLevel.DEBUG);
        
        logger.info("Starting Phase 2 API Discovery test");
        
        // Test 1: Version Compatibility
        testVersionCompatibility(logger);
        
        // Test 2: API Discovery
        testApiDiscovery(logger);
        
        logger.info("Phase 2 API Discovery test completed");
    }
    
    private static void testVersionCompatibility(CliLogger logger) {
        logger.info("Testing version compatibility...");
        
        try {
            // Test version parsing
            String[] testVersions = {
                "kalixcli 0.1.0",
                "0.1.0",
                "1.2.3-beta",
                "2.0.0+build.123",
                "invalid.version"
            };
            
            for (String versionString : testVersions) {
                Optional<VersionCompatibility.Version> version = VersionCompatibility.parseVersion(versionString);
                if (version.isPresent()) {
                    logger.info("Parsed version '" + versionString + "' -> " + version.get());
                    
                    // Test compatibility
                    VersionCompatibility.CompatibilityResult compatibility = 
                        VersionCompatibility.checkCompatibility(versionString);
                    logger.info("  Compatibility: " + compatibility);
                    logger.info("  Can proceed: " + compatibility.canProceed());
                } else {
                    logger.warn("Failed to parse version: " + versionString);
                }
            }
            
            // Test feature support
            VersionCompatibility.Version v010 = new VersionCompatibility.Version(0, 1, 0);
            logger.info("Version 0.1.0 supports 'sim': " + 
                VersionCompatibility.supportsFeature(v010, "sim"));
            logger.info("Version 0.1.0 supports 'get-api': " + 
                VersionCompatibility.supportsFeature(v010, "get-api"));
            
        } catch (Exception e) {
            logger.error("Error in version compatibility test", e);
        }
    }
    
    private static void testApiDiscovery(CliLogger logger) {
        logger.info("Testing API discovery...");
        
        try {
            ApiDiscovery discovery = new ApiDiscovery();
            
            // Test API discovery
            CompletableFuture<ApiDiscovery.DiscoveryResult> future = discovery.discoverApi();
            ApiDiscovery.DiscoveryResult result = future.join();
            
            if (result.isSuccess()) {
                logger.info("✓ API discovery successful!");
                logger.info("  CLI Path: " + result.getCliPath());
                logger.info("  CLI Version: " + result.getCliVersion());
                logger.info("  From Cache: " + result.isFromCache());
                
                ApiModel.ApiSpec apiSpec = result.getApiSpec();
                logger.info("  API Spec: " + apiSpec);
                
                // Test command discovery
                logger.info("Available commands:");
                for (String cmdName : ApiDiscovery.getAvailableCommands(apiSpec)) {
                    Optional<ApiModel.Command> cmd = ApiDiscovery.findCommand(apiSpec, cmdName);
                    if (cmd.isPresent()) {
                        ApiModel.Command command = cmd.get();
                        logger.info("    " + cmdName + ": " + command.getAbout());
                        
                        // Show command arguments
                        if (command.hasArguments()) {
                            logger.info("      Arguments:");
                            for (ApiModel.CommandArgument arg : command.getArgs()) {
                                String flags = arg.getAllFlags().isEmpty() ? "positional" : 
                                    String.join(", ", arg.getAllFlags());
                                logger.info("        " + arg.getName() + " (" + flags + "): " + 
                                    arg.getHelp() + (arg.isRequired() ? " [required]" : " [optional]"));
                            }
                        }
                    }
                }
                
                // Test specific command checks
                logger.info("Command availability checks:");
                logger.info("  'sim' available: " + ApiDiscovery.isCommandAvailable(apiSpec, "sim"));
                logger.info("  'calibrate' available: " + ApiDiscovery.isCommandAvailable(apiSpec, "calibrate"));
                logger.info("  'nonexistent' available: " + ApiDiscovery.isCommandAvailable(apiSpec, "nonexistent"));
                
                // Test cache functionality
                logger.info("Cache stats: " + discovery.getCacheStats());
                
                // Test second discovery (should use cache)
                logger.info("Testing cached discovery...");
                CompletableFuture<ApiDiscovery.DiscoveryResult> cachedFuture = discovery.discoverApi();
                ApiDiscovery.DiscoveryResult cachedResult = cachedFuture.join();
                
                if (cachedResult.isSuccess()) {
                    logger.info("✓ Cached discovery successful!");
                    logger.info("  From Cache: " + cachedResult.isFromCache());
                } else {
                    logger.error("✗ Cached discovery failed: " + cachedResult.getError().getMessage());
                }
                
            } else {
                logger.error("✗ API discovery failed: " + result.getError().getMessage());
            }
            
        } catch (Exception e) {
            logger.error("Error in API discovery test", e);
        }
    }
}