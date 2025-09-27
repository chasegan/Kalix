# Kalix IDE Docking Package

A bespoke docking system designed specifically for the Kalix IDE, providing intuitive drag-and-drop panel management with clean visual feedback.

## Features

### Core Functionality
- **F4 Activation**: Press F4 on any dockable panel to enter docking mode
- **Visual Feedback**: Translucent blue highlighting with grip handle
- **Drag and Drop**: Intuitive drag operations using the grip handle
- **Window Detachment**: Drop panels outside valid zones to create new windows
- **Multiple Drop Zones**: Support for various container types and layouts

### Design Principles
- **Unobtrusive**: Normal operation unchanged - docking only activated on demand
- **Clean Interface**: Simple API for easy integration
- **Extensible**: Interface-based design for custom behaviors
- **Lightweight**: Minimal performance impact when not in use

## Visual Design

### Colors
- **Highlight Color**: Translucent blue (`new Color(0, 120, 255, 100)`)
- **Grip Color**: Darker blue (`new Color(0, 80, 180, 200)`)
- **Grip Dots**: Using highlight color with drop shadow effect

### Grip Design
- **Size**: 30px × 20px rectangle in top-left corner
- **Pattern**: 3×2 grid of dots with internal drop shadows
- **Effect**: Dots appear as holes/indentations for tactile appearance

## Usage Examples

### Basic Dockable Panel
```java
// Create a dockable panel
DockablePanel panel = new DockablePanel(new BorderLayout());
panel.add(new JLabel("My Content"), BorderLayout.CENTER);

// Add to container
parentContainer.add(panel);
```

### Wrapping Existing Components
```java
// Make existing components dockable
MapPanel mapPanel = new MapPanel();
DockableMapPanel dockableMap = new DockableMapPanel(mapPanel);

EnhancedTextEditor textEditor = new EnhancedTextEditor();
DockableTextEditor dockableEditor = new DockableTextEditor(textEditor);
```

### Setting Up Drop Zones
```java
DockingManager manager = DockingManager.getInstance();

// Register containers as drop zones
manager.registerDropZone(new BasicDockZone(leftPanel));
manager.registerDropZone(new BasicDockZone(rightPanel));

// Custom drop zone behavior
manager.registerDropZone(new BasicDockZone(centerPanel) {
    @Override
    public boolean acceptPanel(DockablePanel panel, Point dropPoint) {
        // Custom integration logic
        return super.acceptPanel(panel, dropPoint);
    }
});
```

## Integration with KalixIDE

The docking system is designed to integrate seamlessly with existing KalixIDE components:

1. **MapPanel Integration**: `DockableMapPanel` wraps the existing `MapPanel`
2. **Text Editor Integration**: `DockableTextEditor` wraps `EnhancedTextEditor`
3. **Split Pane Support**: Custom drop zones for `JSplitPane` layouts
4. **Menu Integration**: Docking actions can be added to existing menus

## Testing

### Test Applications
- **`DockingTestApp`**: Basic functionality demonstration
- **`KalixDockingDemo`**: Integration with real KalixIDE components

### Manual Testing
1. Run the demo application
2. Click on any panel and press F4
3. Observe the blue highlight and grip appearance
4. Drag the grip to move panels between areas
5. Drag outside the window to create detached windows
6. Press Escape to cancel docking mode

## Architecture

### Key Classes
- **`DockablePanel`**: Main component, extends JPanel with docking capabilities
- **`DockingManager`**: Singleton coordinator for drag/drop operations
- **`DockGrip`**: Visual grip with dot pattern and positioning logic
- **`DockHighlighter`**: Manages translucent highlighting effects
- **`DockZone`**: Interface for drop target areas
- **`BasicDockZone`**: Default implementation for standard containers
- **`DockingWindow`**: Specialized JFrame for detached panels

### Design Patterns Used
- **Singleton**: `DockingManager` ensures single coordination point
- **Observer**: Drag events are propagated to interested parties
- **Strategy**: `DockZone` interface allows custom drop behaviors
- **Wrapper**: Component wrappers like `DockableMapPanel` preserve existing APIs

## Constants

All docking-related constants are centralized in `UIConstants.Docking`:
- Trigger key (F4)
- Colors (translucent blues)
- Dimensions (grip size, margins)
- Drag thresholds

## Future Enhancements

- Persistence of panel layouts
- Tabbed container support for multiple panels
- Snap-to-grid functionality
- Keyboard-only docking operations
- Theme integration for color customization

## Installation

The docking package is included as part of the KalixIDE project. No additional dependencies are required beyond standard Java Swing components.