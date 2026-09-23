---
title: "Regulated_User"
---

# Regulated_User

## At a glance…

The regulated\_user node represents a water user with the ability to place orders. The user orders in anticipation of demands, and subsequently diverts water from the network to satisfy those demands. If the demand cannot be fully satisfied, this is called a shortfall.

```ini
[node.urban_user]
type = regulated_user
loc = 20, 30
order = data.extendeddataset.by_name.urban_demand
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.urban_user]` |
| type (compulsory) | The node type, which is “user” in this case. `type = user` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| order (optional) | This property specifies how much the regulated user will order [ML] each timestep. In regulated systems with nonzero travel times, the demand will be lagged to give enough time for the water to arrive.  Example: `order = data.extendeddataset.by_name.urban_demand` |
| pump (optional) | Use this to limit the amount of water the user can extract each timestep. Example: `pump = 86.4` |
| opportunistic\_demand (optional) | Demand for water above the arriving order, evaluated at flow time — for access that is announced on conditions rather than ordered ahead, such as off-allocation. The opportunistic take is supplied from whatever availability the regulated delivery leaves behind, shares the pump limit, and is debited to the same accounts. Example: `opportunistic_demand = if(fn.oa_open(), 309.5, 0)` |
| accounts (optional) | Names of the [accounts](accounts.md) this user draws on, comma-separated in order of use. Orders are capped by the accounts' combined balance and deliveries are debited from them, so an [allocation system](allocation-systems.md) can constrain the user. Example: `accounts = smith_carryover, smith_annual` |
| order\_accounts (optional) | Names of order-authorisation accounts (debit-on-order), comma-separated in order of use. They extend the order cap beyond the regular `accounts` balance, and the excess portion of each approved order is debited from them immediately at order time. They are invisible to the flow phase. See "Order accounts" below. Example: `order_accounts = wy_bridge` |
| order\_factor (optional) | Factor applied to this node's order as it is sent upstream. The network sees `order_factor × order`, while `order`, `order_due` and the delivery are unchanged. A number; default 1. See [Order factor](#order-factor). Example: `order_factor = 1.1` |
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
| ds\_2\_order (and 3, 4) | The order arriving on the supply outlet today, as it arrived [ML] |
| ds\_2\_order\_due (and 3, 4) | The order accepted from the supply outlet earlier, which is due to be delivered today [ML] |
| order | The order placed today [ML] |
| order\_due | The order previously placed, which is due to be delivered today [ML] |
| demand | Demand at this node [ML] |
| diversion | Diverted volume [ML] — the sum of the regulated and opportunistic takes. This is the whole metered take, including what is sent down supply outlets |
| diversion\_regulated | The part of the diversion that delivers the arriving order [ML] |
| diversion\_opportunistic | The part of the diversion taken under opportunistic\_demand [ML] |
| opportunistic\_demand | Today's opportunistic\_demand value [ML] (zero when the property is not set) |
| pump | Pump capacity value [ML] which may vary due to functions |
| order\_factor | The declared `order_factor` (a static property; 1 when not set) |

## How the node works

Demands and diversions must be positive. The user node extract flows to meet the demand as specified in the user node.

#### Pump capacity

Flow available for diversion is limited by the specified pump capacity.

`available=min(usflow,pump capacity)`

#### Diversion

`diversion=min(usflow,demand)`

`dsflow=usflow−diversion`

#### Opportunistic demand

`opportunistic_demand` lets the user extract water it did not order, when its
expression says access is open — the pattern behind off-allocation and other
announced surplus-access schemes. It is evaluated during the flow phase (order
time is too early: the announcement typically depends on today's flows), so the
expression can be gated on flow conditions, e.g.
`if(fn.oa_open(), 309.5, 0)`.

The arriving order has first claim on availability; the opportunistic take is
supplied from what remains:

`diversion_opportunistic = min(opportunistic_demand, available − diversion_regulated)`

Both takes share the pump limit, and both are debited to the node's accounts —
with the regulated delivery drawing on the balance first. The two parts are
recorded separately (`diversion_regulated`, `diversion_opportunistic`) so a
resource assessment can count regulated usage only.

#### Order factor

`order_factor` scales the order the node sends up the network without changing
the order it places. With `order_factor = 1.1`, the upstream node receives
`downstream orders + 1.1 × order`; the node's own `order` and `order_due`
outputs, the account cap and debits, and the delivery at flow time all use the
order as placed, so the extra water ordered passes downstream. Use it for a
deliberate margin — transmission losses the loss tables do not describe, or an
operating rule that orders ahead of demand. Orders arriving from downstream
pass through unscaled.

#### Accounts

If the node lists `accounts`, its order is capped at order time by the combined
account balance, and each delivery is debited from the accounts in the order
listed (the first is drawn down before the second). This is how an
[allocation system](allocation-systems.md) limits the user: a low announced
allocation means a low balance, which caps ordering. See [`[acc.*]`](accounts.md).

#### Order accounts

`order_accounts` lists additional accounts that authorise ordering without
supplying water — debit-on-order semantics. The contract:

- The order cap becomes `min(order, Σaccounts + Σorder_accounts)`.
- Only the **excess** of the approved order over the regular `accounts`
  balance is debited from the `order_accounts`, walked in list order, at
  order time. Orders within the regular balance never touch them.
- They are **invisible to the flow phase**: they never extend the delivery
  cap, are never debited by takes, and are not refunded when an
  authorised order goes undelivered.

Two intended uses. First, an *order bridge*: at an accounting boundary
(e.g. the last days of a water year) orders for delivery after the reset
are otherwise capped by the dying period's drained balance. Crediting a
small shared bridge account just before the boundary — and `set_empty`-ing
it at the reset, before deliveries arrive — lets users keep ordering
against the balance they are about to receive, without ever taking water
against it. The bridge is self-limiting: it cannot authorise more total
ordering than its size. Note a shared pool is drawn in node execution
order (order phase runs downstream-to-upstream), so size it for the sum of
its users' needs.

Second, a *pure order-debit scheme*: with `accounts` empty and only
`order_accounts` listed, the user is debited when it orders and never at
take — the "you ordered it, you own it" accounting some supply schemes
use.

#### Supply outlets

A regulated user can supply water to other nodes through its supply outlets, `ds_2`, `ds_3` and `ds_4`. The usual case is a [field](field.md): the field does not pump from the river itself, it is supplied by the user, through the user's pump and accounts.

```ini
[node.farm_pump]
type = regulated_user
loc = 20, 30
order = 0
pump = 86.4
accounts = farm_licence
ds_1 = river_below
ds_2 = paddock

[node.paddock]
type = field
loc = 30, 40
order = 5
ds_1 = river_below
```

The node on a supply outlet places its orders with the user, and they become the user's order:

- The orders arriving on the supply outlets are added to the user's own `order`. The total is capped by the user's accounts, where it has them, and scaled by `order_factor`, and that is the order the network sees: `order_factor × (order + ds_2_order + ds_3_order + ds_4_order)`. The `order` result stays the user's own order.
- The user holds each accepted order for its own travel time, as it does its own (`ds_2_order_due`). When the ordered water reaches the user it diverts it and sends it down the outlet. If there is routing between the user and the node below, that node's travel time is longer than the user's by that much, and the water arrives there on the step its order falls due.
- The links below a supply outlet are part of the same regulated zone as the user, with travel time counted from the same supply.

When the user cannot divert everything that is due — the flow is short, or the pump capacity or the account balance limits the take — the supply outlets are served first, `ds_2` then `ds_3` then `ds_4`, and the user's own order takes what is left. The same order applies when the accounts cap the order as it is placed. `opportunistic_demand` is the user's own, and is served last.

Everything that limits or meters the take applies to the whole diversion, the supply outlets' share included: `pump`, the accounts' balance, and the debit to the accounts. It is the user's water. Water that the node below returns to the river is not credited back.

For the mass balance, only what the user keeps for itself leaves the model at the user; the water sent down a supply outlet is still in the model, on that link. What the user keeps is `diversion − ds_2 − ds_3 − ds_4`, and `usflow = ds_1 + diversion`.

## References

None.
