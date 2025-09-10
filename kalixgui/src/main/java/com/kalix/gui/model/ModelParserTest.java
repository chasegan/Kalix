package com.kalix.gui.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Simple test to verify model parsing works with example INI files.
 */
public class ModelParserTest {
    
    public static void main(String[] args) {
        System.out.println("Model Parser Test");
        System.out.println("=================");
        
        // Test with the example model
        Path examplePath = Paths.get("./example_models/model_3_abs.ini");
        if (!Files.exists(examplePath)) {
            System.err.println("Example model file not found: " + examplePath);
            System.exit(1);
        }
        
        try {
            String iniContent = Files.readString(examplePath);
            System.out.println("Loaded example model from: " + examplePath);
            System.out.println("Content length: " + iniContent.length() + " characters");
            
            // Parse the model
            System.out.println("\nParsing model...");
            ModelParser.ParseResult result = ModelParser.parse(iniContent);
            
            System.out.println("Found " + result.getNodes().size() + " nodes:");
            for (ModelNode node : result.getNodes()) {
                System.out.println("  " + node);
            }
            
            System.out.println("\nFound " + result.getLinks().size() + " links:");
            for (ModelLink link : result.getLinks()) {
                System.out.println("  " + link);
            }
            
            // Test with HydrologicalModel
            System.out.println("\nTesting HydrologicalModel integration...");
            HydrologicalModel model = new HydrologicalModel();
            model.parseFromIniText(iniContent);
            
            ModelStatistics stats = model.getStatistics();
            System.out.println("Model statistics: " + stats);
            
            System.out.println("\n✓ Model parsing test completed successfully!");
            
        } catch (IOException e) {
            System.err.println("Error reading example model: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error during parsing: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}