package com.kalix.gui.utils;

import javax.swing.*;
import java.awt.Component;

/**
 * Utility class for creating and displaying common dialog types.
 * Centralizes dialog creation to ensure consistent look and behavior.
 */
public class DialogUtils {
    
    /**
     * Shows an error dialog with a standardized title and icon.
     * 
     * @param parent the parent component for the dialog
     * @param message the error message to display
     */
    public static void showError(Component parent, String message) {
        showError(parent, message, "Error");
    }
    
    /**
     * Shows an error dialog with a custom title.
     * 
     * @param parent the parent component for the dialog
     * @param message the error message to display
     * @param title the dialog title
     */
    public static void showError(Component parent, String message, String title) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
    }
    
    /**
     * Shows an information dialog with a standardized title and icon.
     * 
     * @param parent the parent component for the dialog
     * @param message the information message to display
     */
    public static void showInfo(Component parent, String message) {
        showInfo(parent, message, "Information");
    }
    
    /**
     * Shows an information dialog with a custom title.
     * 
     * @param parent the parent component for the dialog
     * @param message the information message to display
     * @param title the dialog title
     */
    public static void showInfo(Component parent, String message, String title) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * Shows a warning dialog with a standardized title and icon.
     * 
     * @param parent the parent component for the dialog
     * @param message the warning message to display
     */
    public static void showWarning(Component parent, String message) {
        showWarning(parent, message, "Warning");
    }
    
    /**
     * Shows a warning dialog with a custom title.
     * 
     * @param parent the parent component for the dialog
     * @param message the warning message to display
     * @param title the dialog title
     */
    public static void showWarning(Component parent, String message, String title) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.WARNING_MESSAGE);
    }
    
    /**
     * Shows a confirmation dialog asking for Yes/No response.
     * 
     * @param parent the parent component for the dialog
     * @param message the question to ask
     * @param title the dialog title
     * @return true if user clicked Yes, false if No or closed dialog
     */
    public static boolean showConfirmation(Component parent, String message, String title) {
        int result = JOptionPane.showConfirmDialog(parent, message, title, 
                                                 JOptionPane.YES_NO_OPTION, 
                                                 JOptionPane.QUESTION_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }
    
    /**
     * Shows a confirmation dialog asking for Yes/No response with default title.
     * 
     * @param parent the parent component for the dialog
     * @param message the question to ask
     * @return true if user clicked Yes, false if No or closed dialog
     */
    public static boolean showConfirmation(Component parent, String message) {
        return showConfirmation(parent, message, "Confirm");
    }
    
    /**
     * Shows a Yes/No/Cancel confirmation dialog.
     * 
     * @param parent the parent component for the dialog
     * @param message the question to ask
     * @param title the dialog title
     * @return JOptionPane.YES_OPTION, JOptionPane.NO_OPTION, or JOptionPane.CANCEL_OPTION
     */
    public static int showYesNoCancelDialog(Component parent, String message, String title) {
        return JOptionPane.showConfirmDialog(parent, message, title,
                                           JOptionPane.YES_NO_CANCEL_OPTION,
                                           JOptionPane.QUESTION_MESSAGE);
    }
}