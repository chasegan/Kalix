# Node References in Kalix

## Overview

During simulation, each node produces output variables that are stored in the data cache. These outputs can be referenced by other nodes using the `node.*` namespace, enabling you to build connected water resource networks.

## Reference Syntax

Node outputs follow this pattern:

```
node.<node_name>.<output_variable>
```

**Example:** A node named `catchment` with output `dsflow`:
```
node.catchment.dsflow
```

## Common Output Variables

Most nodes produce a subset of these outputs:

| Variable | Description |
|----------|-------------|
| `usflow` | Upstream flow (flow entering the node) |
| `dsflow` | Downstream flow (flow leaving the node) |
| `ds_1` | Downstream flow on link 1 (alias for dsflow) |
| `inflow` | Inflow to the node |
| `volume` | Storage volume |
| `level` | Water level |
| `area` | Surface area |
| `demand` | Water demand |
| `loss` | Losses (e.g., seepage, evaporation) |

### Rainfall-Runoff Nodes (GR4J, Sacramento)

| Variable | Description |
|----------|-------------|
| `runoff_depth` | Runoff depth (mm) |
| `runoff_volume` | Runoff volume (ML) |
| `rain` | Precipitation input (mm) |
| `evap` | Evapotranspiration (mm) |

## Using Node References

Reference another node's output in any dynamic expression:

```ini
[node.catchment]
type = gr4j
precipitation = data.climate_csv.by_name.rainfall
evapotranspiration = data.climate_csv.by_name.evap

[node.reservoir]
type = storage
inflow = node.catchment.dsflow

[node.diversion]
type = loss
inflow = node.reservoir.dsflow
loss = 0.1 * node.reservoir.dsflow
```

## Temporal Offset for Node Outputs

You can access previous timestep values using offset syntax:

```
node.<name>.<output>[offset, default_value]
```

**Important:** Only **negative offsets** (past values) are supported for node references. Forward lookups are not allowed because future values haven't been computed yet.

**Examples:**
```ini
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

## Outputs in the [outputs] Section

To export node results to CSV, list them in the `[outputs]` section:

```ini
[outputs]
node.catchment.dsflow
node.catchment.runoff_depth
node.reservoir.volume
node.reservoir.level
```
