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
| ds\_1 (optional) | Name of the downstream node on the river. This property defines a downstream link: what the user does not divert flows down it.  Example: `ds_1 = my_other_node` |
| ds\_2, ds\_3, ds\_4 (optional) | Supply outlets. The node on a supply outlet places its orders with this user, and the user diverts that water and sends it down the outlet. See [Supply outlets](#supply-outlets). Example: `ds_2 = my_field` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML], the total down all outlets |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| ds\_2, ds\_3, ds\_4 | Flow sent down the supply outlet [ML] |
| ds\_2\_order (and 3, 4) | The order arriving on the supply outlet today [ML] |
| order | Zero (0) for unregulated users |
| order\_due | Zero (0) for unregulated users |
| demand | Demand at this node [ML] |
| diversion | Diverted volume [ML]. This is the whole metered take, including what is sent down supply outlets |
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

#### Supply outlets

An unregulated user can supply water to other nodes through its supply outlets, `ds_2`, `ds_3` and `ds_4`. The usual case is a `field` node: the field does not pump from the river itself, it is supplied by the user, through the user's pump, its flow threshold, its annual cap and its accounts.

```ini
[node.farm_pump]
type = unregulated_user
loc = 20, 30
demand = 0
pump = 86.4
flow_threshold = 200
ds_1 = river_below
ds_2 = paddock

[node.paddock]
type = field
loc = 30, 40
order = 5
ds_1 = river_below
```

The node on a supply outlet places its orders with the user. The user is the supply at the top of that node's regulated zone, in the way a storage is for the reach below it:

- The orders arriving on the supply outlets are added to the user's demand on the step they arrive, and the user diverts them and sends them down the outlets on that same step. The `demand` result stays the user's own demand.
- Travel time for the nodes on a supply outlet is counted from the user. With routing between the user and the node below, that node orders ahead by that travel time, and the water the user diverts today reaches it as the order falls due. This holds wherever the user sits, including inside a storage's regulated zone: the supply outlet starts a new zone, while `ds_1` carries on the river's.
- An unregulated user places no orders upstream, and that includes these. It takes what the river is carrying, within its limits. If the river cannot supply an order, the order is not met.

When the user cannot divert everything that is wanted, the supply outlets are served first, `ds_2` then `ds_3` then `ds_4`, and the user's own demand takes what is left.

Everything that limits or meters the take applies to the whole diversion, the supply outlets' share included: `flow_threshold`, `pump`, `annual_cap`, the accounts' balance, and the debit to the accounts. It is the user's water. Water that the node below returns to the river is not credited back.

Orders on the supply outlets take no part in `demand_carryover`, which carries over the user's own unmet demand alone. A node that orders to meet a deficit, as a field does, orders that deficit again the next day; carrying it over as well would deliver it twice.

For the mass balance, only what the user keeps for itself leaves the model at the user; the water sent down a supply outlet is still in the model, on that link. What the user keeps is `diversion − ds_2 − ds_3 − ds_4`, and `usflow = ds_1 + diversion`.

## References

None.
