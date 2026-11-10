package com.kalix.ide.linter.parsing;

import com.kalix.ide.utils.EngineNames;

/**
 * The annotation keys Kalix owns (ADR-0010 §4, §6): the IDE's one registry. A
 * dotted key such as {@code pycalib.bounds} belongs to the tool its prefix names
 * and is never looked up here. Adding a key is one constant and one answer each
 * from {@link #appliesTo}, {@link #isValidValue} and {@link #valueRule}.
 */
public enum AnnotationKey {

    /** A readable alternative name for a node. */
    AKA("aka"),
    /** The cluster a node belongs to. */
    CLUSTER("cluster"),
    /** The title of a collapsible text region, which may introduce any section. */
    REGION("region"),
    /** A node's position on the schematic. */
    LOC("loc");

    private static final String NODE_SECTION_PREFIX = "node.";

    private final String key;

    AnnotationKey(String key) {
        this.key = key;
    }

    /** The key as written in the file. */
    public String key() {
        return key;
    }

    /** The registered key spelled exactly {@code key}, or {@code null}. */
    public static AnnotationKey of(String key) {
        for (AnnotationKey candidate : values()) {
            if (candidate.key.equals(key)) {
                return candidate;
            }
        }
        return null;
    }

    /** True if the key belongs to another tool: it has a prefix before a dot. */
    public static boolean isToolKey(String key) {
        return key.indexOf('.') > 0;
    }

    /** True if this key means something on the section named {@code sectionName}. */
    public boolean appliesTo(String sectionName) {
        return switch (this) {
            case AKA, CLUSTER, LOC -> sectionName.startsWith(NODE_SECTION_PREFIX);
            case REGION -> true;
        };
    }

    /**
     * True if {@code value} is one this key accepts (ADR-0010 §4). Only a cluster
     * constrains its value today, by the node-name rule (ADR-0010 §6.2), asked of
     * {@link EngineNames} so the two rules can't drift apart. Nothing checks a
     * {@code loc} yet.
     */
    public boolean isValidValue(String value) {
        return switch (this) {
            case CLUSTER -> EngineNames.isValidNodeName(value);
            case AKA, REGION, LOC -> true;
        };
    }

    /**
     * The rule {@link #isValidValue} applies, in words, for every message that refuses
     * a value; {@code null} for a key that accepts any value.
     */
    public String valueRule() {
        return switch (this) {
            case CLUSTER -> "Cluster names may contain only letters, digits and underscores";
            case AKA, REGION, LOC -> null;
        };
    }
}
