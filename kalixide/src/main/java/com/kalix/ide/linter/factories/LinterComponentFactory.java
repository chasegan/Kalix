package com.kalix.ide.linter.factories;

import com.kalix.ide.linter.LinterHighlighter;
import com.kalix.ide.linter.LinterManager;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.events.ValidationEventManager;
import com.kalix.ide.linter.managers.LinterOrchestrator;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.ui.ErrorNavigationManager;
import com.kalix.ide.linter.ui.LinterTooltipManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and configuring linter components with proper dependency injection.
 * Centralizes component creation and ensures consistent initialization.
 */
public class LinterComponentFactory {

    /**
     * Create a fully configured LinterManager with all dependencies.
     */
    public static LinterManager createLinterManager(RSyntaxTextArea textArea, SchemaManager schemaManager) {
        // Create shared issue tracking map
        ConcurrentHashMap<Integer, ValidationIssue> issuesByLine = new ConcurrentHashMap<>();

        // Create components
        LinterOrchestrator orchestrator = new LinterOrchestrator(schemaManager);
        LinterHighlighter highlighter = new LinterHighlighter(textArea);
        LinterTooltipManager tooltipManager = new LinterTooltipManager(textArea, issuesByLine);
        ErrorNavigationManager navigationManager = new ErrorNavigationManager(textArea);

        // Create LinterManager with dependencies
        LinterManager linterManager = new LinterManager(
            textArea,
            schemaManager,
            orchestrator,
            highlighter,
            tooltipManager,
            navigationManager,
            issuesByLine
        );

        // Create ValidationEventManager with LinterManager as trigger
        ValidationEventManager eventManager = new ValidationEventManager(textArea, linterManager);
        linterManager.setEventManager(eventManager);

        return linterManager;
    }
}