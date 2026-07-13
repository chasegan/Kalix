package com.kalix.ide.linter.model;

import com.kalix.ide.language.ExpressionLanguage;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The {@code [fn]} section, parsed once per lint pass into a lookup by
 * (lowercased) function name.
 *
 * <p>Before this existed, every {@code fn.foo(...)} call site re-parsed all
 * {@code [fn]} keys to find one function's arity ({@code O(fns × call-sites)}
 * string work per lint pass), and the recursion scan regex-matched raw body
 * text. This registry is built by ONE shared signature parser
 * ({@link #parseSignature(String)}) and answers existence, arity, and the call
 * graph from a single source.</p>
 *
 * <p>It is memoized per {@link INIModelParser.ParsedModel} via
 * {@link #forModel(INIModelParser.ParsedModel)} so it is built once per lint
 * pass even though many per-node {@link ValidationContext}s wrap the same
 * model.</p>
 */
public final class FnRegistry {

    /** A parsed signature key: name + ordered params, or an error message. */
    public record Signature(String name, List<String> params, String error) {}

    /** One user-defined function: its lowercased name, params, definition line,
     *  and body text (for the recursion scan). */
    public record Entry(String name, List<String> params, int line, String body) {
        public int arity() {
            return params.size();
        }
    }

    private static final FnRegistry EMPTY = new FnRegistry(Map.of());

    // Memoize per parsed model so the registry is built once per lint pass.
    // WeakHashMap: entries clear when a model (parsed fresh each pass) is GC'd.
    private static final Map<INIModelParser.ParsedModel, FnRegistry> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<String, Entry> byName;

    private FnRegistry(Map<String, Entry> byName) {
        this.byName = byName;
    }

    /** The registry for a model, built once and cached. Never null. */
    public static FnRegistry forModel(INIModelParser.ParsedModel model) {
        if (model == null) {
            return EMPTY;
        }
        return CACHE.computeIfAbsent(model, FnRegistry::build);
    }

    private static FnRegistry build(INIModelParser.ParsedModel model) {
        INIModelParser.Section fnSection = model.getSections().get("fn");
        if (fnSection == null) {
            return EMPTY;
        }
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (INIModelParser.Property prop : fnSection.getAllProperties()) {
            Signature sig = parseSignature(prop.getKey());
            if (sig.error() != null) {
                continue; // malformed keys are reported by FnSectionValidator
            }
            // Bare-name rule guarantees the name is already lowercase; keep the
            // FIRST definition (duplicates are reported by FnSectionValidator).
            byName.putIfAbsent(sig.name(),
                    new Entry(sig.name(), sig.params(), prop.getLineNumber(), prop.getValue()));
        }
        return byName.isEmpty() ? EMPTY : new FnRegistry(Map.copyOf(byName));
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public boolean contains(String lowerName) {
        return byName.containsKey(lowerName);
    }

    public Entry get(String lowerName) {
        return byName.get(lowerName);
    }

    /** Arity of a function by lowercase name, or null when it is undefined. */
    public Integer arity(String lowerName) {
        Entry e = byName.get(lowerName);
        return e == null ? null : e.arity();
    }

    /** All definitions, in file order. */
    public Collection<Entry> entries() {
        return byName.values();
    }

    /**
     * Parse a signature key {@code name(a, b)} / {@code name()} — the ONE shared
     * signature parser for the whole linter.
     *
     * <p>Uses java {@code split(",", -1)} to mirror the engine's {@code split(',')}:
     * trailing and empty parameters are kept and rejected (the engine now errors
     * "parameter is empty" on {@code foo(a,)}, {@code foo(,a)}, {@code foo(,)}).
     * Name and parameters are validated against the strict bare-name rule and the
     * reserved-name registry via {@link ExpressionLanguage}.</p>
     *
     * @return a {@link Signature} whose {@code error} is non-null when malformed
     */
    public static Signature parseSignature(String key) {
        String k = key.trim();
        int open = k.indexOf('(');
        if (open < 0 || !k.endsWith(")")) {
            return err("Invalid [fn] signature '" + key
                    + "': expected a signature like name(a, b) or name()");
        }

        String name = k.substring(0, open).trim();
        if (name.contains(".")) {
            return err("Invalid function name '" + name + "' (use a bare name; no dots)");
        }
        if (!ExpressionLanguage.isBareName(name)) {
            return err("Invalid function name '" + name
                    + "' (start with a lowercase letter; use lowercase letters, digits and underscores)");
        }
        if (ExpressionLanguage.reservedTier(name) != null) {
            return err("Function name '" + name + "' collides with a builtin function or reserved word");
        }

        String inner = k.substring(open + 1, k.length() - 1).trim();
        List<String> params = new ArrayList<>();
        if (!inner.isEmpty()) {
            for (String rawParam : inner.split(",", -1)) {
                String p = rawParam.trim();
                if (p.isEmpty()) {
                    return err("Empty parameter in signature '" + key
                            + "' (parameters cannot be empty; check for a trailing or doubled comma)");
                }
                if (p.contains(".")) {
                    return err("Invalid parameter '" + p + "' in signature '" + key
                            + "' (use a bare name; no dots)");
                }
                if (!ExpressionLanguage.isBareName(p)) {
                    return err("Invalid parameter '" + p + "' in signature '" + key
                            + "' (start with a lowercase letter; use lowercase letters, digits and underscores)");
                }
                if (ExpressionLanguage.reservedTier(p) != null) {
                    return err("Parameter '" + p + "' in signature '" + key
                            + "' collides with a builtin function or reserved word");
                }
                if (params.contains(p)) {
                    return err("Duplicate parameter '" + p + "' in signature '" + key + "'");
                }
                params.add(p);
            }
        }
        return new Signature(name, List.copyOf(params), null);
    }

    private static Signature err(String message) {
        return new Signature(null, List.of(), message);
    }
}
