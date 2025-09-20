package com.kalix.gui;

import com.kalix.gui.model.HydrologicalModel;
import com.kalix.gui.model.ModelChangeEvent;

/**
 * Test to verify the simplified status reporting shows modified node/link counts.
 */
public class StatusReportingTest {
    
    public static void main(String[] args) {
        System.out.println("Status Reporting Test");
        System.out.println("====================");
        
        HydrologicalModel model = new HydrologicalModel();
        
        // Add change listener to simulate status bar updates
        model.addChangeListener((ModelChangeEvent event) -> {
            // Simulate the KalixGUI status update logic
            var stats = model.getStatistics();
            String baseMessage = String.format("Model: %d nodes, %d links", 
                stats.getNodeCount(), stats.getLinkCount());
            
            // Add affected counts if there were changes
            if (event.getAffectedNodeCount() > 0 || event.getAffectedLinkCount() > 0) {
                String changeMessage = String.format(" (%d nodes, %d links modified)",
                    event.getAffectedNodeCount(), event.getAffectedLinkCount());
                System.out.println("Status: " + baseMessage + changeMessage);
            } else {
                System.out.println("Status: " + baseMessage);
            }
        });
        
        // Test 1: Initial load
        System.out.println("\n1. Initial load:");
        String initialModel = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 10, 20\n" +
            "\n" +
            "[node.node2]\n" +
            "type = outlet\n" +
            "loc = 30, 40\n";
        
        model.parseFromIniTextIncremental(initialModel);
        
        // Test 2: Add one node
        System.out.println("\n2. Add one node:");
        String modelWithNewNode = initialModel +
            "\n" +
            "[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n";
        
        model.parseFromIniTextIncremental(modelWithNewNode);
        
        // Test 3: Modify existing node
        System.out.println("\n3. Modify one node:");
        String modifiedModel = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 15, 25\n" +  // Changed coordinates
            "\n" +
            "[node.node2]\n" +
            "type = outlet\n" +
            "loc = 30, 40\n" +
            "\n" +
            "[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n";
        
        model.parseFromIniTextIncremental(modifiedModel);
        
        // Test 4: Remove one node
        System.out.println("\n4. Remove one node:");
        String reducedModel = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 15, 25\n" +
            "\n" +
            "[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n";
        
        model.parseFromIniTextIncremental(reducedModel);
        
        // Test 5: No change (should not show modified counts)
        System.out.println("\n5. No changes:");
        model.parseFromIniTextIncremental(reducedModel);
        
        System.out.println("\n✓ Status reporting test completed!");
    }
}