/**
 * Kalix IDE Docking System
 *
 * A custom docking framework designed specifically for the Kalix IDE application.
 * Provides drag-and-drop panel docking capabilities with visual feedback and
 * cross-window communication.
 *
 * <h2>Key Components:</h2>
 * <ul>
 *   <li>{@link com.kalix.ide.docking.DockablePanel} - Base class for panels that can be docked</li>
 *   <li>{@link com.kalix.ide.docking.DockingArea} - Container that accepts docked panels</li>
 *   <li>{@link com.kalix.ide.docking.DockingWindow} - Floating windows for undocked panels</li>
 *   <li>{@link com.kalix.ide.docking.DockingManager} - Coordinates drag/drop operations</li>
 *   <li>{@link com.kalix.ide.docking.DockingContext} - Service locator for panel communication</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <ol>
 *   <li>Hover over a DockablePanel</li>
 *   <li>Press F6 to activate docking mode (shows blue highlight and drag grip)</li>
 *   <li>Click and drag the grip to undock the panel</li>
 *   <li>Drop into a DockingArea or outside to create a floating window</li>
 * </ol>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Translucent blue highlighting during activation</li>
 *   <li>Dotted grip with inset shadow effect</li>
 *   <li>Real-time drop zone highlighting</li>
 *   <li>Automatic floating window creation and cleanup</li>
 *   <li>Panel-to-window communication via service locator pattern</li>
 * </ul>
 *
 * @author Kalix Development Team
 * @version 1.0
 * @since 2025-09-27
 */
package com.kalix.ide.docking;