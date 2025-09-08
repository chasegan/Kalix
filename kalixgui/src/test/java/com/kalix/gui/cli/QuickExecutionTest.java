package com.kalix.gui.cli;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Quick test to verify the command execution works correctly.
 */
public class QuickExecutionTest {
    
    public static void main(String[] args) {
        CliLogger logger = CliLogger.getInstance();
        logger.setLogLevel(CliLogger.LogLevel.DEBUG);
        
        logger.info("Quick test of command execution");
        
        try {
            // Direct test with ProcessExecutor
            ProcessExecutor executor = new ProcessExecutor();
            
            // Test the get-api command that we know works
            ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig()
                .timeout(10)
                .onStdout(line -> logger.debug("STDOUT: " + line))
                .onStderr(line -> logger.debug("STDERR: " + line));
            
            String cliPath = "/Users/chas/github/Kalix/target/release/kalixcli";
            
            logger.info("Executing: " + cliPath + " get-api");
            ProcessExecutor.ProcessResult result = executor.execute(cliPath, List.of("get-api"), config);
            
            logger.info("Execution result:");
            logger.info("  Success: " + result.isSuccess());
            logger.info("  Exit code: " + result.getExitCode());
            logger.info("  Output length: " + result.getStdout().length());
            logger.info("  Error length: " + result.getStderr().length());
            
            if (result.isSuccess()) {
                logger.info("✓ Direct ProcessExecutor test successful");
                
                // Now test with CommandExecutor
                logger.info("Testing with CommandExecutor...");
                CommandExecutor cmdExecutor = new CommandExecutor(Paths.get(cliPath));
                
                CommandExecutor.ExecutionConfig execConfig = new CommandExecutor.ExecutionConfig()
                    .timeout(10)
                    .parseJson(true)
                    .onOutput(line -> logger.debug("CMD Output: " + line));
                
                // Execute get-api command args: [get-api] (the CLI path is handled separately)
                CompletableFuture<CommandExecutor.ExecutionResult> future = 
                    cmdExecutor.execute(List.of("get-api"), execConfig);
                CommandExecutor.ExecutionResult execResult = future.join();
                
                logger.info("CommandExecutor result:");
                logger.info("  Success: " + execResult.isSuccess());
                logger.info("  Exit code: " + execResult.getExitCode());
                logger.info("  Execution time: " + execResult.getExecutionTimeMs() + "ms");
                logger.info("  Messages: " + execResult.getMessages());
                logger.info("  Parsed data keys: " + execResult.getParsedData().keySet());
                
                if (execResult.isSuccess()) {
                    logger.info("✓ CommandExecutor test successful");
                    
                    // Test building and executing a command through the full pipeline
                    logger.info("Testing full command building pipeline...");
                    testFullPipeline(logger);
                    
                } else {
                    logger.error("✗ CommandExecutor test failed");
                }
                
                cmdExecutor.shutdown();
            } else {
                logger.error("✗ Direct ProcessExecutor test failed");
            }
            
            executor.shutdown();
            
        } catch (Exception e) {
            logger.error("Test failed with exception", e);
        }
    }
    
    private static void testFullPipeline(CliLogger logger) {
        try {
            // 1. Discover API
            ApiDiscovery discovery = new ApiDiscovery();
            ApiDiscovery.DiscoveryResult discoveryResult = discovery.discoverApi().join();
            
            if (!discoveryResult.isSuccess()) {
                logger.error("API discovery failed");
                return;
            }
            
            // 2. Build command
            CommandBuilder builder = new CommandBuilder(discoveryResult.getApiSpec(), "get-api");
            CommandBuilder.BuildResult buildResult = builder.build();
            
            if (!buildResult.isValid()) {
                logger.error("Command build failed: " + buildResult.getErrors());
                return;
            }
            
            logger.info("Built command args: " + buildResult.getCommandArgs());
            
            // 3. Execute command
            CommandExecutor executor = new CommandExecutor(discoveryResult.getCliPath());
            CommandExecutor.ExecutionConfig config = new CommandExecutor.ExecutionConfig()
                .timeout(10)
                .parseJson(true);
            
            CommandExecutor.ExecutionResult result = executor.execute(buildResult, config).join();
            
            logger.info("Full pipeline result:");
            logger.info("  Success: " + result.isSuccess());
            logger.info("  Execution time: " + result.getExecutionTimeMs() + "ms");
            logger.info("  Parsed data: " + result.getParsedData().keySet());
            
            if (result.isSuccess() && result.hasData("api_spec")) {
                logger.info("✓ Full pipeline test successful - API spec parsed!");
            } else {
                logger.warn("✗ Full pipeline test - no API spec in parsed data");
                logger.info("  Messages: " + result.getMessages());
            }
            
            executor.shutdown();
            
        } catch (Exception e) {
            logger.error("Full pipeline test failed", e);
        }
    }
}