package com.kalix.ide;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;

/**
 * Test to verify zoom to fit functionality with different node layouts.
 */
public class ZoomToFitTest {
    
    public static void main(String[] args) {
        System.out.println("Zoom to Fit Test");
        System.out.println("================");
        
        // Test 1: Nodes spread across a large area
        System.out.println("\n1. Testing nodes spread across large area:");
        testLargeSpread();
        
        // Test 2: Nodes clustered in small area
        System.out.println("\n2. Testing nodes clustered in small area:");
        testSmallCluster();
        
        // Test 3: Single node
        System.out.println("\n3. Testing single node:");
        testSingleNode();
        
        // Test 4: All nodes at same location
        System.out.println("\n4. Testing nodes at same location:");
        testSameLocation();
        
        System.out.println("\n✓ Zoom to fit test scenarios created!");
        System.out.println("Run the IDE and use View -> Zoom to Fit to test the functionality.");
    }
    
    private static void testLargeSpread() {
        HydrologicalModel model = new HydrologicalModel();
        
        // Nodes spread from (0,0) to (1000,800)
        model.addNode(new ModelNode("top_left", "reservoir", 0, 0));
        model.addNode(new ModelNode("top_right", "outlet", 1000, 0));
        model.addNode(new ModelNode("bottom_left", "gr4j", 0, 800));
        model.addNode(new ModelNode("bottom_right", "storage", 1000, 800));
        model.addNode(new ModelNode("center", "junction", 500, 400));
        
        System.out.println("  Created model with nodes spanning (0,0) to (1000,800)");
        System.out.println("  Zoom to fit should center at (500,400) and zoom out to show all nodes");
    }
    
    private static void testSmallCluster() {
        HydrologicalModel model = new HydrologicalModel();
        
        // Nodes clustered around (100,100) within 50 unit radius
        model.addNode(new ModelNode("cluster1", "gr4j", 100, 100));
        model.addNode(new ModelNode("cluster2", "gr4j", 120, 110));
        model.addNode(new ModelNode("cluster3", "outlet", 90, 95));
        model.addNode(new ModelNode("cluster4", "reservoir", 110, 130));
        
        System.out.println("  Created model with nodes clustered around (100,100)");
        System.out.println("  Zoom to fit should center around cluster and zoom in to show detail");
    }
    
    private static void testSingleNode() {
        HydrologicalModel model = new HydrologicalModel();
        
        model.addNode(new ModelNode("lonely", "outlet", 300, 200));
        
        System.out.println("  Created model with single node at (300,200)");
        System.out.println("  Zoom to fit should center on node with default span");
    }
    
    private static void testSameLocation() {
        HydrologicalModel model = new HydrologicalModel();
        
        // Multiple nodes at exact same location
        model.addNode(new ModelNode("node1", "gr4j", 500, 300));
        model.addNode(new ModelNode("node2", "outlet", 500, 300));
        model.addNode(new ModelNode("node3", "reservoir", 500, 300));
        
        System.out.println("  Created model with 3 nodes at same location (500,300)");
        System.out.println("  Zoom to fit should center on location with default span");
    }
}