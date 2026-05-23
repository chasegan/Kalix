package com.kalix.ide.tableview;

import com.kalix.ide.tableview.definitions.Gr4jParamsDefinition;
import com.kalix.ide.tableview.definitions.LinearCombinationDataRefDefinition;
import com.kalix.ide.tableview.definitions.LossTableDefinition;
import com.kalix.ide.tableview.definitions.RoutingPwlDefinition;
import com.kalix.ide.tableview.definitions.SacramentoParamsDefinition;
import com.kalix.ide.tableview.definitions.SplitterTableDefinition;
import com.kalix.ide.tableview.definitions.StorageDimensionsDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of supported table property definitions, keyed by
 * {@code (nodeType, propertyName)}.
 *
 * <p>Multiple definitions may be registered for the same key when the same
 * property can take more than one value shape (for example a node parameter
 * that may be either a plain table or a linear combination of data
 * references). Each definition self-declares which value shapes it handles
 * via {@link TablePropertyDefinition#canHandleValue(String)}; the registry
 * walks its candidates in registration order and returns the first that
 * accepts the value.</p>
 *
 * <p><b>Register more specific definitions first</b> - if two definitions can
 * both claim a value, the earlier registration wins.</p>
 */
public class TablePropertyRegistry {

    private static final TablePropertyRegistry INSTANCE = new TablePropertyRegistry();

    private final Map<String, List<TablePropertyDefinition>> definitions = new HashMap<>();

    private TablePropertyRegistry() {
        registerBuiltInDefinitions();
    }

    public static TablePropertyRegistry getInstance() {
        return INSTANCE;
    }

    private void registerBuiltInDefinitions() {
        register(new SacramentoParamsDefinition());
        register(new Gr4jParamsDefinition());
        register(new StorageDimensionsDefinition());
        register(new RoutingPwlDefinition());
        register(new SplitterTableDefinition());
        register(new LossTableDefinition());

        // Rainfall-runoff nodes accept a linear combination of data references
        // as the "rain" input; one definition class covers both node types.
        register(new LinearCombinationDataRefDefinition("sacramento", "rain"));
        register(new LinearCombinationDataRefDefinition("gr4j", "rain"));
    }

    /**
     * Registers a definition. May be called multiple times for the same
     * {@code (nodeType, propertyName)} key; ordering matters (see class doc).
     */
    public void register(TablePropertyDefinition definition) {
        String key = makeKey(definition.getNodeType(), definition.getPropertyName());
        definitions.computeIfAbsent(key, k -> new ArrayList<>()).add(definition);
    }

    /**
     * Finds the first registered definition that both targets this
     * {@code (nodeType, propertyName)} and accepts the given value via
     * {@link TablePropertyDefinition#canHandleValue(String)}.
     *
     * @return the matching definition, or {@code null} if none
     */
    public TablePropertyDefinition findHandler(String nodeType, String propertyName, String value) {
        if (nodeType == null || propertyName == null) {
            return null;
        }
        List<TablePropertyDefinition> candidates = definitions.get(makeKey(nodeType, propertyName));
        if (candidates == null) {
            return null;
        }
        for (TablePropertyDefinition def : candidates) {
            if (def.canHandleValue(value)) {
                return def;
            }
        }
        return null;
    }

    private String makeKey(String nodeType, String propertyName) {
        return nodeType.toLowerCase() + ":" + propertyName.toLowerCase();
    }
}
