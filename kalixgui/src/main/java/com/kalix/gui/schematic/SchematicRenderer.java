package com.kalix.gui.schematic;

import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.glsl.ShaderCode;
import com.jogamp.opengl.util.glsl.ShaderProgram;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * High-performance OpenGL renderer for schematic visualization.
 * Uses GPU instancing to efficiently render thousands of nodes and links.
 */
public class SchematicRenderer {
    
    // Shader programs
    private ShaderProgram nodeShaderProgram;
    private ShaderProgram linkShaderProgram;
    
    // Node rendering
    private int nodeVAO;
    private int nodeVBO;           // Base node geometry
    private int nodeInstanceVBO;   // Instance data
    private int nodeInstanceCount = 0;
    
    // Link rendering  
    private int linkVAO;
    private int linkVBO;
    private int linkInstanceVBO;
    private int linkInstanceCount = 0;
    
    // Uniform locations
    private int nodeViewMatrixLoc;
    private int nodeProjMatrixLoc;
    private int linkViewMatrixLoc;
    private int linkProjMatrixLoc;
    
    // Instance data buffers
    private FloatBuffer nodeInstanceBuffer;
    private FloatBuffer linkInstanceBuffer;
    
    // Constants
    private static final int MAX_NODES = 100000;
    private static final int MAX_LINKS = 100000;
    private static final int NODE_INSTANCE_SIZE = 8; // x, y, width, height, r, g, b, type
    private static final int LINK_INSTANCE_SIZE = 8; // x1, y1, x2, y2, r, g, b, width
    
    public SchematicRenderer() {
        // Initialize buffers
        nodeInstanceBuffer = FloatBuffer.allocate(MAX_NODES * NODE_INSTANCE_SIZE);
        linkInstanceBuffer = FloatBuffer.allocate(MAX_LINKS * LINK_INSTANCE_SIZE);
    }
    
    /**
     * Initialize OpenGL resources
     */
    public void initialize(GL4 gl) throws Exception {
        // Create and compile shaders
        createShaderPrograms(gl);
        
        // Setup node rendering
        setupNodeRendering(gl);
        
        // Setup link rendering
        setupLinkRendering(gl);
        
        System.out.println("SchematicRenderer initialized successfully");
    }
    
    /**
     * Create and compile shader programs
     */
    private void createShaderPrograms(GL4 gl) throws Exception {
        // Node shaders
        String nodeVertexShader = "#version 330 core\n" +
            "\n" +
            "// Base quad vertices\n" +
            "layout (location = 0) in vec2 position;\n" +
            "\n" +
            "// Instance data\n" +
            "layout (location = 1) in vec2 instancePos;\n" +
            "layout (location = 2) in vec2 instanceSize;\n" +
            "layout (location = 3) in vec4 instanceColor;\n" +
            "layout (location = 4) in float instanceType;\n" +
            "\n" +
            "uniform mat4 viewMatrix;\n" +
            "uniform mat4 projMatrix;\n" +
            "\n" +
            "out vec4 vertexColor;\n" +
            "out float nodeType;\n" +
            "\n" +
            "void main() {\n" +
            "    // Scale base geometry by instance size\n" +
            "    vec2 scaledPos = position * instanceSize;\n" +
            "    vec2 worldPos = scaledPos + instancePos;\n" +
            "    \n" +
            "    gl_Position = projMatrix * viewMatrix * vec4(worldPos, 0.0, 1.0);\n" +
            "    vertexColor = instanceColor;\n" +
            "    nodeType = instanceType;\n" +
            "}\n";
            
        String nodeFragmentShader = "#version 330 core\n" +
            "\n" +
            "in vec4 vertexColor;\n" +
            "in float nodeType;\n" +
            "\n" +
            "out vec4 fragColor;\n" +
            "\n" +
            "void main() {\n" +
            "    fragColor = vertexColor;\n" +
            "}\n";
        
        // Link shaders
        String linkVertexShader = "#version 330 core\n" +
            "\n" +
            "layout (location = 0) in vec2 position;\n" +
            "\n" +
            "// Instance data\n" +
            "layout (location = 1) in vec2 lineStart;\n" +
            "layout (location = 2) in vec2 lineEnd;\n" +
            "layout (location = 3) in vec4 lineColor;\n" +
            "layout (location = 4) in float lineWidth;\n" +
            "\n" +
            "uniform mat4 viewMatrix;\n" +
            "uniform mat4 projMatrix;\n" +
            "\n" +
            "out vec4 vertexColor;\n" +
            "\n" +
            "void main() {\n" +
            "    // Create line geometry\n" +
            "    vec2 lineDir = normalize(lineEnd - lineStart);\n" +
            "    vec2 lineNormal = vec2(-lineDir.y, lineDir.x);\n" +
            "    \n" +
            "    vec2 worldPos;\n" +
            "    if (position.x < 0.5) {\n" +
            "        worldPos = lineStart + lineNormal * position.y * lineWidth;\n" +
            "    } else {\n" +
            "        worldPos = lineEnd + lineNormal * position.y * lineWidth;\n" +
            "    }\n" +
            "    \n" +
            "    gl_Position = projMatrix * viewMatrix * vec4(worldPos, 0.0, 1.0);\n" +
            "    vertexColor = lineColor;\n" +
            "}\n";
            
        String linkFragmentShader = "#version 330 core\n" +
            "\n" +
            "in vec4 vertexColor;\n" +
            "out vec4 fragColor;\n" +
            "\n" +
            "void main() {\n" +
            "    fragColor = vertexColor;\n" +
            "}\n";
        
        // Create and compile shaders using raw OpenGL
        int vertexShader = createShader(gl, GL4.GL_VERTEX_SHADER, nodeVertexShader);
        int fragmentShader = createShader(gl, GL4.GL_FRAGMENT_SHADER, nodeFragmentShader);
        
        if (vertexShader == 0 || fragmentShader == 0) {
            throw new Exception("Failed to compile node shaders");
        }
        
        // Create shader program
        int program = gl.glCreateProgram();
        gl.glAttachShader(program, vertexShader);
        gl.glAttachShader(program, fragmentShader);
        gl.glLinkProgram(program);
        
        // Check linking
        int[] linked = new int[1];
        gl.glGetProgramiv(program, GL4.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            System.err.println("Node shader program linking failed");
            throw new Exception("Node shader program linking failed");
        }
        
        System.out.println("Node shader program created successfully");
        
        // Store program ID - create a simple wrapper
        nodeShaderProgram = new SimpleShaderProgram(program);
        
        // Get uniform locations for node shader
        nodeViewMatrixLoc = gl.glGetUniformLocation(nodeShaderProgram.program(), "viewMatrix");
        nodeProjMatrixLoc = gl.glGetUniformLocation(nodeShaderProgram.program(), "projMatrix");
        
        // Create and compile link shaders using raw OpenGL
        int linkVertexShaderId = createShader(gl, GL4.GL_VERTEX_SHADER, linkVertexShader);
        int linkFragmentShaderId = createShader(gl, GL4.GL_FRAGMENT_SHADER, linkFragmentShader);
        
        if (linkVertexShaderId == 0 || linkFragmentShaderId == 0) {
            throw new Exception("Failed to compile link shaders");
        }
        
        // Create link shader program
        int linkProgram = gl.glCreateProgram();
        gl.glAttachShader(linkProgram, linkVertexShaderId);
        gl.glAttachShader(linkProgram, linkFragmentShaderId);
        gl.glLinkProgram(linkProgram);
        
        // Check linking
        int[] linkLinked = new int[1];
        gl.glGetProgramiv(linkProgram, GL4.GL_LINK_STATUS, linkLinked, 0);
        if (linkLinked[0] == 0) {
            System.err.println("Link shader program linking failed");
            throw new Exception("Link shader program linking failed");
        }
        
        System.out.println("Link shader program created successfully");
        
        // Store program ID
        linkShaderProgram = new SimpleShaderProgram(linkProgram);
        
        // Get uniform locations for link shader
        linkViewMatrixLoc = gl.glGetUniformLocation(linkShaderProgram.program(), "viewMatrix");
        linkProjMatrixLoc = gl.glGetUniformLocation(linkShaderProgram.program(), "projMatrix");
    }
    
    /**
     * Setup node rendering resources
     */
    private void setupNodeRendering(GL4 gl) {
        // Create base quad geometry (unit square centered at origin)
        float[] quadVertices = {
            -0.5f, -0.5f,  // Bottom left
             0.5f, -0.5f,  // Bottom right
             0.5f,  0.5f,  // Top right
            -0.5f,  0.5f   // Top left
        };
        
        int[] quadIndices = {
            0, 1, 2,
            2, 3, 0
        };
        
        // Generate vertex arrays and buffers
        int[] arrays = new int[1];
        gl.glGenVertexArrays(1, arrays, 0);
        nodeVAO = arrays[0];
        
        int[] buffers = new int[2];
        gl.glGenBuffers(2, buffers, 0);
        nodeVBO = buffers[0];
        nodeInstanceVBO = buffers[1];
        
        // Setup base geometry
        gl.glBindVertexArray(nodeVAO);
        
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, nodeVBO);
        gl.glBufferData(GL4.GL_ARRAY_BUFFER, quadVertices.length * 4, 
                        FloatBuffer.wrap(quadVertices), GL4.GL_STATIC_DRAW);
        
        gl.glVertexAttribPointer(0, 2, GL4.GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(0);
        
        // Setup instance buffer
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, nodeInstanceVBO);
        gl.glBufferData(GL4.GL_ARRAY_BUFFER, MAX_NODES * NODE_INSTANCE_SIZE * 4, 
                        null, GL4.GL_DYNAMIC_DRAW);
        
        // Instance position (2 floats)
        gl.glVertexAttribPointer(1, 2, GL4.GL_FLOAT, false, NODE_INSTANCE_SIZE * 4, 0);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribDivisor(1, 1);
        
        // Instance size (2 floats)  
        gl.glVertexAttribPointer(2, 2, GL4.GL_FLOAT, false, NODE_INSTANCE_SIZE * 4, 2 * 4);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribDivisor(2, 1);
        
        // Instance color (4 floats)
        gl.glVertexAttribPointer(3, 4, GL4.GL_FLOAT, false, NODE_INSTANCE_SIZE * 4, 4 * 4);
        gl.glEnableVertexAttribArray(3);
        gl.glVertexAttribDivisor(3, 1);
        
        // Instance type (1 float)
        gl.glVertexAttribPointer(4, 1, GL4.GL_FLOAT, false, NODE_INSTANCE_SIZE * 4, 7 * 4);
        gl.glEnableVertexAttribArray(4);
        gl.glVertexAttribDivisor(4, 1);
        
        gl.glBindVertexArray(0);
    }
    
    /**
     * Setup link rendering resources
     */
    private void setupLinkRendering(GL4 gl) {
        // Create base line geometry (unit line)
        float[] lineVertices = {
            0.0f, -0.5f,  // Start bottom
            0.0f,  0.5f,  // Start top
            1.0f, -0.5f,  // End bottom
            1.0f,  0.5f   // End top
        };
        
        // Generate vertex arrays and buffers
        int[] arrays = new int[1];
        gl.glGenVertexArrays(1, arrays, 0);
        linkVAO = arrays[0];
        
        int[] buffers = new int[2];
        gl.glGenBuffers(2, buffers, 0);
        linkVBO = buffers[0];
        linkInstanceVBO = buffers[1];
        
        // Setup base geometry
        gl.glBindVertexArray(linkVAO);
        
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, linkVBO);
        gl.glBufferData(GL4.GL_ARRAY_BUFFER, lineVertices.length * 4, 
                        FloatBuffer.wrap(lineVertices), GL4.GL_STATIC_DRAW);
        
        gl.glVertexAttribPointer(0, 2, GL4.GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(0);
        
        // Setup instance buffer
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, linkInstanceVBO);
        gl.glBufferData(GL4.GL_ARRAY_BUFFER, MAX_LINKS * LINK_INSTANCE_SIZE * 4, 
                        null, GL4.GL_DYNAMIC_DRAW);
        
        // Instance line start (2 floats)
        gl.glVertexAttribPointer(1, 2, GL4.GL_FLOAT, false, LINK_INSTANCE_SIZE * 4, 0);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribDivisor(1, 1);
        
        // Instance line end (2 floats)
        gl.glVertexAttribPointer(2, 2, GL4.GL_FLOAT, false, LINK_INSTANCE_SIZE * 4, 2 * 4);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribDivisor(2, 1);
        
        // Instance color (4 floats)
        gl.glVertexAttribPointer(3, 4, GL4.GL_FLOAT, false, LINK_INSTANCE_SIZE * 4, 4 * 4);
        gl.glEnableVertexAttribArray(3);
        gl.glVertexAttribDivisor(3, 1);
        
        // Instance width (1 float)
        gl.glVertexAttribPointer(4, 1, GL4.GL_FLOAT, false, LINK_INSTANCE_SIZE * 4, 7 * 4);
        gl.glEnableVertexAttribArray(4);
        gl.glVertexAttribDivisor(4, 1);
        
        gl.glBindVertexArray(0);
    }
    
    /**
     * Update view and projection matrices
     */
    public void updateMatrices(GL4 gl, ViewportManager viewport) {
        float[] viewMatrix = viewport.getViewMatrix();
        float[] projMatrix = viewport.getProjectionMatrix();
        
        // Update node shader matrices
        gl.glUseProgram(nodeShaderProgram.program());
        gl.glUniformMatrix4fv(nodeViewMatrixLoc, 1, false, viewMatrix, 0);
        gl.glUniformMatrix4fv(nodeProjMatrixLoc, 1, false, projMatrix, 0);
        
        // Update link shader matrices
        gl.glUseProgram(linkShaderProgram.program());
        gl.glUniformMatrix4fv(linkViewMatrixLoc, 1, false, viewMatrix, 0);
        gl.glUniformMatrix4fv(linkProjMatrixLoc, 1, false, projMatrix, 0);
        
        gl.glUseProgram(0);
    }
    
    /**
     * Update projection matrix only (for window resize)
     */
    public void updateProjection(GL4 gl, int width, int height) {
        // This is handled by ViewportManager.getProjectionMatrix()
        // Just trigger a matrix update
    }
    
    /**
     * Render all nodes using GPU instancing
     */
    public void renderNodes(GL4 gl, List<SchematicNode> nodes) {
        if (nodes.isEmpty()) return;
        
        // Update instance data
        updateNodeInstanceData(nodes);
        
        // Upload instance data to GPU
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, nodeInstanceVBO);
        gl.glBufferSubData(GL4.GL_ARRAY_BUFFER, 0, nodeInstanceCount * NODE_INSTANCE_SIZE * 4,
                          nodeInstanceBuffer);
        
        // Render all instances
        gl.glUseProgram(nodeShaderProgram.program());
        gl.glBindVertexArray(nodeVAO);
        gl.glDrawArraysInstanced(GL4.GL_TRIANGLES, 0, 6, nodeInstanceCount);
        gl.glBindVertexArray(0);
        gl.glUseProgram(0);
    }
    
    /**
     * Render all links using GPU instancing
     */
    public void renderLinks(GL4 gl, List<SchematicLink> links) {
        if (links.isEmpty()) return;
        
        // Update instance data
        updateLinkInstanceData(links);
        
        // Upload instance data to GPU
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, linkInstanceVBO);
        gl.glBufferSubData(GL4.GL_ARRAY_BUFFER, 0, linkInstanceCount * LINK_INSTANCE_SIZE * 4,
                          linkInstanceBuffer);
        
        // Render all instances
        gl.glUseProgram(linkShaderProgram.program());
        gl.glBindVertexArray(linkVAO);
        gl.glDrawArraysInstanced(GL4.GL_TRIANGLE_STRIP, 0, 4, linkInstanceCount);
        gl.glBindVertexArray(0);
        gl.glUseProgram(0);
    }
    
    /**
     * Render UI overlays (selection highlights, etc.)
     */
    public void renderOverlays(GL4 gl, List<SchematicNode> selectedNodes) {
        // For now, selection highlighting is handled in the node colors
        // Future: render selection rectangles, hover effects, etc.
    }
    
    /**
     * Update node instance data buffer
     */
    private void updateNodeInstanceData(List<SchematicNode> nodes) {
        nodeInstanceBuffer.clear();
        nodeInstanceCount = Math.min(nodes.size(), MAX_NODES);
        
        for (int i = 0; i < nodeInstanceCount; i++) {
            SchematicNode node = nodes.get(i);
            float[] color = node.getColorComponents();
            
            // Position (2 floats)
            nodeInstanceBuffer.put(node.getX());
            nodeInstanceBuffer.put(node.getY());
            
            // Size (2 floats)
            nodeInstanceBuffer.put(node.getWidth());
            nodeInstanceBuffer.put(node.getHeight());
            
            // Color (4 floats)
            nodeInstanceBuffer.put(color[0]);
            nodeInstanceBuffer.put(color[1]);
            nodeInstanceBuffer.put(color[2]);
            nodeInstanceBuffer.put(color[3]);
            
            // Type (1 float)
            nodeInstanceBuffer.put(node.getTypeId());
        }
        
        nodeInstanceBuffer.flip();
    }
    
    /**
     * Update link instance data buffer
     */
    private void updateLinkInstanceData(List<SchematicLink> links) {
        linkInstanceBuffer.clear();
        linkInstanceCount = Math.min(links.size(), MAX_LINKS);
        
        for (int i = 0; i < linkInstanceCount; i++) {
            SchematicLink link = links.get(i);
            float[] color = link.getColorComponents();
            
            // For now, render simple direct lines
            // TODO: Handle waypoints and proper link routing
            
            // Line start (2 floats) - placeholder values
            linkInstanceBuffer.put(0.0f);  // Will be calculated from nodes
            linkInstanceBuffer.put(0.0f);
            
            // Line end (2 floats) - placeholder values
            linkInstanceBuffer.put(100.0f);
            linkInstanceBuffer.put(100.0f);
            
            // Color (4 floats)
            linkInstanceBuffer.put(color[0]);
            linkInstanceBuffer.put(color[1]);
            linkInstanceBuffer.put(color[2]);
            linkInstanceBuffer.put(color[3]);
            
            // Width (1 float)
            linkInstanceBuffer.put(link.getEffectiveWidth());
        }
        
        linkInstanceBuffer.flip();
    }
    
    /**
     * Cleanup OpenGL resources
     */
    public void cleanup(GL4 gl) {
        if (nodeShaderProgram != null) {
            nodeShaderProgram.destroy(gl);
        }
        if (linkShaderProgram != null) {
            linkShaderProgram.destroy(gl);
        }
        
        // Cleanup buffers and arrays
        if (nodeVAO != 0) {
            gl.glDeleteVertexArrays(1, new int[]{nodeVAO}, 0);
        }
        if (nodeVBO != 0) {
            gl.glDeleteBuffers(1, new int[]{nodeVBO}, 0);
        }
        if (nodeInstanceVBO != 0) {
            gl.glDeleteBuffers(1, new int[]{nodeInstanceVBO}, 0);
        }
        if (linkVAO != 0) {
            gl.glDeleteVertexArrays(1, new int[]{linkVAO}, 0);
        }
        if (linkVBO != 0) {
            gl.glDeleteBuffers(1, new int[]{linkVBO}, 0);
        }
        if (linkInstanceVBO != 0) {
            gl.glDeleteBuffers(1, new int[]{linkInstanceVBO}, 0);
        }
    }
    
    /**
     * Create and compile a shader
     */
    private int createShader(GL4 gl, int shaderType, String source) {
        int shader = gl.glCreateShader(shaderType);
        if (shader == 0) {
            System.err.println("Failed to create shader");
            return 0;
        }
        
        gl.glShaderSource(shader, 1, new String[]{source}, null);
        gl.glCompileShader(shader);
        
        // Check compilation status
        int[] compiled = new int[1];
        gl.glGetShaderiv(shader, GL4.GL_COMPILE_STATUS, compiled, 0);
        
        if (compiled[0] == 0) {
            // Get error message
            int[] logLength = new int[1];
            gl.glGetShaderiv(shader, GL4.GL_INFO_LOG_LENGTH, logLength, 0);
            
            if (logLength[0] > 0) {
                byte[] log = new byte[logLength[0]];
                gl.glGetShaderInfoLog(shader, logLength[0], null, 0, log, 0);
                System.err.println("Shader compilation error: " + new String(log));
            }
            
            gl.glDeleteShader(shader);
            return 0;
        }
        
        System.out.println("Shader compiled successfully");
        return shader;
    }
    
    /**
     * Simple wrapper for shader program
     */
    private static class SimpleShaderProgram extends ShaderProgram {
        private final int programId;
        
        public SimpleShaderProgram(int programId) {
            super();
            this.programId = programId;
        }
        
        @Override
        public int program() {
            return programId;
        }
    }
}