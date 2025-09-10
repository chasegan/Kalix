package com.kalix.gui.model;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents the differences between two model states for incremental updates.
 */
public class ModelDiff {
    private final Set<String> addedNodes = new HashSet<>();
    private final Set<String> removedNodes = new HashSet<>();
    private final Set<String> modifiedNodes = new HashSet<>();
    private final Map<String, ModelNode> nodeUpdates = new HashMap<>();
    
    // For now, links are simpler - just track if the link list changed
    private final boolean linksChanged;
    private final java.util.List<ModelLink> newLinks;
    
    public ModelDiff(ModelParser.ParseResult oldResult, ModelParser.ParseResult newResult) {
        // Create lookup maps for efficient comparison
        Map<String, ModelNode> oldNodes = new HashMap<>();
        for (ModelNode node : oldResult.getNodes()) {
            oldNodes.put(node.getName(), node);
        }
        
        Map<String, ModelNode> newNodes = new HashMap<>();
        for (ModelNode node : newResult.getNodes()) {
            newNodes.put(node.getName(), node);
        }
        
        // Find added nodes (in new but not in old)
        for (String nodeName : newNodes.keySet()) {
            if (!oldNodes.containsKey(nodeName)) {
                addedNodes.add(nodeName);
                nodeUpdates.put(nodeName, newNodes.get(nodeName));
            }
        }
        
        // Find removed nodes (in old but not in new)
        for (String nodeName : oldNodes.keySet()) {
            if (!newNodes.containsKey(nodeName)) {
                removedNodes.add(nodeName);
            }
        }
        
        // Find modified nodes (same name, different content)
        for (String nodeName : newNodes.keySet()) {
            if (oldNodes.containsKey(nodeName)) {
                ModelNode oldNode = oldNodes.get(nodeName);
                ModelNode newNode = newNodes.get(nodeName);
                
                // Compare nodes for changes
                if (!oldNode.equals(newNode)) {
                    modifiedNodes.add(nodeName);
                    nodeUpdates.put(nodeName, newNode);
                }
            }
        }
        
        // For links, do a simple comparison for now
        this.linksChanged = !oldResult.getLinks().equals(newResult.getLinks());
        this.newLinks = newResult.getLinks();
    }
    
    public boolean hasChanges() {
        return !addedNodes.isEmpty() || !removedNodes.isEmpty() || 
               !modifiedNodes.isEmpty() || linksChanged;
    }
    
    public Set<String> getAddedNodes() {
        return addedNodes;
    }
    
    public Set<String> getRemovedNodes() {
        return removedNodes;
    }
    
    public Set<String> getModifiedNodes() {
        return modifiedNodes;
    }
    
    public Map<String, ModelNode> getNodeUpdates() {
        return nodeUpdates;
    }
    
    public boolean areLinksChanged() {
        return linksChanged;
    }
    
    public java.util.List<ModelLink> getNewLinks() {
        return newLinks;
    }
    
    public int getTotalChanges() {
        return addedNodes.size() + removedNodes.size() + modifiedNodes.size() + 
               (linksChanged ? 1 : 0);
    }
    
    @Override
    public String toString() {
        return String.format("ModelDiff{added=%d, removed=%d, modified=%d, linksChanged=%b}", 
            addedNodes.size(), removedNodes.size(), modifiedNodes.size(), linksChanged);
    }
}