package com.kalix.ide.tableview;

import com.kalix.ide.tableview.definitions.Gr4jParamsDefinition;
import com.kalix.ide.tableview.definitions.SacramentoParamsDefinition;
import com.kalix.ide.tableview.definitions.StorageDimensionsDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of all supported table property definitions.
 * Singleton pattern for global access.
 */
public class TablePropertyRegistry {

    private static final TablePropertyRegistry INSTANCE = new TablePropertyRegistry();

    private final Map<String, TablePropertyDefinition> definitions = new HashMap<>();

    private TablePropertyRegistry() {
        registerBuiltInDefinitions();
    }

    public static TablePropertyRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers the built-in property definitions.
     */
    private void registerBuiltInDefinitions() {
        register(new SacramentoParamsDefinition());
        register(new Gr4jParamsDefinition());
        register(new StorageDimensionsDefinition());
    }

    /**
     * Registers a property definition.
     *
     * @param definition The definition to register
     */
    public void register(TablePropertyDefinition definition) {
        String key = makeKey(definition.getNodeType(), definition.getPropertyName());
        definitions.put(key, definition);
    }

    /**
     * Gets the definition for a node type and property combination.
     *
     * @param nodeType     The node type (e.g., "sacramento", "storage")
     * @param propertyName The property name (e.g., "params", "dimensions")
     * @return The definition, or null if not supported
     */
    public TablePropertyDefinition getDefinition(String nodeType, String propertyName) {
        if (nodeType == null || propertyName == null) {
            return null;
        }
        return definitions.get(makeKey(nodeType, propertyName));
    }

    /**
     * Checks if a node type and property combination is supported.
     *
     * @param nodeType     The node type
     * @param propertyName The property name
     * @return true if table view is supported for this combination
     */
    public boolean isSupported(String nodeType, String propertyName) {
        return getDefinition(nodeType, propertyName) != null;
    }

    private String makeKey(String nodeType, String propertyName) {
        return nodeType.toLowerCase() + ":" + propertyName.toLowerCase();
    }
}
