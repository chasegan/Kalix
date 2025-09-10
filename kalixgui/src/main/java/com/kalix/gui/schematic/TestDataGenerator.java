package com.kalix.gui.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Utility class for generating test data for schematic visualization.
 * Creates sample nodes and links for testing performance and functionality.
 */
public class TestDataGenerator {
    
    private static final Random random = new Random(42); // Fixed seed for reproducible results
    
    /**
     * Generate a small test network with basic node types
     */
    public static List<SchematicNode> generateBasicTestNodes() {
        List<SchematicNode> nodes = new ArrayList<>();
        
        // Create a simple hydrological network
        nodes.add(new SchematicNode("rainfall1", NodeType.RAINFALL_RUNOFF, -200, 200));
        nodes.add(new SchematicNode("rainfall2", NodeType.RAINFALL_RUNOFF, 200, 200));
        
        nodes.add(new SchematicNode("storage1", NodeType.STORAGE, -100, 100));
        nodes.add(new SchematicNode("storage2", NodeType.STORAGE, 100, 100));
        
        nodes.add(new SchematicNode("routing1", NodeType.ROUTING, -50, 0));
        nodes.add(new SchematicNode("routing2", NodeType.ROUTING, 50, 0));
        
        nodes.add(new SchematicNode("confluence1", NodeType.CONFLUENCE, 0, -100));
        nodes.add(new SchematicNode("outlet1", NodeType.OUTLET, 0, -200));
        
        // Add some loss nodes
        nodes.add(new SchematicNode("loss1", NodeType.LOSS, -150, 50));
        nodes.add(new SchematicNode("loss2", NodeType.LOSS, 150, 50));
        
        return nodes;
    }
    
    /**
     * Generate links for the basic test network
     */
    public static List<SchematicLink> generateBasicTestLinks() {
        List<SchematicLink> links = new ArrayList<>();
        
        // Connect the basic network
        links.add(new SchematicLink("link1", "rainfall1", "storage1"));
        links.add(new SchematicLink("link2", "rainfall2", "storage2"));
        
        links.add(new SchematicLink("link3", "storage1", "routing1"));
        links.add(new SchematicLink("link4", "storage2", "routing2"));
        
        links.add(new SchematicLink("link5", "routing1", "confluence1"));
        links.add(new SchematicLink("link6", "routing2", "confluence1"));
        
        links.add(new SchematicLink("link7", "confluence1", "outlet1"));
        
        // Loss connections
        links.add(new SchematicLink("link8", "storage1", "loss1"));
        links.add(new SchematicLink("link9", "storage2", "loss2"));
        
        return links;
    }
    
    /**
     * Generate a large network for performance testing
     */
    public static List<SchematicNode> generateLargeTestNetwork(int nodeCount) {
        List<SchematicNode> nodes = new ArrayList<>();
        
        // Create a grid-like network for testing
        int gridSize = (int) Math.ceil(Math.sqrt(nodeCount));
        float spacing = 100.0f;
        
        for (int i = 0; i < nodeCount; i++) {
            int row = i / gridSize;
            int col = i % gridSize;
            
            float x = (col - gridSize / 2.0f) * spacing;
            float y = (row - gridSize / 2.0f) * spacing;
            
            NodeType type = getRandomNodeType();
            String id = "node_" + i;
            
            SchematicNode node = new SchematicNode(id, type, x, y);
            
            // Add some randomness to positioning
            node.setX(x + (random.nextFloat() - 0.5f) * spacing * 0.3f);
            node.setY(y + (random.nextFloat() - 0.5f) * spacing * 0.3f);
            
            // Vary node sizes slightly
            node.setWidth(30 + random.nextFloat() * 20);
            node.setHeight(20 + random.nextFloat() * 15);
            
            nodes.add(node);
        }
        
        return nodes;
    }
    
    /**
     * Generate links for a large network (creates a connected graph)
     */
    public static List<SchematicLink> generateLargeTestLinks(List<SchematicNode> nodes) {
        List<SchematicLink> links = new ArrayList<>();
        
        if (nodes.size() < 2) return links;
        
        // Create connections to form a roughly connected network
        for (int i = 0; i < nodes.size(); i++) {
            SchematicNode node = nodes.get(i);
            
            // Connect to 1-3 nearby nodes
            int connectionCount = 1 + random.nextInt(3);
            
            for (int j = 0; j < connectionCount && links.size() < nodes.size() * 2; j++) {
                // Find a nearby node to connect to
                int targetIndex = findNearbyNode(nodes, i, 5);
                if (targetIndex != -1 && targetIndex != i) {
                    SchematicNode targetNode = nodes.get(targetIndex);
                    
                    String linkId = "link_" + node.getId() + "_" + targetNode.getId();
                    
                    // Avoid duplicate links
                    if (!linkExists(links, node.getId(), targetNode.getId())) {
                        SchematicLink link = new SchematicLink(linkId, node.getId(), targetNode.getId());
                        link.setType(getRandomLinkType());
                        links.add(link);
                    }
                }
            }
        }
        
        return links;
    }
    
    /**
     * Generate performance test data with specified node count
     */
    public static SchematicTestData generatePerformanceTestData(int nodeCount) {
        System.out.println("Generating " + nodeCount + " nodes for performance testing...");
        
        List<SchematicNode> nodes = generateLargeTestNetwork(nodeCount);
        List<SchematicLink> links = generateLargeTestLinks(nodes);
        
        System.out.println("Generated " + nodes.size() + " nodes and " + links.size() + " links");
        
        return new SchematicTestData(nodes, links);
    }
    
    /**
     * Get a random node type weighted towards common types
     */
    private static NodeType getRandomNodeType() {
        float rand = random.nextFloat();
        
        if (rand < 0.3f) return NodeType.ROUTING;
        if (rand < 0.5f) return NodeType.STORAGE;
        if (rand < 0.7f) return NodeType.RAINFALL_RUNOFF;
        if (rand < 0.85f) return NodeType.CONFLUENCE;
        if (rand < 0.95f) return NodeType.LOSS;
        return NodeType.OUTLET;
    }
    
    /**
     * Get a random link type weighted towards flow links
     */
    private static LinkType getRandomLinkType() {
        float rand = random.nextFloat();
        
        if (rand < 0.8f) return LinkType.FLOW;
        if (rand < 0.9f) return LinkType.CONTROL;
        if (rand < 0.95f) return LinkType.DATA;
        return LinkType.FEEDBACK;
    }
    
    /**
     * Find a nearby node within search radius
     */
    private static int findNearbyNode(List<SchematicNode> nodes, int centerIndex, int searchRadius) {
        SchematicNode centerNode = nodes.get(centerIndex);
        
        for (int i = 0; i < searchRadius && (centerIndex + i + 1) < nodes.size(); i++) {
            int candidateIndex = centerIndex + i + 1;
            SchematicNode candidate = nodes.get(candidateIndex);
            
            // Check if within reasonable distance
            float distance = centerNode.distanceTo(candidate);
            if (distance < 200.0f) { // Reasonable connection distance
                return candidateIndex;
            }
        }
        
        return -1;
    }
    
    /**
     * Check if a link already exists between two nodes
     */
    private static boolean linkExists(List<SchematicLink> links, String fromId, String toId) {
        for (SchematicLink link : links) {
            if ((link.getFromNodeId().equals(fromId) && link.getToNodeId().equals(toId)) ||
                (link.getFromNodeId().equals(toId) && link.getToNodeId().equals(fromId))) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Container for test data
     */
    public static class SchematicTestData {
        public final List<SchematicNode> nodes;
        public final List<SchematicLink> links;
        
        public SchematicTestData(List<SchematicNode> nodes, List<SchematicLink> links) {
            this.nodes = nodes;
            this.links = links;
        }
    }
}