/**
 * Bespoke docking package for Kalix IDE.
 * 
 * <p>This package provides a complete docking system that allows panels to be
 * dragged and dropped within the application, as well as detached to separate
 * windows. The system is designed to be unobtrusive and provide clean visual
 * feedback during docking operations.</p>
 * 
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link com.kalix.ide.docking.DockablePanel} - The main dockable component that extends JPanel</li>
 *   <li>{@link com.kalix.ide.docking.DockingManager} - Central coordinator for all docking operations</li>
 *   <li>{@link com.kalix.ide.docking.DockGrip} - Visual grip component with dot pattern</li>
 *   <li>{@link com.kalix.ide.docking.DockHighlighter} - Provides visual highlighting during docking</li>
 *   <li>{@link com.kalix.ide.docking.DockZone} - Interface for areas that accept docked panels</li>
 *   <li>{@link com.kalix.ide.docking.DockingWindow} - Detached window for undocked panels</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <p>To make a component dockable, either extend {@link com.kalix.ide.docking.DockablePanel}
 * or wrap an existing component using the wrapper classes like
 * {@link com.kalix.ide.docking.DockableMapPanel} or {@link com.kalix.ide.docking.DockableTextEditor}.</p>
 * 
 * <p>Users activate docking mode by pressing F4 when a dockable panel has focus.
 * This shows a translucent blue highlight around the panel and displays a grip
 * handle in the top-left corner. The panel can then be dragged to new locations
 * or detached to create a new window.</p>
 * 
 * <h2>Design Features</h2>
 * <ul>
 *   <li><b>Unobtrusive:</b> Normal operation is unchanged - docking mode is only activated on demand</li>
 *   <li><b>Visual Feedback:</b> Clear visual indicators using translucent blue colors</li>
 *   <li><b>Clean Interface:</b> Simple API that can be easily integrated into existing applications</li>
 *   <li><b>Extensible:</b> Interface-based design allows for custom drop zones and behaviors</li>
 * </ul>
 * 
 * <h2>Integration Example</h2>
 * <pre>{@code
 * // Create a dockable version of an existing component
 * JPanel myPanel = new JPanel();
 * DockablePanel dockablePanel = new DockablePanel();
 * dockablePanel.add(myPanel, BorderLayout.CENTER);
 * 
 * // Register drop zones
 * DockingManager manager = DockingManager.getInstance();
 * manager.registerDropZone(new BasicDockZone(someContainer));
 * 
 * // Add to your UI
 * parentContainer.add(dockablePanel);
 * }</pre>
 * 
 * @author Copilot Code Assistant
 * @version 1.0
 * @see com.kalix.ide.docking.DockingTestApp
 * @see com.kalix.ide.docking.KalixDockingDemo
 */
package com.kalix.ide.docking;