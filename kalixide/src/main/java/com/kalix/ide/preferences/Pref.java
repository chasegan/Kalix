package com.kalix.ide.preferences;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A typed preference key: raw key string, storage tier, value type, and default
 * value bound together in a single constant.
 *
 * <p>Each constant in {@link PreferenceKeys} is created through one of the
 * factories below, which encode the tier (file-based {@code kalix_prefs.json}
 * vs the OS preference store) and the value type once. Call sites then use
 * {@link #get()} / {@link #set(Object)} and can neither disagree about the
 * default nor read a key through the wrong tier or type.
 *
 * <p>Reads never write: a missing (or type-invalid) stored value simply yields
 * {@link #defaultValue()}. Only {@link #set(Object)} persists.
 */
public final class Pref<T> {

    private final String key;
    private final T defaultValue;
    private final BiFunction<String, T, T> reader;
    private final BiConsumer<String, T> writer;

    private Pref(String key, T defaultValue,
                 BiFunction<String, T, T> reader, BiConsumer<String, T> writer) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.reader = reader;
        this.writer = writer;
    }

    // ==== Factories: file tier (kalix_prefs.json — portable, shareable) ====

    public static Pref<Boolean> fileBoolean(String key, boolean defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getFileBoolean, PreferenceManager::setFileBoolean);
    }

    public static Pref<Integer> fileInt(String key, int defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getFileInt, PreferenceManager::setFileInt);
    }

    public static Pref<Double> fileDouble(String key, double defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getFileDouble, PreferenceManager::setFileDouble);
    }

    public static Pref<String> fileString(String key, String defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getFileString, PreferenceManager::setFileString);
    }

    public static Pref<List<String>> fileStringList(String key, List<String> defaultValue) {
        return new Pref<>(key, List.copyOf(defaultValue),
            PreferenceManager::getFileStringList, PreferenceManager::setFileStringList);
    }

    // ==== Factories: OS tier (java.util.prefs — machine-local UI state) ====

    public static Pref<Boolean> osBoolean(String key, boolean defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getOsBoolean, PreferenceManager::setOsBoolean);
    }

    public static Pref<Integer> osInt(String key, int defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getOsInt, PreferenceManager::setOsInt);
    }

    public static Pref<String> osString(String key, String defaultValue) {
        return new Pref<>(key, defaultValue,
            PreferenceManager::getOsString, PreferenceManager::setOsString);
    }

    // ==== Instance API ====

    /** The stored value, or {@link #defaultValue()} when unset/invalid. Never writes. */
    public T get() {
        return reader.apply(key, defaultValue);
    }

    /** Stores the value; persisted immediately. */
    public void set(T value) {
        writer.accept(key, value);
    }

    /** The raw key string as stored on disk. */
    public String key() {
        return key;
    }

    /** The default returned by {@link #get()} when nothing valid is stored. */
    public T defaultValue() {
        return defaultValue;
    }

    @Override
    public String toString() {
        return key;
    }
}
