package com.kalix.ide.utils;

import java.util.Objects;

/**
 * The identity of a cluster: its name folded for comparison. Cluster names are matched
 * ignoring case, as node names are (ADR-0010 §6.2; {@link EngineNames#nodeKey}), so
 * {@code Teemburra} and {@code teemburra} make one key. This is the one place that says
 * how cluster names compare, wherever they are compared.
 *
 * <p>A key is what a map of clusters is keyed by; a spelling stays a {@code String} and is
 * what is shown (ADR-0003 §2.1, §2.4). The type keeps the two apart: a spelling can't be
 * used as a key by mistake, and the constructor folds any spelling, so there is no way to
 * make a key that isn't folded.</p>
 */
public record ClusterKey(String value) {

    public ClusterKey {
        value = EngineNames.nodeKey(Objects.requireNonNull(value, "cluster name"));
    }
}
