# Kalix GUI

A Java Swing GUI application for the Kalix hydrologic modeling platform.

## Setup with IntelliJ IDEA

1. Open IntelliJ IDEA
2. File → Open → Select this directory (`kalixgui`)
3. IntelliJ will automatically detect the Gradle project
4. Wait for Gradle sync to complete
5. Run the application:
   - Open `src/main/java/com/kalix/gui/KalixGUI.java`
   - Click the green arrow next to the `main` method

## Manual Build & Run

```bash
cd src/main/java
javac com/kalix/gui/KalixGUI.java
java com.kalix.gui.KalixGUI
```

## Features Completed

✅ **Phase 1**: Basic application structure with:
- Menu bar (File, Edit, View, Help menus)
- Status bar showing application status
- Split-pane layout (map panel on left, text editor on right)
- Basic zoom functionality in map panel
- Grid display in map view

## Next Steps

See `initial_plan.txt` for the complete development roadmap.