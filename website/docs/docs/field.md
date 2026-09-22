---
title: "Field"
---

# Field

## At a glance…

The field node represents an irrigated field: a root-zone soil store that dries by
evapotranspiration, fills with rain and irrigation, and orders water upstream to meet its deficit.
The soil water balance is the FAO-56 daily root-zone depletion balance, with a crop coefficient and
a stress coefficient that reduces evapotranspiration as the soil dries.

A field is supplied by the node above it: a [storage](storage.md) outlet, or a supply outlet
(`ds_2` to `ds_4`) of a [regulated user](regulated-user.md#supply-outlets) or an
[unregulated user](unregulated-user.md#supply-outlets). What it does not take, and what runs off
the soil, drains down `ds_1`.

```ini
[node.paddock]
type = field
loc = 30, 40
area = 4.2
rain = data.climate_csv.by_name.rain
evap = data.climate_csv.by_name.et0
capacity = 120
kc = 0.6
p = 0.5
efficiency = 0.8
order = this.area * clamp(this.depletion - 40, 0, 120) / this.efficiency - this.orders_en_route
ds_1 = drain
```

!!! note "This is the first version of the field"
    It has one crop, always in the ground, over the whole field, with one soil store. Crops,
    planting, harvest, fallow, and changes to the cropped area are being designed; they will make
    `kc`, `p` and `area` per-crop and time-varying. A model written against this version will keep
    working, but expect these properties to move.

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Example: `[node.paddock]` |
| type (compulsory) | The node type, which is "field" in this case. `type = field` |
| loc (compulsory) | The location of the node in cartesian coordinates. Example: `loc = 30, 40` |
| area (compulsory) | The area of the field [km²]. 1 mm over 1 km² is 1 ML, so 4.2 km² is 420 ha. Readable in expressions as `this.area`. Example: `area = 4.2` |
| capacity (compulsory) | The water the root zone holds between full and empty [mm]: the total available water. Example: `capacity = 120` |
| rain (optional) | Rainfall on the field [mm]. Omitted, no rain falls. Example: `rain = data.climate_csv.by_name.rain` |
| evap (optional) | Reference evapotranspiration [mm], the reference the `kc` values were derived for (ET₀ for FAO-56 coefficients). Omitted, nothing evaporates. Example: `evap = data.climate_csv.by_name.et0` |
| kc (optional) | The crop coefficient: a constant, a table on `sim.day_of_year` for a seasonal curve, or any expression. Omitted, 0. Example: `kc = table.cotton_kc(sim.day_of_year)` |
| p (optional) | The depletion fraction: the share of `capacity` the crop can use before stress begins. Default 0.5. Example: `p = 0.65` |
| efficiency (optional) | The share of the water supplied that reaches the soil. The rest is `escape` (delivery loss, tailwater the field does not keep) and leaves the model here. Readable as `this.efficiency`. Default 1. Example: `efficiency = 0.8` |
| initial\_depletion (optional) | The depletion at the start of the run [mm]. Default 0, a full profile. Example: `initial_depletion = 20` |
| order (optional) | The irrigation rule: the order the field places upstream each step [ML]. An expression; see [The irrigation rule](#the-irrigation-rule). Omitted, the field never orders: it is rain-fed. |
| ds\_1 (optional) | Name of the downstream node. `bypass` and `excess` drain down it. Example: `ds_1 = drain` |

## Results associated with this node

| Result | Description |
| --- | --- |
| depletion | How far the root zone is below field capacity at the start of the step [mm]: 0 is full, `capacity` is empty. Recorded before the irrigation rule is evaluated, so `this.depletion` in `order` is this value |
| orders\_en\_route | Water ordered and not yet arrived [ML], where there is travel time from the supply. Recorded with `depletion` |
| order | The order placed this step [ML] |
| order\_due | The order placed earlier that is due to arrive this step [ML] |
| usflow | Upstream flow: the water that arrives at the field [ML] |
| ks | The stress coefficient this step, 0 to 1 |
| kc | The value of the `kc` expression this step |
| et | Evapotranspiration [ML] |
| rain | Rain on the field [ML] |
| excess | Rain the soil could not hold [ML], drained down `ds_1` |
| supply | The water the field takes from what arrives [ML] |
| escape | The share of `supply` that does not reach the soil [ML], which leaves the model here |
| bypass | The water that arrives and is not taken [ML], passed down `ds_1` |
| dsflow | Downstream flow [ML]: `bypass + excess` |
| ds\_1 | Downstream flow on link ds\_1 [ML], the same |
| area | The declared `area` (a static property) |
| efficiency | The declared `efficiency` (a static property) |

## How the node works

The field works in mm over its area: 1 mm × 1 km² = 1 ML. Each step, in this order:

1. **Stress.** From the depletion `D` at the start of the step:
   `ks = clamp((capacity − D) / ((1 − p) × capacity), 0, 1)`. The crop transpires freely while it
   has used less than `p` of the capacity, and less and less as the soil dries beyond that
   (FAO-56, equation 84).
2. **Evapotranspiration.** `et = ks × kc × evap`, no more than the water the soil holds.
3. **Rain** goes on the soil. What would take the depletion below zero leaves as `excess`.
4. **Irrigation.** The field takes from what arrives no more than the soil has room for after the
   rain, allowing for the share that escapes: `supply = min(usflow, room / efficiency)`, of which
   `escape = supply × (1 − efficiency)` and the rest infiltrates. What it does not take is
   `bypass`. So irrigation never overfills the soil, whatever was ordered.

Rain goes on before irrigation so that a day's rain reduces what the field takes, rather than
running off a profile that irrigation has just filled. Effective rainfall is `rain − excess`.

**The balance closes every step, to machine precision:**

`rain + (supply − escape) = et + excess + Δ(water held)`, with `usflow = supply + bypass` and
`ds_1 = bypass + excess`. Every term is a result, so the balance can be replayed line by line.

#### The irrigation rule

The field owns the physics. When to irrigate, and how much, is the farmer's decision, and it is
written in the `order` expression, in ML. The field publishes what the decision needs, and these
read cleanly in the same step with no offsets:

- `this.depletion`, the soil at the start of the step [mm];
- `this.area` [km²] and `this.efficiency`;
- `this.orders_en_route`, what has been ordered and has not yet arrived [ML].

The rule the IDE template carries tops the soil up to a target depletion of 40 mm, at most 120 mm
in a day, grossed up for escape, less what is already on its way:

```ini
order = this.area * clamp(this.depletion - 40, 0, 120) / this.efficiency - this.orders_en_route
```

`order` means what it means everywhere in Kalix, the order placed on the network. Nothing is
transformed behind the modeller's back: the allowance for escape is in the line. Other rules are
one line each. A refill trigger, irrigating to a target once the depletion passes a threshold:

```ini
order = if(this.depletion >= 60, this.area * (this.depletion - 20) / this.efficiency, 0)
```

Stopping irrigation once the soil is past the point of saving the crop:

```ini
order = if(this.depletion >= 100, 0, this.area * clamp(this.depletion - 40, 0, 120) / this.efficiency - this.orders_en_route)
```

`this.orders_en_route` matters where there is travel time between the supply and the field.
Without it, a rule that orders the deficit places the same order every day until the first
delivery lands.

#### Ordering

A field orders like a regulated user: its order travels upstream to the supply, and the field
acts on it after its travel time (see [Ordering](ordering.md)). A field outside any regulated zone
never has an order fall due, so it is rain-fed whatever its `order` says.

The links leaving a field are not regulated. A field's `ds_1` carries bypass and excess back to
the river: it is a drain, not a delivery path. No order travels up it, and the travel time to the
field is no part of the travel time to anything below it.

#### Mass balance

Only what the field keeps and loses leaves the model at the field: the water the soil holds,
evapotranspiration, and escape. `bypass` and `excess` are still in the model, on `ds_1`.

## References

Allen, R.G., Pereira, L.S., Raes, D. and Smith, M. (1998). *Crop evapotranspiration — Guidelines for
computing crop water requirements.* FAO Irrigation and Drainage Paper 56. Chapter 8, the root-zone
water balance and the stress coefficient Ks.
