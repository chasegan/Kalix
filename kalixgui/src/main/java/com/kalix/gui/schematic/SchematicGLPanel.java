package com.kalix.gui.schematic;

import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.FPSAnimator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance OpenGL-based schematic visualization panel for hydrological models.
 * Capable of rendering thousands of nodes simultaneously using GPU instancing.
 * 
 * This panel replaces the MapPanel for visualizing node-link networks representing
 * hydrological models, with support for pan, zoom, and interactive node selection.
 */
public class SchematicGLPanel extends GLCanvas implements GLEventListener {
    
    private static final int TARGET_FPS = 60;
    
    // Rendering components
    private FPSAnimator animator;
    private SchematicRenderer renderer;
    private ViewportManager viewport;
    private InteractionManager interaction;
    
    // Data
    private List<SchematicNode> nodes;
    private List<SchematicLink> links;
    
    // State
    private boolean initialized = false;
    private int frameCount = 0;
    
    public SchematicGLPanel() {
        super(createGLCapabilities());
        
        // Initialize data structures
        nodes = new ArrayList<>();
        links = new ArrayList<>();
        
        // Setup OpenGL event handling
        addGLEventListener(this);
        
        // Initialize viewport and interaction managers
        viewport = new ViewportManager();
        interaction = new InteractionManager(viewport);
        
        // Setup mouse interaction
        setupMouseHandlers();
        
        // Enable automatic buffer swapping
        setAutoSwapBufferMode(true);
    }
    
    /**
     * Create OpenGL capabilities with hardware acceleration preferences
     */
    private static GLCapabilities createGLCapabilities() {
        GLProfile profile = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(profile);
        
        // Request hardware acceleration
        caps.setHardwareAccelerated(true);
        caps.setDoubleBuffered(true);
        
        // Anti-aliasing for smooth graphics
        caps.setSampleBuffers(true);
        caps.setNumSamples(4);
        
        return caps;
    }
    
    private void setupMouseHandlers() {
        // Mouse interaction for pan, zoom, and selection
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                interaction.handleMousePressed(e);
                repaint();
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                interaction.handleMouseDragged(e);
                repaint();
            }
            
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                interaction.handleMouseWheel(e);
                repaint();
            }
            
            @Override
            public void mouseClicked(MouseEvent e) {
                interaction.handleMouseClicked(e, nodes);
                repaint();
            }
        };
        
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
    }
    
    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        
        try {
            // Initialize renderer with OpenGL context
            renderer = new SchematicRenderer();
            renderer.initialize(gl);
            
            // Set initial viewport
            viewport.initialize(getWidth(), getHeight());
            
            // Setup OpenGL state
            gl.glEnable(GL.GL_BLEND);
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            gl.glEnable(GL.GL_DEPTH_TEST);
            
            // Clear color (light gray background)
            gl.glClearColor(0.95f, 0.95f, 0.95f, 1.0f);
            
            initialized = true;
            
            // Start animation loop
            animator = new FPSAnimator(this, TARGET_FPS);
            animator.start();
            
            System.out.println("SchematicGLPanel initialized successfully");
            
        } catch (Exception e) {
            System.err.println("Failed to initialize SchematicGLPanel: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void display(GLAutoDrawable drawable) {
        if (!initialized) {
            System.out.println("Display called but not initialized yet");
            return;
        }
        
        GL4 gl = drawable.getGL().getGL4();
        
        // Clear the screen
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        
        // Update view matrices
        renderer.updateMatrices(gl, viewport);
        
        // Debug info
        if (frameCount % 60 == 0) { // Print every 60 frames
            System.out.println("Rendering frame " + frameCount + 
                             ", nodes: " + nodes.size() + 
                             ", links: " + links.size() +
                             ", viewport center: (" + viewport.getCenterX() + ", " + viewport.getCenterY() + ")" +
                             ", zoom: " + viewport.getZoom());
        }
        frameCount++;
        
        // Render nodes using GPU instancing
        if (!nodes.isEmpty()) {
            renderer.renderNodes(gl, nodes);
        }
        
        // Render links
        if (!links.isEmpty()) {
            renderer.renderLinks(gl, links);
        }
        
        // Render UI overlays (selection, etc.)
        renderer.renderOverlays(gl, interaction.getSelectedNodes());
    }
    
    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL4 gl = drawable.getGL().getGL4();
        
        // Update viewport
        gl.glViewport(0, 0, width, height);
        viewport.resize(width, height);
        
        // Update projection matrix
        if (initialized) {
            renderer.updateProjection(gl, width, height);
        }
    }
    
    @Override
    public void dispose(GLAutoDrawable drawable) {
        if (animator != null) {
            animator.stop();
        }
        
        if (renderer != null) {
            renderer.cleanup(drawable.getGL().getGL4());
        }
    }
    
    // Public API for updating model data
    
    /**
     * Update the nodes displayed in the schematic
     */
    public void setNodes(List<SchematicNode> nodes) {
        this.nodes = new ArrayList<>(nodes);
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
    
    /**
     * Update the links displayed in the schematic
     */
    public void setLinks(List<SchematicLink> links) {
        this.links = new ArrayList<>(links);
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
    
    /**
     * Add a single node to the schematic
     */
    public void addNode(SchematicNode node) {
        nodes.add(node);
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
    
    /**
     * Remove a node from the schematic
     */
    public void removeNode(SchematicNode node) {
        nodes.remove(node);
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
    
    /**
     * Clear all nodes and links
     */
    public void clearModel() {
        nodes.clear();
        links.clear();
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
    
    /**
     * Fit all nodes in the viewport
     */
    public void fitToModel() {
        if (nodes.isEmpty()) return;
        
        viewport.fitToNodes(nodes);
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
    
    /**
     * Get the current viewport manager for external access
     */
    public ViewportManager getViewport() {
        return viewport;
    }
    
    /**
     * Get currently selected nodes
     */
    public List<SchematicNode> getSelectedNodes() {
        return interaction.getSelectedNodes();
    }
    
    /**
     * Set selection state of nodes
     */
    public void setSelectedNodes(List<SchematicNode> selectedNodes) {
        interaction.setSelectedNodes(selectedNodes);
        if (initialized) {
            SwingUtilities.invokeLater(this::repaint);
        }
    }
}