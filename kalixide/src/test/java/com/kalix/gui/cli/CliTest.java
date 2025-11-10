package com.kalix.ide.cli;

import java.util.List;
import java.util.Optional;

/**
 * Simple test class to demonstrate Phase 1 CLI integration functionality.
 * This can be removed once Phase 2 is implemented.
 */
public class CliTest {
    
    public static void main(String[] args) {
        StdioLogger logger = StdioLogger.getInstance();
        logger.setLogLevel(StdioLogger.LogLevel.DEBUG);
        
        logger.info("Starting CLI integration test");
        
        // Test 1: CLI Location
        testCliLocation(logger);
        
        // Test 2: Process Execution (if CLI found)
        testProcessExecution(logger);
        
        logger.info("CLI integration test completed");
    }
    
    private static void testCliLocation(StdioLogger logger) {
        logger.info("Testing CLI location detection...");
        
        try {
            Optional<KalixCliLocator.CliLocation> location = KalixCliLocator.findKalixCliWithPreferences();

            if (location.isPresent()) {
                KalixCliLocator.CliLocation cli = location.get();
                logger.info("Found kalixcli: " + cli);
                
                // Validate the CLI
                boolean isValid = KalixCliLocator.validateKalixCli(cli.getPath());
                logger.info("CLI validation: " + (isValid ? "PASSED" : "FAILED"));
                
            } else {
                logger.warn("kalixcli not found on this system");
                logger.info("This is expected if kalixcli is not installed");
                
                // Show all search attempts
                List<KalixCliLocator.CliLocation> allInstalls = KalixCliLocator.findAllInstallations();
                logger.info("Total installations found: " + allInstalls.size());
            }
            
        } catch (Exception e) {
            logger.error("Error during CLI location test", e);
        }
    }
    
    private static void testProcessExecution(StdioLogger logger) {
        logger.info("Testing process execution...");
        
        try {
            ProcessExecutor executor = new ProcessExecutor();
            
            // Test with a simple system command that should work on all platforms
            String testCommand = "echo";
            String testArg = "Hello from ProcessExecutor!";
            
            logger.debug("Executing test command: " + testCommand + " " + testArg);
            
            ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig()
                .timeout(10)
                .onStdout(line -> logger.debug("STDOUT: " + line))
                .onStderr(line -> logger.debug("STDERR: " + line))
                .onProgress(progress -> logger.info("Progress: " + progress));
            
            ProcessExecutor.ProcessResult result = executor.execute(testCommand, List.of(testArg), config);
            
            logger.info("Process execution result:");
            logger.info("  Exit code: " + result.getExitCode());
            logger.info("  Success: " + result.isSuccess());
            logger.info("  Cancelled: " + result.wasCancelled());
            logger.info("  STDOUT: " + result.getStdout().trim());
            logger.info("  STDERR: " + result.getStderr().trim());
            
            if (result.getException() != null) {
                logger.error("  Exception: " + result.getException().getMessage());
            }
            
            executor.shutdown();
            
        } catch (Exception e) {
            logger.error("Error during process execution test", e);
        }
    }
}