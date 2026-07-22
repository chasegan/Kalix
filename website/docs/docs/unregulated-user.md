---
title: "Unregulated_User"
---

# Unregulated_User

## At a glance…

The unregulated\_user node represents a water user whose access is opportunistic. The user diverts water subject to licence conditions to satisfy their demands. If the demand cannot be fully satisfied, this is called a shortfall.

```ini
[node.water_harvester]
type = unregulated_user
loc = 20, 30
pump = 20
demand = data.extendeddataset.by_name.unrestricted_demand
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.urban_user]` |
| type (compulsory) | The node type, which is “unregulated\_user” in this case. `type = user` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| demand (optional) | Demand data [ML].  Example: `demand = data.extendeddataset.by_name.urban_demand` |
| pump (optional) | Use this to limit the amount of water the user can extract each timestep. Example: `pump = 86.4` |
| flow\_threshold (optional) | Use this to leave a certain amount of flow in the river (not extract it). This can be used to set flow conditions as may be associated with unregulated licence conditions. Example: `flow_threshold = 100.0` |
| annual\_cap (optional) | Use this to set an annual diversion limit. Specify the cap volume (ML), and the month in which the annual cap resets.  Example:  `annual_cap = 2250, 7` |
| demand\_carryover (optional) | Turn on/off demand carryover. This behaviour is off be default. Set this to “true” if you want the user to carryover unmet demands to the following timestep.  Example:  `demand_carryover = true` And if you want the carryover to reset each year, then also specify the month when that should happen. Example:  `demand_carryover = true, 7` |
| accounts (optional) | Names of the [accounts](accounts.md) this user draws on, comma-separated in order of use. Availability is capped by the accounts' combined balance and diversions are debited from them in order, so an [allocation system](allocation-systems.md) can constrain the user. Example: `accounts = harvest_licence` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| order | Zero (0) for unregulated users |
| order\_due | Zero (0) for unregulated users |
| demand | Demand at this node [ML] |
| diversion | Diverted volume [ML] |
| pump | Pump capacity value [ML] which may vary due to functions |
| flow\_threshold | Flow threshold value [ML] which may vary due to functions |
| demand\_carryover | Total demand carried over to the next timestep [ML]. |

## How the node works

Demands and diversions must be positive. The user node extract flows to meet the demand as specified in the user node.

#### Pump capacity

Flow available for diversion is limited by the specified pump capacity.

`available=min(usflow,pump capacity)`

#### Flow threshold

`available=max(usflow−threshold,0)`

#### Annual cap

Limits diversions on an annual basis. Diversion accounting starts on the first timestep of the specified month.

#### Demand carryover

If demand carryover is allowed, then unmet demands will be carried forward with the hope of satisfying them in the next timestep. If a reset month is been specified, the carryover will be reset to 0 at the start of the first timestep on that calendar month each year.

#### Accounts

If the node lists `accounts`, availability is further capped by the combined
account balance, and each diversion is debited from the accounts in the order
listed. This lets an [allocation system](allocation-systems.md) govern
opportunistic take — for example an account credited only on flow events. See
[`[acc.*]`](accounts.md).

#### Diversion

`diversion=min(usflow,demand)`

`dsflow=usflow−diversion`

## References

None.
