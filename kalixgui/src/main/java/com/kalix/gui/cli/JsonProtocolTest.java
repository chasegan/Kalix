package com.kalix.gui.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Test class to demonstrate and verify the new JSON-based kalixcli communication protocol.
 * This replaces the old line-based protocol testing.
 */
public class JsonProtocolTest {
    
    private static final String SAMPLE_MODEL = """
        # Sample hydrological model for testing
        [General]
        name = test_model
        description = Simple test model for JSON protocol verification
        
        [Parameters]
        timestep = 1
        duration = 10
        
        [Components]
        # Basic components for testing
        outlet = simple_outlet
        """;
    
    public static void main(String[] args) {
        System.out.println("Starting JSON Protocol Test");
        System.out.println("==========================");
        
        try {
            // Find kalixcli executable
            Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCli();
            if (cliLocation.isEmpty()) {
                System.err.println("ERROR: kalixcli not found in PATH");
                System.err.println("Please ensure kalixcli is installed and accessible");
                System.exit(1);
            }
            
            Path cliPath = cliLocation.get().getPath();
            System.out.println("Found kalixcli at: " + cliPath);
            
            // Test basic JSON protocol functionality
            testBasicProtocol(cliPath);
            
            // Test session manager with JSON protocol
            testJsonSessionManager(cliPath);
            
            System.out.println("\nJSON Protocol Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Test failed with error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Tests basic JSON protocol functionality with direct process communication.
     */
    private static void testBasicProtocol(Path cliPath) throws Exception {
        System.out.println("\n1. Testing Basic JSON Protocol");
        System.out.println("------------------------------");
        
        ProcessExecutor executor = new ProcessExecutor();
        
        try (JsonInteractiveKalixProcess process = JsonInteractiveKalixProcess.start(cliPath, executor)) {
            
            // Wait for ready message
            System.out.println("Waiting for kalixcli to become ready...");
            Optional<JsonMessage.SystemMessage> readyMessage = process.waitForReady(30);
            
            if (readyMessage.isEmpty()) {
                throw new RuntimeException("kalixcli did not become ready within 30 seconds");
            }
            
            System.out.println("✓ Received ready message");
            System.out.println("  Session ID: " + readyMessage.get().getSessionId());
            
            // Extract ready data
            try {
                JsonMessage.ReadyData readyData = JsonStdioProtocol.extractData(readyMessage.get(), JsonMessage.ReadyData.class);
                System.out.println("  Status: " + readyData.getStatus());
                System.out.println("  Available commands: " + (readyData.getAvailableCommands() != null ? readyData.getAvailableCommands().size() : 0));
                
                if (readyData.getCurrentState() != null) {
                    System.out.println("  Model loaded: " + readyData.getCurrentState().isModelLoaded());
                    System.out.println("  Data loaded: " + readyData.getCurrentState().isDataLoaded());
                }
            } catch (Exception e) {
                System.out.println("  (Could not parse ready data: " + e.getMessage() + ")");
            }
            
            // Test version query
            System.out.println("\nTesting version query...");
            process.requestVersion();
            
            // Read response (could be result, log, or ready message)
            for (int i = 0; i < 5; i++) {
                if (process.hasOutputReady()) {
                    Optional<JsonMessage.SystemMessage> response = process.readJsonMessage();
                    if (response.isPresent()) {
                        System.out.println("✓ Received response: " + response.get().getType());
                        break;
                    }
                } else {
                    Thread.sleep(1000);
                }
            }
            
            // Test state query
            System.out.println("\nTesting state query...");
            process.requestState();
            
            // Read response
            for (int i = 0; i < 5; i++) {
                if (process.hasOutputReady()) {
                    Optional<JsonMessage.SystemMessage> response = process.readJsonMessage();
                    if (response.isPresent()) {
                        System.out.println("✓ Received response: " + response.get().getType());
                        break;
                    }
                } else {
                    Thread.sleep(1000);
                }
            }
            
            // Test model loading if supported
            System.out.println("\nTesting model loading...");
            process.sendModelDefinition(SAMPLE_MODEL);
            
            // Wait for completion (busy -> ready cycle expected)
            int messageCount = 0;
            while (messageCount < 10 && process.isRunning()) {
                if (process.hasOutputReady()) {
                    Optional<JsonMessage.SystemMessage> message = process.readJsonMessage();
                    if (message.isPresent()) {
                        messageCount++;
                        JsonStdioTypes.SystemMessageType type = message.get().systemMessageType();
                        System.out.println("  Received: " + type);
                        
                        if (type == JsonStdioTypes.SystemMessageType.READY) {
                            System.out.println("✓ Model loading completed (back to ready)");
                            break;
                        } else if (type == JsonStdioTypes.SystemMessageType.ERROR) {
                            System.out.println("  Model loading failed (this may be expected if model is invalid)");
                            break;
                        }
                    }
                } else {
                    Thread.sleep(500);
                }
            }
            
            System.out.println("✓ Basic protocol test completed");
        }
    }
    
    /**
     * Tests the JSON session manager functionality.
     */
    private static void testJsonSessionManager(Path cliPath) throws Exception {
        System.out.println("\n2. Testing JSON Session Manager");
        System.out.println("-------------------------------");
        
        ProcessExecutor executor = new ProcessExecutor();
        
        // Status callback
        Consumer<String> statusCallback = status -> System.out.println("[STATUS] " + status);
        
        // Event callback
        Consumer<JsonSessionManager.SessionEvent> eventCallback = event -> {
            System.out.println("[EVENT] " + event.getSessionId() + " (" + event.getKalixSessionId() + "): " + 
                event.getOldState() + " -> " + event.getNewState() + " - " + event.getMessage());
        };
        
        JsonSessionManager sessionManager = new JsonSessionManager(executor, statusCallback, eventCallback);
        
        try {
            // Start a session
            System.out.println("Starting session...");
            JsonSessionManager.SessionConfig config = new JsonSessionManager.SessionConfig();
            CompletableFuture<String> sessionFuture = sessionManager.startSession(cliPath, config);
            String sessionId = sessionFuture.get();
            
            System.out.println("✓ Session started: " + sessionId);
            
            // Get session info
            Optional<JsonSessionManager.JsonKalixSession> session = sessionManager.getSession(sessionId);
            if (session.isPresent()) {
                System.out.println("  Kalix Session ID: " + session.get().getKalixSessionId());
                System.out.println("  State: " + session.get().getState());
                System.out.println("  Ready: " + session.get().isReady());
            }
            
            // Test sending a query
            System.out.println("\nSending version query...");
            CompletableFuture<Void> queryFuture = sessionManager.sendQuery(sessionId, "get_version");
            queryFuture.get();
            System.out.println("✓ Query sent");
            
            // Wait a bit for response
            Thread.sleep(2000);
            
            // Test model loading
            System.out.println("\nLoading sample model...");
            CompletableFuture<Void> loadFuture = sessionManager.loadModelString(sessionId, SAMPLE_MODEL);
            loadFuture.get();
            System.out.println("✓ Model load command sent");
            
            // Wait for processing
            Thread.sleep(3000);
            
            // Check session state
            session = sessionManager.getSession(sessionId);
            if (session.isPresent()) {
                System.out.println("  Current state: " + session.get().getState());
                System.out.println("  Last message: " + session.get().getLastMessage());
                if (session.get().getReadyData() != null) {
                    System.out.println("  Model loaded: " + session.get().getReadyData().getCurrentState().isModelLoaded());
                }
            }
            
            // Terminate session
            System.out.println("\nTerminating session...");
            CompletableFuture<Void> terminateFuture = sessionManager.terminateSession(sessionId);
            terminateFuture.get();
            System.out.println("✓ Session terminated");
            
            // Wait for cleanup
            Thread.sleep(1000);
            
            System.out.println("✓ JSON Session Manager test completed");
            
        } finally {
            sessionManager.shutdown();
        }
    }
    
}