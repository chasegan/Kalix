package com.kalix.gui.model;

/**
 * Test to verify incremental model updates work correctly.
 */
public class IncrementalUpdateTest {
    
    private static int changeEventCount = 0;
    private static String lastEventType = "";
    
    public static void main(String[] args) {
        System.out.println("Incremental Update Test");
        System.out.println("======================");
        
        HydrologicalModel model = new HydrologicalModel();
        
        // Add change listener to track events
        model.addChangeListener(event -> {
            changeEventCount++;
            lastEventType = event.getType().toString();
            System.out.println("Event " + changeEventCount + ": " + event.getType() + 
                " (" + event.getEntityId() + ")");
            
            ModelStatistics stats = model.getStatistics();
            System.out.println("  -> Model now has: " + stats.getNodeCount() + 
                " nodes, " + stats.getLinkCount() + " links");
        });
        
        // Test 1: Initial parse (should be full update)
        System.out.println("\n1. Initial parse:");
        String initialModel = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 10, 20\n" +
            "\n" +
            "[node.node2]\n" +
            "type = outlet\n" +
            "loc = 30, 40\n";
        
        model.parseFromIniTextIncremental(initialModel);
        
        // Test 2: No change (should skip update)
        System.out.println("\n2. Same content (should be no events):");
        int eventsBefore = changeEventCount;
        model.parseFromIniTextIncremental(initialModel);
        if (changeEventCount == eventsBefore) {
            System.out.println("✓ No unnecessary updates triggered");
        } else {
            System.out.println("✗ Unexpected update triggered");
        }
        
        // Test 3: Add a node
        System.out.println("\n3. Add a new node:");
        String modelWithNewNode = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 10, 20\n" +
            "\n" +
            "[node.node2]\n" +
            "type = outlet\n" +
            "loc = 30, 40\n" +
            "\n" +
            "[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n";
        
        model.parseFromIniTextIncremental(modelWithNewNode);
        
        // Test 4: Modify existing node location
        System.out.println("\n4. Modify node1 location:");
        String modelWithModifiedNode = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 15, 25\n" +  // Changed location
            "\n" +
            "[node.node2]\n" +
            "type = outlet\n" +
            "loc = 30, 40\n" +
            "\n" +
            "[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n";
        
        model.parseFromIniTextIncremental(modelWithModifiedNode);
        
        // Test 5: Remove a node
        System.out.println("\n5. Remove node2:");
        String modelWithRemovedNode = 
            "[node.node1]\n" +
            "type = gr4j\n" +
            "loc = 15, 25\n" +
            "\n" +
            "[node.node3]\n" +
            "type = storage\n" +
            "loc = 50, 60\n";
        
        model.parseFromIniTextIncremental(modelWithRemovedNode);
        
        // Test 6: Performance test with many nodes
        System.out.println("\n6. Performance test with large model:");
        testLargeModelPerformance(model);
        
        System.out.println("\n✓ Incremental update test completed!");
        System.out.println("Total change events: " + changeEventCount);
    }
    
    private static void testLargeModelPerformance(HydrologicalModel model) {
        int nodeCount = 1000;
        long startTime = System.currentTimeMillis();
        
        // Create a large model
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= nodeCount; i++) {
            sb.append(String.format("[node.node%d]\n", i));
            sb.append("type = test\n");
            sb.append(String.format("loc = %d, %d\n\n", i * 10, i * 20));
        }
        
        model.parseFromIniTextIncremental(sb.toString());
        long fullParseTime = System.currentTimeMillis() - startTime;
        System.out.println("  Full parse of " + nodeCount + " nodes: " + fullParseTime + "ms");
        
        // Now make a small change
        startTime = System.currentTimeMillis();
        
        // Change just one node location
        String modifiedModel = sb.toString().replace("loc = 10, 20", "loc = 11, 21");
        model.parseFromIniTextIncremental(modifiedModel);
        
        long incrementalTime = System.currentTimeMillis() - startTime;
        System.out.println("  Incremental update (1 node changed): " + incrementalTime + "ms");
        
        if (incrementalTime < fullParseTime) {
            System.out.println("  ✓ Incremental update is faster than full parse");
        } else {
            System.out.println("  ⚠ Incremental update not faster (small model size?)");
        }
    }
}