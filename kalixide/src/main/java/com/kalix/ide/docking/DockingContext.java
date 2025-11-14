package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Service locator and event bus for docking system communication.
 * Provides a way for docked panels to access services and communicate
 * with their parent windows regardless of their current docking state.
 */
public class DockingContext {
    private static DockingContext instance;

    // Service registry
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private final Map<String, Object> namedServices = new ConcurrentHashMap<>();

    // Event listeners
    private final Map<String, Consumer<DockingEvent>> eventListeners = new ConcurrentHashMap<>();

    // Action registry for menu commands
    private final Map<String, Action> actions = new ConcurrentHashMap<>();

    private DockingContext() {
        // Private constructor for singleton
    }

    public static DockingContext getInstance() {
        if (instance == null) {
            instance = new DockingContext();
        }
        return instance;
    }

    /**
     * Registers a service by its class type.
     */
    public <T> void registerService(Class<T> serviceClass, T serviceInstance) {
        services.put(serviceClass, serviceInstance);
    }

    /**
     * Registers a service by name.
     */
    public void registerService(String serviceName, Object serviceInstance) {
        namedServices.put(serviceName, serviceInstance);
    }

    /**
     * Gets a service by name.
     */
    @SuppressWarnings("unchecked")
    public <T> T getService(String serviceName) {
        return (T) namedServices.get(serviceName);
    }

    /**
     * Registers an action that can be invoked by docked panels.
     */
    public void registerAction(String actionName, Action action) {
        actions.put(actionName, action);
    }

    /**
     * Invokes an action by name.
     */
    public void invokeAction(String actionName) {
        Action action = actions.get(actionName);
        if (action != null) {
            action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionName));
        }
    }

    /**
     * Unregisters all services and listeners (cleanup).
     */
    public void cleanup() {
        services.clear();
        namedServices.clear();
        eventListeners.clear();
        actions.clear();
    }

    /**
     * Event class for docking system communication.
     */
    public static class DockingEvent {
        private final String type;
        private final Object source;
        private final String data;
        private final long timestamp;

        public DockingEvent(String type, Object source, String data) {
            this.type = type;
            this.source = source;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public String getType() {
            return type;
        }

        public Object getSource() {
            return source;
        }

        public String getData() {
            return data;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return String.format("DockingEvent{type='%s', source=%s, data='%s', timestamp=%d}",
                    type, source, data, timestamp);
        }
    }
}