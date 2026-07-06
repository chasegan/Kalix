---
title: Inverted Pyramid Storage Tables
---

# Inverted Pyramid Storage Tables

Known volume and surface area values (or alternatively the volume and depth) are enough to fully-specify the dimensions of an inverted pyramid. From these it is possible to derive a piecewise-linear storage table that approximates such a shape. This is available in the editor’s right-click menu in KalixGUI.

If `hmax` and `Vmax` are known:

`Amax=hmax3Vmax`

If `Amax` and `Vmax` are known:

`hmax=Amax3Vmax`

If `Amax` and `hmax` are known:

`Vmax=3Amaxhmax`

When using a table to represent an inverted-pyramid storage:

- Linear interpolation between levels will result in small errors compared to an exact pyramid. This is because height, area, and volume are not proportional to each other for such a shape (i.e. width is proportional to height, and area goes as height^2, and volume goes as height^3).

- Therefore we need to define a number of table rows, *n*, that has enough resolution to keep the interpolation error acceptably low. It turns out that if we build a table with `n=5` heights `h` evenly spaced between min and max height, our relative error in area is not more than ~10 (except at the very bottom of the table).

- `Across-sectional=(hmaxh)2Amax`

- \*Height refers to height above the min height (if it is not already = 0).
