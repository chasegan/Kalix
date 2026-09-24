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

### Aggregate series

An **aggregate** is the point-by-point sum of series from one source: a run, the **Last run**, or a loaded dataset. Use one to total the flows through several nodes, or the demands of a group of users.

**Creating an aggregate.** In the **Timeseries** tree, either select the series to sum and right-click **New aggregate from selected…**, or tick them and right-click **New aggregate from checked…**. Then give it a name (a suggestion such as `sum_1` is filled in). Each appears once there are at least two series to sum. Ticking suits "all but a few": tick everything, then untick the ones to leave out.

- Selecting (or ticking) a folder such as `node.mygr4j` sums every series under it, as the tree shows it. Use the filter to narrow what is summed, for example to every `ds_1`.
- When the selected series come from several sources (for example `Run_1` and `Run_2`), Kalix makes one aggregate per source, each summing that source's series. Every source must include every selected series.
- An aggregate can be summed into another aggregate of the same source.
- The series must have identical timestamps. A point missing in any input is missing in the sum.

Aggregates appear under **Aggregate series** in the Run Manager's source tree, grouped by source, and as `aggregate.<name>` in the **Timeseries** tree, where they plot like any other series. Hover over an aggregate to see what it sums.

**Aggregates of Last run** are recomputed whenever a new run completes. If the new run can't supply an input (a series is missing, say), the aggregate is cleared and its row in the statistics view says why; it comes back when a later run can.

**Removing a source keeps its aggregates.** You can load a dataset, make an aggregate, then remove the dataset and keep only the aggregate for comparison. Its group is then labelled, for example, `flows.csv (removed)`.

**Managing aggregates.** Right-click an aggregate in the source tree to **Show component series** (a window listing its inputs, one per line, ready to copy), or to **Save…**, **Rename…** or **Delete** it. Right-click a group to save all of its aggregates, or choose **Save aggregates…** in the **Timeseries** tree to save the selected ones together. Aggregates save as CSV, zipped CSV or Pixie, one column per aggregate.

Aggregates last for the session; save them to keep them. A dataset with a column named exactly like an existing aggregate (`aggregate.<name>`) can't be loaded until one of the two is renamed or removed.

## Docking

Fn+F9 reveals docking capabilities in the main window. Holding Fn+F9, look for the blue handles that appear in the top left of the schematic editor and text editors. This feature is in alpha. Good luck 😄.

## KalixIDE Preferences

Preferences are automatically saved in the preference file, which lives in the `./app/kalix_prefs.json` file relative to your KalixIDE executable. Some of these are configurable at File > Preferences, while others are simply settings remembered from your last session.
