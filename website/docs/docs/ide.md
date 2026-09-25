---
title: "Kalix IDE"
---

# Kalix IDE

![](../assets/docs-using-ide/Screenshot_2025-10-10_at_12.16.40_pm.png)

## Interactions

**Rename node** from the schematic map context menu

![](../assets/docs-using-ide/image.png)

Model editor context menu item **Show on Map** will select and centre the node you’re working on in the schematic map

![](../assets/docs-using-ide/image_1.png)

Click and drag to select nodes on the schematic map. Then drag to move or ctrl+drag to rotate.

![](../assets/docs-using-ide/image_2.png)

**Draw a link** by hovering just outside a node until a ring appears, then dragging to the downstream node. The cursor shows when a drop isn't allowed (the same node, an existing link, or a loop). Esc cancels; Ctrl+Z undoes.

The link is written as `ds_N = <downstream>` at the first free outlet. If every outlet the node type allows is taken, the last one is re-pointed instead (`ds_1` for most nodes, `ds_2` for a splitter, `ds_4` for storage). To use a different outlet, edit the `ds_N` numbers in the text.

Ctrl+f in the model editor for **find**, or Ctrl+h for **find-and-replace**

![](../assets/docs-using-ide/image_3.png)

**Navigate Back** and **Navigate Forward** allow you to move between locations you have visited in the model text. Keyboard shortcuts make this faster.

![](../assets/docs-using-ide/image_4.png)

**Table View** for node parameters that accept tabular values. Find this in the editor’s context menu, or by using the shortcut Ctrl+t.

![](../assets/docs-using-ide/image_5.png)

**Copy Location** by right-clicking your desired location on the map and using the context menu item.

![](../assets/docs-using-ide/image_6.png)

Use the **Navigate Back** and **Navigate Forward** buttons to flick back and forth between locations you have visited in the model file.

![](../assets/docs-using-ide/image_7.png)

**Undo/redo in plots**! Buttons in the Run Manager’s plotting toolbar allow you to undo and redo your plotting actions (data selection, zoom, plot type).

![](../assets/docs-using-ide/image_8.png)

**Shift + click + drag to zoom** into a specific part of the plot using the mouse. This rectangular lasso zoom is handy for focusing in specific events in the hydrograph.

![](../assets/docs-using-ide/image_9.png)

The **Parameter Sheet** shows node properties in a table. Filter by node type and name. Great for model review or bulk edits. Find it in the Tools menu.

![](../assets/docs-using-ide/image_10.png)

**Auto-complete** is accessible from the context menu, or the ctrl+space keyboard shortcut. Suggestions include data references and model result references. The list filters as you type and is context aware.

![](../assets/docs-using-ide/image_11.png)

**Cut/copy/paste** nodes in the schematic map using the context menu or keyboard shortcuts. Paste will paste nodes onto the schematic at the coordinates where you opened the context menu. These will appear in the text-based model representation immediately below the section where the cursor currently is. (If you want to paste below a specific node, click on that node to place the cursor there before pasting).

![](../assets/docs-using-ide/image_12.png)

Ctrl+f to **Find node** on map. This is also available from a button in the toolbar.

![](../assets/docs-using-ide/image_13.png)

**Linting** warnings and errors highlight issues with the model file before runtime

![](../assets/docs-using-ide/image_14.png)

**Undo and redo**, right out of the box ;)

![](../assets/docs-using-ide/image_15.png)

Right-click on a downstream link and use **Go to Node Definition** to navigate there with one click.

![](../assets/docs-using-ide/image_16.png)

If you’re inside a terminal and want to edit a model, you can **launch the IDE from the terminal** and pass in the name of the model file.

![](../assets/docs-using-ide/image_17.png)

Look for the **Plot Palettes** button in the plotting tool create custom colour palettes. And click the line sample in the key to change the colour and style of a given line.

![](../assets/docs-using-ide/image_18.png)![](../assets/docs-using-ide/92a32e80-6483-4366-ad2c-563768393bbd.png)

## Themes

Themes can be independently set for the Editor Syntax, Node Palate and the Application Window.

![](../assets/docs-using-ide/Screenshot_2025-10-10_at_12.16.40_pm.png)

![](../assets/docs-using-ide/Screenshot_2025-10-10_at_12.15.30_pm.png)

![](../assets/docs-using-ide/Screenshot_2025-10-10_at_12.18.17_pm.png)

![](../assets/docs-using-ide/Screenshot_2025-10-10_at_12.17.05_pm.png)

## Run Manager

It is a run manager. Yes.

![](../assets/docs-using-ide/image_19.png)

### Filtering the Timeseries tree

Type in the box above the **Timeseries** tree to show only the series you want. Each series is matched on its full name (such as `node.inflow_3.ds_1`) and on its source (such as `Run_2` or `flows.csv`). Case is ignored.

| You type | Shows |
|----------|-------|
| `inflow` | Every series whose name or source contains `inflow`. |
| `inflow_*.ds_1` | `ds_1` of every node named `inflow_…`. `*` matches anything, dots included; `?` matches one character. |
| `inflow ds_1` | `ds_1` of every inflow node. Spaces separate terms, and a series shows only if it matches all of them. |
| `*.inflow_* !dummy_*` | Every inflow node except the dummies. `!` excludes whatever a term matches. `!dummy_*` on its own shows everything else. |
| `"qu art"` or `qu\ art` | A name with a space in it. Quotes, or a backslash before the space, keep it one term. Wildcards still work inside quotes. |
| `/inflow_[34]\.ds_1$/` | A regular expression, written between slashes. It may contain spaces; write `\/` for a slash. |
| `Run_2` | Only Run_2's series, when several runs are selected. `Run_2 inflow` narrows that to its inflow series. |

A term with `*` or `?` must match whole parts of the name, between dots: `inflow_*.ds_1` matches `node.inflow_3.ds_1` but not `node.inflow_3.ds_10`. A term without them matches anywhere, as `ds_1` does in both.

If the filter doesn't make sense (a quote or regular expression left open, or an unclosed bracket in a regular expression), the box turns red and its tooltip says why. The tree keeps the last filter that worked until you fix it.

The filter only changes what the tree shows. Ticking a folder while filtering ticks just the series you can see, which makes it quick to plot every match. Series you ticked before filtering stay plotted even when the filter hides them.

## Docking

Fn+F9 reveals docking capabilities in the main window. Holding Fn+F9, look for the blue handles that appear in the top left of the schematic editor and text editors. This feature is in alpha. Good luck 😄.

## KalixIDE Preferences

Preferences are automatically saved in the preference file, which lives in the `./app/kalix_prefs.json` file relative to your KalixIDE executable. Some of these are configurable at File > Preferences, while others are simply settings remembered from your last session.
