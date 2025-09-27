# Docking System Visual Guide

## Normal Operation (Docking Mode Inactive)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Kalix IDE - Main Window                                           [_][▢][X] │
├─────────────────────────────────────────────────────────────────────────┤
│ File  Edit  View  Tools  Help                                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│ ┌─────────────────────┐ │ ┌─────────────────────────────────────────────┐ │
│ │                     │ │ │                                             │ │
│ │     Map Panel       │ │ │           Text Editor                       │ │
│ │                     │ │ │                                             │ │
│ │  ○────○────○        │ │ │  [NODES]                                    │ │
│ │  │    │    │        │ │ │  Node1, 100, 200, Reservoir                 │ │
│ │  │    ○    │        │ │ │  Node2, 300, 200, Junction                  │ │
│ │  │         │        │ │ │  Node3, 500, 200, Outfall                   │ │
│ │  ○─────────○        │ │ │                                             │ │
│ │                     │ │ │  [LINKS]                                    │ │
│ │                     │ │ │  Link1, Node1, Node2                        │ │
│ │                     │ │ │  Link2, Node2, Node3                        │ │
│ │                     │ │ │                                             │ │
│ └─────────────────────┘ │ └─────────────────────────────────────────────┘ │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ Ready                                                                   │
└─────────────────────────────────────────────────────────────────────────┘
```

**State**: Normal operation - panels look and behave exactly as they did before

## Docking Mode Activated (Press F4 on Map Panel)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Kalix IDE - Main Window                                           [_][▢][X] │
├─────────────────────────────────────────────────────────────────────────┤
│ File  Edit  View  Tools  Help                                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│ ╔═════════════════════╗ │ ┌─────────────────────────────────────────────┐ │
│ ║ ┌───┐               ║ │ │           Text Editor                       │ │
│ ║ │ : │    Map Panel  ║ │ │                                             │ │
│ ║ │: :│               ║ │ │  [NODES]                                    │ │
│ ║ └───┘  ○────○────○  ║ │ │  Node1, 100, 200, Reservoir                 │ │
│ ║        │    │    │  ║ │ │  Node2, 300, 200, Junction                  │ │
│ ║        │    ○    │  ║ │ │  Node3, 500, 200, Outfall                   │ │
│ ║        │         │  ║ │ │                                             │ │
│ ║        ○─────────○  ║ │ │  [LINKS]                                    │ │
│ ║                     ║ │ │  Link1, Node1, Node2                        │ │
│ ║                     ║ │ │  Link2, Node2, Node3                        │ │
│ ║                     ║ │ │                                             │ │
│ ╚═════════════════════╝ │ └─────────────────────────────────────────────┘ │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ Ready                                                                   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Changes**:
- **Blue translucent border** around the map panel (shown as ║═══║)
- **Grip handle** in top-left corner with dot pattern (┌───┐ with : : dots)
- **Grip has drop shadow effect** making dots appear as holes

## During Drag Operation

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Kalix IDE - Main Window                                           [_][▢][X] │
├─────────────────────────────────────────────────────────────────────────┤
│ File  Edit  View  Tools  Help                                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                 ┌─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐                 │
│ [Empty space where             │     Map Panel Preview   │                │
│  map panel was]                │                         │                │
│                                │    ○────○────○         │                │
│                                │    │    │    │         │                │
│                                │    │    ○    │         │                │
│                                │    │         │         │                │
│                                │    ○─────────○         │                │
│                                 └─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘                 │
│                         │ ┌─────────────────────────────────────────────┐ │
│                         │ │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓         Text Editor         ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓                             ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓  [NODES]                    ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓  Node1, 100, 200, Reservoir ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓  Node2, 300, 200, Junction  ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓  Node3, 500, 200, Outfall   ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ │▓▓▓                             ▓▓▓▓▓▓▓▓▓▓▓▓▓│ │
│                         │ └─────────────────────────────────────────────┘ │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ Dragging panel - drop outside window to create new window              │
└─────────────────────────────────────────────────────────────────────────┘
```

**Changes**:
- **Glass pane** shows translucent preview of dragged panel following cursor
- **Drop zone highlighting** with green background (▓▓▓) when hovering over valid targets
- **Real-time feedback** as user moves the panel around

## Panel Detached to New Window

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Kalix IDE - Main Window                                           [_][▢][X] │
├─────────────────────────────────────────────────────────────────────────┤
│ File  Edit  View  Tools  Help                                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│ [Empty space where        │ ┌─────────────────────────────────────────────┐ │
│  map panel was]           │ │           Text Editor                       │ │
│                           │ │                                             │ │
│                           │ │  [NODES]                                    │ │
│                           │ │  Node1, 100, 200, Reservoir                 │ │
│                           │ │  Node2, 300, 200, Junction                  │ │
│                           │ │  Node3, 500, 200, Outfall                   │ │
│                           │ │                                             │ │
│                           │ │  [LINKS]                                    │ │
│                           │ │  Link1, Node1, Node2                        │ │
│                           │ │  Link2, Node2, Node3                        │ │
│                           │ │                                             │ │
│                           │ └─────────────────────────────────────────────┘ │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ Map panel detached to new window                                        │
└─────────────────────────────────────────────────────────────────────────┘

    ┌───────────────────────────────┐     <- New detached window
    │ Docked Panel            [_][X] │
    ├───────────────────────────────┤
    │                               │
    │        Map Panel              │
    │                               │
    │   ○────○────○                 │
    │   │    │    │                 │
    │   │    ○    │                 │
    │   │         │                 │
    │   ○─────────○                 │
    │                               │
    │                               │
    └───────────────────────────────┘
```

**Result**: 
- Panel is now in its own independent window
- Original location in main window is empty (could be filled by layout manager)
- Detached window can accept other panels being dropped onto it
- Panel maintains full functionality in the new window

## Visual Design Details

### Colors Used
- **Highlight Border**: `Color(0, 120, 255, 100)` - Translucent blue
- **Grip Background**: `Color(0, 80, 180, 200)` - Darker blue
- **Grip Dots**: `Color(0, 120, 255, 150)` - Medium blue with drop shadow
- **Drop Zone**: `Color(0, 200, 100, 80)` - Translucent green

### Grip Pattern (Enlarged View)
```
┌─────────────────────────────┐
│ ●   ●   ●                   │  <- 3×2 dot pattern
│   ●   ●   ●                 │  <- Each dot has drop shadow
│                             │  <- Dots appear as indentations
│                             │
└─────────────────────────────┘
```

The grip uses a subtle drop shadow effect to make the dots appear as holes or indentations, giving it a tactile appearance that clearly indicates it's a draggable handle.