package com.kalix.gui.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Library of inspirational quotes for use as default text in the model editor.
 * Provides a curated collection of quotes about creativity, craftsmanship, and innovation.
 */
public class QuoteLibrary {
    
    private static final List<String> QUOTES = new ArrayList<>();
    private static final Random random = new Random();
    
    static {
        // Initialize the quote library
        QUOTES.add("Simple can be harder than complex: You have to work hard to get your thinking clean to make it simple.\n– Steve Jobs");
        
        QUOTES.add("It is working within limits that the craftsman reveals himself.\n– Johann Wolfgang von Goethe");
        
        QUOTES.add("Logic will get you from A to B. Imagination will take you everywhere.\n– Albert Einstein");
        
        QUOTES.add("Art is never finished, only abandoned.\n– Leonardo da Vinci");
        
        QUOTES.add("She who works with her hands is a laborer. She who works with her hands and her head is a craftsman. She who works with her hands and her head and her heart is an artist.\n– Saint Francis of Assisi");
    }
    
    /**
     * Gets a random quote from the library.
     * 
     * @return A random inspirational quote
     */
    public static String getRandomQuote() {
        if (QUOTES.isEmpty()) {
            return "# Welcome, friend ...";
        }
        
        int index = random.nextInt(QUOTES.size());
        return formatAsComment(QUOTES.get(index));
    }
    
    /**
     * Gets a specific quote by index.
     * 
     * @param index The index of the quote (0-based)
     * @return The quote at the specified index, or a random quote if index is invalid
     */
    public static String getQuote(int index) {
        if (index < 0 || index >= QUOTES.size()) {
            return getRandomQuote();
        }
        return formatAsComment(QUOTES.get(index));
    }
    
    /**
     * Gets all quotes in the library.
     * 
     * @return List of all quotes
     */
    public static List<String> getAllQuotes() {
        return new ArrayList<>(QUOTES);
    }
    
    /**
     * Gets the number of quotes in the library.
     * 
     * @return Number of quotes available
     */
    public static int getQuoteCount() {
        return QUOTES.size();
    }
    
    /**
     * Adds a new quote to the library.
     * 
     * @param quote The quote to add
     */
    public static void addQuote(String quote) {
        if (quote != null && !quote.trim().isEmpty()) {
            QUOTES.add(quote.trim());
        }
    }
    
    /**
     * Formats a quote as a comment for use in model files.
     * Adds proper comment prefixes and spacing for readability.
     * 
     * @param quote The quote to format
     * @return The formatted quote as a comment block
     */
    private static String formatAsComment(String quote) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("#\n");

        // Split quote into lines and add comment prefix
        String[] lines = quote.split("\n");
        for (String line : lines) {
            formatted.append("# ").append(line.trim()).append("\n");
        }
        
        formatted.append("#\n");
        return formatted.toString();
    }
    
    /**
     * Gets a quote formatted for display in the editor with proper model file structure.
     * 
     * @return A random quote formatted as the default editor content
     */
    public static String getDefaultEditorContent() {
        return getRandomQuote();
    }
    
    /**
     * Private constructor to prevent instantiation.
     */
    private QuoteLibrary() {
        throw new UnsupportedOperationException("QuoteLibrary is a utility class and should not be instantiated");
    }
}