package com.kalix.ide;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;
import com.kalix.ide.model.ModelStatistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test to verify the model integration shows correct status updates.
 */
public class ModelIntegrationTest {
    
    public static void main(String[] args) {
        System.out.println("Model Integration Test");
        System.out.println("=====================");
        
        // Test the status message generation
        HydrologicalModel model = new HydrologicalModel();
        
        // Add change listener to capture status updates
        model.addChangeListener((ModelChangeEvent event) -> {
            ModelStatistics stats = model.getStatistics();
            String message = String.format("Model: %d nodes, %d links", 
                stats.getNodeCount(), stats.getLinkCount());
            System.out.println("Status update: " + message);
        });
        
        System.out.println("Testing with empty model:");
        ModelStatistics emptyStats = model.getStatistics();
        System.out.println("Empty model: " + String.format("Model: %d nodes, %d links", 
            emptyStats.getNodeCount(), emptyStats.getLinkCount()));
        
        // Test with example model
        Path examplePath = Paths.get("./example_models/model_3_abs.ini");
        if (!Files.exists(examplePath)) {
            System.err.println("Example model file not found: " + examplePath);
            System.exit(1);
        }
        
        try {
            String iniContent = Files.readString(examplePath);
            System.out.println("\nLoading example model...");
            model.parseFromIniText(iniContent);
            
            System.out.println("\n✓ Model integration test completed successfully!");
            
        } catch (IOException e) {
            System.err.println("Error reading example model: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error during integration test: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}