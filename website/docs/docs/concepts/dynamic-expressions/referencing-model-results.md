---
title: "Referencing Model Results"
---

# Referencing Model Results

## Overview

Model results (reflecting the system state) can be referenced within dynamic expressions in other parts of the model using the `node.*` namespace.

## Reference Syntax

Node outputs follow this pattern:

```
node.<node_name>.<output_variable>
```

**Example:** A node named `catchment` with output `dsflow`:

```
node.catchment.dsflow
```

## ‘This’ Syntax

When referring to outputs of the current node, the ‘this’ syntax offers can be more convenient than giving the fully qualified name every time:

**Example:** An unregulated user may have a pump-capacity which is a function of its own upstream flow, `this.uflow`:

```
pump = if(this.usflow > 100, 10, 0)
```

## Common Output Variables

All node outputs are accessible in this way. Some common ones are:

| Variable | Description |
| --- | --- |
| `usflow` | Upstream flow (flow entering the node) |
| `dsflow` | Downstream flow (flow leaving the node) |
| `ds_1` | Downstream flow on link 1 (alias for dsflow) |
| `inflow` | Inflow to the node |
| `volume` | Storage volume |
| `loss` | Losses (e.g., seepage, evaporation) |

## Using Node References

Reference another node’s output in any dynamic expression:

```
[node.catchment]
loc = 0, 0
type = gr4j
rain = data.climate_csv.by_name.rainfall
evap = data.climate_csv.by_name.evap

[node.other_catchment]
loc = 0, 10
type = inflow
inflow = node.catchment.dsflow

[node.user]
loc = 0, 20
type = user
demand = 0.1 * node.other_catchment.inflow
```

## Temporal Offset for Node Outputs

You can access previous timestep values using offset syntax:

```
node.<name>.<output>[offset, default_value]
```

**Important:** Only **negative offsets** (past values) are supported for node references. Forward lookups are not allowed because future values haven’t been computed yet.

**Examples:**

```
; Yesterday's downstream flow (default to 0 if at simulation start)
node.catchment.dsflow[-1, 0.0]

; Storage volume from 7 days ago
node.reservoir.volume[-7, 1000.0]

; Calculate daily change in storage
node.reservoir.volume - node.reservoir.volume[-1, 0.0]

; Conditional based on previous flow
if(node.catchment.dsflow > node.catchment.dsflow[-1, 0.0], 1, 0)
```

## Name Handling

Node names follow the same sanitisation rules as data references:

- Names are **case-insensitive**

- Special characters are replaced with underscores

- References are converted to lowercase internally

So `node.My_Catchment.dsflow` and `node.my_catchment.dsflow` are equivalent.
