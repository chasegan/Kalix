package com.kalix.gui.cli;

import java.util.concurrent.CompletableFuture;

/**
 * Test class for Phase 3 Dynamic Command Execution functionality.
 */
public class CommandExecutionTest {
    
    public static void main(String[] args) {
        CliLogger logger = CliLogger.getInstance();
        logger.setLogLevel(CliLogger.LogLevel.DEBUG);
        
        logger.info("Starting Phase 3 Dynamic Command Execution test");
        
        // Test the complete pipeline: Discovery -> Building -> Execution
        testCompleteCommandPipeline(logger);
        
        logger.info("Phase 3 Dynamic Command Execution test completed");
    }
    
    private static void testCompleteCommandPipeline(CliLogger logger) {
        try {
            // Step 1: Discover API
            logger.info("Step 1: Discovering API...");
            ApiDiscovery discovery = new ApiDiscovery();
            CompletableFuture<ApiDiscovery.DiscoveryResult> discoveryFuture = discovery.discoverApi();
            ApiDiscovery.DiscoveryResult discoveryResult = discoveryFuture.join();
            
            if (!discoveryResult.isSuccess()) {
                logger.error("API discovery failed: " + discoveryResult.getError().getMessage());
                return;
            }
            
            ApiModel.ApiSpec apiSpec = discoveryResult.getApiSpec();
            logger.info("✓ API discovered: " + apiSpec);
            
            // Step 2: Test Command Building
            logger.info("Step 2: Testing command building...");
            testCommandBuilding(apiSpec, logger);
            
            // Step 3: Test Command Execution
            logger.info("Step 3: Testing command execution...");
            testCommandExecution(discoveryResult, logger);
            
        } catch (Exception e) {
            logger.error("Error in complete command pipeline test", e);
        }
    }
    
    private static void testCommandBuilding(ApiModel.ApiSpec apiSpec, CliLogger logger) {
        logger.info("Testing command building with validation...");
        
        // Test 1: Build a valid simulation command
        logger.info("Test 1: Building simulation command");
        CommandBuilder simBuilder = CommandBuilder.forSimulation(apiSpec);
        logger.info("Command info:\n" + simBuilder.getCommandInfo());
        
        // Build without parameters (should work since model_file is optional)
        CommandBuilder.BuildResult simResult = simBuilder.build();
        logger.info("Sim build result: " + simResult);
        if (simResult.isValid()) {
            logger.info("✓ Valid sim command: " + simResult.getCommandString());
        } else {
            logger.warn("✗ Sim validation errors: " + simResult.getErrors());
        }
        
        // Test 2: Build a calibration command with parameters
        logger.info("Test 2: Building calibration command with parameters");
        CommandBuilder calibBuilder = CommandBuilder.forCalibration(apiSpec);
        CommandBuilder.BuildResult calibResult = calibBuilder
            .withParameter("config", "test-config.toml")
            .withParameter("iterations", "100")
            .build();
        
        logger.info("Calibration build result: " + calibResult);
        if (calibResult.isValid()) {
            logger.info("✓ Valid calibration command: " + calibResult.getCommandString());
        } else {
            logger.warn("✗ Calibration validation errors: " + calibResult.getErrors());
        }
        
        // Test 3: Build test command (no parameters)
        logger.info("Test 3: Building test command");
        CommandBuilder testBuilder = CommandBuilder.forTest(apiSpec);
        CommandBuilder.BuildResult testResult = testBuilder.build();
        
        logger.info("Test build result: " + testResult);
        if (testResult.isValid()) {
            logger.info("✓ Valid test command: " + testResult.getCommandString());
        } else {
            logger.warn("✗ Test validation errors: " + testResult.getErrors());
        }
        
        // Test 4: Build invalid command (demonstrate validation)
        logger.info("Test 4: Building invalid command to test validation");
        CommandBuilder invalidBuilder = CommandBuilder.forCalibration(apiSpec);
        CommandBuilder.BuildResult invalidResult = invalidBuilder
            .withParameter("nonexistent_param", "value")
            .withParameter("iterations", "not_a_number")
            .build();
        
        logger.info("Invalid build result: " + invalidResult);
        if (!invalidResult.isValid()) {
            logger.info("✓ Validation correctly caught errors: " + invalidResult.getErrors());
        } else {
            logger.warn("✗ Validation should have failed");
        }
    }
    
    private static void testCommandExecution(ApiDiscovery.DiscoveryResult discoveryResult, CliLogger logger) {
        logger.info("Testing command execution with result parsing...");
        
        try {
            CommandExecutor executor = new CommandExecutor(discoveryResult.getCliPath());
            ApiModel.ApiSpec apiSpec = discoveryResult.getApiSpec();
            
            // Test 1: Execute get-api command (we know this works)
            logger.info("Test 1: Executing get-api command");
            CommandBuilder apiBuilder = new CommandBuilder(apiSpec, "get-api");
            CommandBuilder.BuildResult apiCommand = apiBuilder.build();
            
            if (apiCommand.isValid()) {
                CommandExecutor.ExecutionConfig config = new CommandExecutor.ExecutionConfig()
                    .timeout(30)
                    .parseJson(true)
                    .onOutput(line -> logger.debug("API Output: " + line))
                    .onError(line -> logger.debug("API Error: " + line));
                
                CompletableFuture<CommandExecutor.ExecutionResult> apiFuture = 
                    executor.execute(apiCommand, config);
                CommandExecutor.ExecutionResult apiResult = apiFuture.join();
                
                logger.info("API execution result: " + apiResult);
                if (apiResult.isSuccess()) {
                    logger.info("✓ get-api executed successfully");
                    logger.info("  Execution time: " + apiResult.getExecutionTimeMs() + "ms");
                    logger.info("  Messages: " + apiResult.getMessages());
                    logger.info("  Parsed data keys: " + apiResult.getParsedData().keySet());
                    
                    if (apiResult.hasData("api_spec")) {
                        logger.info("  ✓ API spec was parsed from JSON");
                    }
                } else {
                    logger.warn("✗ get-api execution failed: " + apiResult.getMessages());
                }
            }
            
            // Test 2: Execute test command
            logger.info("Test 2: Executing test command");
            CommandBuilder testBuilder = CommandBuilder.forTest(apiSpec);
            CommandBuilder.BuildResult testCommand = testBuilder.build();
            
            if (testCommand.isValid()) {
                CommandExecutor.ExecutionConfig config = new CommandExecutor.ExecutionConfig()
                    .timeout(60) // Tests might take longer
                    .onOutput(line -> logger.debug("Test Output: " + line))
                    .onError(line -> logger.debug("Test Error: " + line))
                    .onProgress(progress -> logger.info("Test Progress: " + progress));
                
                CompletableFuture<CommandExecutor.ExecutionResult> testFuture = 
                    executor.execute(testCommand, config);
                CommandExecutor.ExecutionResult testResult = testFuture.join();
                
                logger.info("Test execution result: " + testResult);
                if (testResult.isSuccess()) {
                    logger.info("✓ test executed successfully");
                    logger.info("  Execution time: " + testResult.getExecutionTimeMs() + "ms");
                    logger.info("  Messages: " + testResult.getMessages());
                    logger.info("  Parsed data: " + testResult.getParsedData());
                } else {
                    logger.warn("✗ test execution failed: " + testResult.getMessages());
                    logger.info("  This is expected if kalixcli test requires specific setup");
                }
            }
            
            // Test 3: Execute simulation command (might fail if no model file)
            logger.info("Test 3: Executing simulation command (may fail without model)");
            CommandBuilder simBuilder = CommandBuilder.forSimulation(apiSpec);
            CommandBuilder.BuildResult simCommand = simBuilder.build();
            
            if (simCommand.isValid()) {
                CommandExecutor.ExecutionConfig config = new CommandExecutor.ExecutionConfig()
                    .timeout(30)
                    .onOutput(line -> logger.debug("Sim Output: " + line))
                    .onError(line -> logger.debug("Sim Error: " + line))
                    .onProgress(progress -> logger.info("Sim Progress: " + progress));
                
                CompletableFuture<CommandExecutor.ExecutionResult> simFuture = 
                    executor.execute(simCommand, config);
                CommandExecutor.ExecutionResult simResult = simFuture.join();
                
                logger.info("Simulation execution result: " + simResult);
                if (simResult.isSuccess()) {
                    logger.info("✓ simulation executed successfully");
                    logger.info("  Execution time: " + simResult.getExecutionTimeMs() + "ms");
                    logger.info("  Messages: " + simResult.getMessages());
                    logger.info("  Parsed data: " + simResult.getParsedData());
                } else {
                    logger.info("◯ simulation failed (expected without model file)");
                    logger.info("  Exit code: " + simResult.getExitCode());
                    logger.info("  Messages: " + simResult.getMessages());
                }
            }
            
            // Test 4: Test error handling with invalid command
            logger.info("Test 4: Testing error handling with invalid arguments");
            try {
                CommandBuilder invalidBuilder = new CommandBuilder(apiSpec, "nonexistent-command");
                // This should throw an exception
                logger.warn("✗ Should have thrown exception for nonexistent command");
            } catch (IllegalArgumentException e) {
                logger.info("✓ Correctly threw exception for nonexistent command: " + e.getMessage());
            }
            
            executor.shutdown();
            
        } catch (Exception e) {
            logger.error("Error in command execution test", e);
        }
    }
}