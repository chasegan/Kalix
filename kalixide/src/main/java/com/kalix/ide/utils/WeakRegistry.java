package com.kalix.ide.utils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The live instances of a component class, so a preference change can be pushed to
 * every one of them.
 *
 * <p>Entries are held weakly: a closed window's components are collected as usual,
 * and their entries are dropped the next time the registry is swept. Safe to use from
 * any thread; the action runs while the registry is locked, so it must not register.
 *
 * @param <T> the registered type
 */
public final class WeakRegistry<T> {

    private final List<WeakReference<T>> entries = new ArrayList<>();

    /** Registers a live instance. */
    public void add(T instance) {
        synchronized (entries) {
            entries.add(new WeakReference<>(instance));
        }
    }

    /** Applies an action to every live instance. */
    public void forEach(Consumer<? super T> action) {
        sweep(action);
    }

    /**
     * Applies an action to every live instance of the given type, skipping the rest.
     *
     * @param type   the instances to visit
     * @param action what to do with each one
     * @param <S>    the visited type
     */
    public <S> void forEach(Class<S> type, Consumer<? super S> action) {
        sweep(instance -> {
            if (type.isInstance(instance)) {
                action.accept(type.cast(instance));
            }
        });
    }

    /** Visits every live instance, dropping the entries of collected ones on the way. */
    private void sweep(Consumer<? super T> action) {
        synchronized (entries) {
            Iterator<WeakReference<T>> iterator = entries.iterator();
            while (iterator.hasNext()) {
                T instance = iterator.next().get();
                if (instance == null) {
                    iterator.remove();
                } else {
                    action.accept(instance);
                }
            }
        }
    }
}
