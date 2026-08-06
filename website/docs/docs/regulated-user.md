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
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| order | The order placed today [ML] |
| order\_due | The order previously placed, which is due to be delivered today [ML] |
| demand | Demand at this node [ML] |
| diversion | Diverted volume [ML] — the sum of the regulated and opportunistic takes |
| diversion\_regulated | The part of the diversion that delivers the arriving order [ML] |
| diversion\_opportunistic | The part of the diversion taken under opportunistic\_demand [ML] |
| opportunistic\_demand | Today's opportunistic\_demand value [ML] (zero when the property is not set) |
| pump | Pump capacity value [ML] which may vary due to functions |

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

## References

None.
