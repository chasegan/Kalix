package com.kalix.gui.model;

import java.util.List;
import java.util.Random;

/**
 * Performance test for the data model with thousands of nodes.
 */
public class ModelPerformanceTest {
    
    public static void main(String[] args) {
        System.out.println("Model Performance Test");
        System.out.println("=====================");
        
        // Test with increasing node counts
        int[] nodeCounts = {100, 1000, 5000, 10000};
        
        for (int nodeCount : nodeCounts) {
            testModelPerformance(nodeCount);
            System.out.println();
        }
    }
    
    private static void testModelPerformance(int nodeCount) {
        System.out.println("Testing with " + nodeCount + " nodes:");
        
        HydrologicalModel model = new HydrologicalModel();
        Random random = new Random(42); // Fixed seed for reproducible results
        
        // Generate test nodes
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < nodeCount; i++) {
            double x = random.nextDouble() * 1000; // 0-1000 range
            double y = random.nextDouble() * 1000;
            String name = "node_" + i;
            String type = "test_" + (i % 5); // 5 different types
            
            ModelNode node = new ModelNode(name, type, x, y);
            model.addNode(node);
        }
        
        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println("  Insert time: " + insertTime + "ms");
        
        // Test spatial queries
        startTime = System.currentTimeMillis();
        List<ModelNode> regionNodes = model.getNodesInRegion(100, 100, 200, 200);
        long queryTime = System.currentTimeMillis() - startTime;
        
        System.out.println("  Spatial query time: " + queryTime + "ms");
        System.out.println("  Nodes in region (100,100)-(200,200): " + regionNodes.size());
        
        // Test point queries
        startTime = System.currentTimeMillis();
        List<ModelNode> nearNodes = model.getNodesNear(150, 150);
        long pointQueryTime = System.currentTimeMillis() - startTime;
        
        System.out.println("  Point query time: " + pointQueryTime + "ms");
        System.out.println("  Nodes near (150,150): " + nearNodes.size());
        
        // Show statistics
        ModelStatistics stats = model.getStatistics();
        SpatialIndex.IndexStatistics spatialStats = model.getSpatialStatistics();
        
        System.out.println("  Model stats: " + stats);
        System.out.println("  Spatial stats: " + spatialStats);
        
        // Test full iteration (baseline comparison)
        startTime = System.currentTimeMillis();
        int countInRegion = 0;
        for (ModelNode node : model.getAllNodes()) {
            if (node.getX() >= 100 && node.getX() <= 200 && 
                node.getY() >= 100 && node.getY() <= 200) {
                countInRegion++;
            }
        }
        long fullIterationTime = System.currentTimeMillis() - startTime;
        
        System.out.println("  Full iteration time: " + fullIterationTime + "ms (baseline)");
        System.out.println("  Speedup: " + (fullIterationTime > 0 ? (double)fullIterationTime / Math.max(1, queryTime) : "N/A") + "x");
    }
}