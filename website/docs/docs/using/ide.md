---
title: "Kalix IDE"
---

# Kalix IDE

![](../../assets/docs-using-ide/Screenshot_2025-10-10_at_12.16.40_pm.png)

## Interactions

**Rename node** from the schematic map context menu

![](../../assets/docs-using-ide/image.png)

Model editor context menu item **Show on Map** will select and centre the node you’re working on in the schematic map

![](../../assets/docs-using-ide/image_1.png)

Click and drag to select nodes on the schematic map. Then drag to move or ctrl+drag to rotate.

![](../../assets/docs-using-ide/image_2.png)

Ctrl+f in the model editor for **find**, or Ctrl+h for **find-and-replace**

![](../../assets/docs-using-ide/image_3.png)

**Navigate Back** and **Navigate Forward** allow you to move between locations you have visited in the model text. Keyboard shortcuts make this faster.

![](../../assets/docs-using-ide/image_4.png)

**Table View** for node parameters that accept tabular values. Find this in the editor’s context menu, or by using the shortcut Ctrl+t.

![](../../assets/docs-using-ide/image_5.png)

**Copy Location** by right-clicking your desired location on the map and using the context menu item.

![](../../assets/docs-using-ide/image_6.png)

Use the **Navigate Back** and **Navigate Forward** buttons to flick back and forth between locations you have visited in the model file.

![](../../assets/docs-using-ide/image_7.png)

**Undo/redo in plots**! Buttons in the Run Manager’s plotting toolbar allow you to undo and redo your plotting actions (data selection, zoom, plot type).

![](../../assets/docs-using-ide/image_8.png)

**Shift + click + drag to zoom** into a specific part of the plot using the mouse. This rectangular lasso zoom is handy for focusing in specific events in the hydrograph.

![](../../assets/docs-using-ide/image_9.png)

The **Parameter Sheet** shows node properties in a table. Filter by node type and name. Great for model review or bulk edits. Find it in the Tools menu.

![](../../assets/docs-using-ide/image_10.png)

**Auto-complete** is accessible from the context menu, or the ctrl+space keyboard shortcut. Suggestions include data references and model result references. The list filters as you type and is context aware.

![](../../assets/docs-using-ide/image_11.png)

**Cut/copy/paste** nodes in the schematic map using the context menu or keyboard shortcuts. Paste will paste nodes onto the schematic at the coordinates where you opened the context menu. These will appear in the text-based model representation immediately below the section where the cursor currently is. (If you want to paste below a specific node, click on that node to place the cursor there before pasting).

![](../../assets/docs-using-ide/image_12.png)

Ctrl+f to **Find node** on map. This is also available from a button in the toolbar.

![](../../assets/docs-using-ide/image_13.png)

**Linting** warnings and errors highlight issues with the model file before runtime

![](../../assets/docs-using-ide/image_14.png)

**Undo and redo**, right out of the box ;)

![](../../assets/docs-using-ide/image_15.png)

Right-click on a downstream link and use **Go to Node Definition** to navigate there with one click.

![](../../assets/docs-using-ide/image_16.png)

If you’re inside a terminal and want to edit a model, you can **launch the IDE from the terminal** and pass in the name of the model file.

![](../../assets/docs-using-ide/image_17.png)

Look for the **Plot Palettes** button in the plotting tool create custom colour palettes. And click the line sample in the key to change the colour and style of a given line.

![](../../assets/docs-using-ide/image_18.png)![](../../assets/docs-using-ide/92a32e80-6483-4366-ad2c-563768393bbd.png)

## Themes

Themes can be independently set for the Editor Syntax, Node Palate and the Application Window.

![](../../assets/docs-using-ide/Screenshot_2025-10-10_at_12.16.40_pm.png)

![](../../assets/docs-using-ide/Screenshot_2025-10-10_at_12.15.30_pm.png)

![](../../assets/docs-using-ide/Screenshot_2025-10-10_at_12.18.17_pm.png)

![](../../assets/docs-using-ide/Screenshot_2025-10-10_at_12.17.05_pm.png)

## Run Manager

It is a run manager. Yes.

![](../../assets/docs-using-ide/image_19.png)

## Docking

Fn+F9 reveals docking capabilities in the main window. Holding Fn+F9, look for the blue handles that appear in the top left of the schematic editor and text editors. This feature is in alpha. Good luck 😄.

## KalixIDE Preferences

Preferences are automatically saved in the preference file, which lives in the `./app/kalix_prefs.json` file relative to your KalixIDE executable. Some of these are configurable at File > Preferences, while others are simply settings remembered from your last session.
