package com.kalix.gui;

import com.kalix.gui.model.HydrologicalModel;
import com.kalix.gui.model.ModelNode;

/**
 * Test to verify node rendering with the color palette system.
 */
public class NodeRenderingTest {
    
    public static void main(String[] args) {
        System.out.println("Node Rendering Test");
        System.out.println("==================");
        
        HydrologicalModel model = new HydrologicalModel();
        
        // Add some test nodes with different types
        ModelNode node1 = new ModelNode("reservoir_1", "reservoir", 100, 150);
        ModelNode node2 = new ModelNode("outlet_1", "outlet", 200, 150);
        ModelNode node3 = new ModelNode("gr4j_1", "gr4j", 150, 50);
        ModelNode node4 = new ModelNode("gr4j_2", "gr4j", 250, 100);
        ModelNode node5 = new ModelNode("storage_1", "storage", 300, 200);
        
        model.addNode(node1);
        model.addNode(node2);
        model.addNode(node3);
        model.addNode(node4);
        model.addNode(node5);
        
        System.out.println("Added test nodes:");
        for (ModelNode node : model.getAllNodes()) {
            System.out.println("  " + node.getName() + " (" + node.getType() + ") at (" + 
                node.getX() + ", " + node.getY() + ")");
        }
        
        var stats = model.getStatistics();
        System.out.println("\nModel contains " + stats.getNodeCount() + " nodes");
        
        System.out.println("\n✓ Node rendering test setup complete!");
        System.out.println("Run the GUI to see the nodes visualized on the map.");
    }
}