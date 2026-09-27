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
order = this.area * clamp(this.depletion[-1, 0] - 40, 0, 120) / this.efficiency - this.orders_en_route[-1, 0]
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
| interception (optional) | Rain reaches the soil only beyond this fraction of the day's `evap`; the rest wets the canopy and evaporates (`intercepted`). Default 0.2, FAO-56's interception loss. Write `0` to take rain as given. Example: `interception = 0.2` |
| return\_fraction (optional) | The share of the field's runoff (`excess`) that survives the farm's drains and goes on down `ds_1` as `return_flow`; the rest is lost from the model at the field. Default 1, nothing lost. Set to 0 when runoff is caught and returned to the farm storage instead; see [Returning runoff to the farm storage](#returning-runoff-to-the-farm-storage). Example: `return_fraction = 0.8` |
| initial\_depletion (optional) | The depletion at the start of the run [mm]. Default 0, a full profile. Example: `initial_depletion = 20` |
| order (optional) | The irrigation rule: the order the field places upstream each step [ML]. An expression; see [The irrigation rule](#the-irrigation-rule). Omitted, the field never orders: it is rain-fed. |
| ds\_1 (optional) | Name of the downstream node. `bypass` and `excess` drain down it. Example: `ds_1 = drain` |

## Results associated with this node

| Result | Description |
| --- | --- |
| depletion | How far the root zone is below field capacity at the end of the step [mm]: 0 is full, `capacity` is empty. A state, reported at the end of the step like a storage's `volume`; the irrigation rule reads the previous step's value, `this.depletion[-1, 0]`, which is the soil at the start of today |
| orders\_en\_route | Water on its way at the end of the step [ML]: ordered, today's order included, and not yet arrived. Zero without travel time from the supply. A state, like `depletion`; the irrigation rule reads `this.orders_en_route[-1, 0]` |
| order | The order placed this step [ML] |
| order\_due | The order placed earlier that is due to arrive this step [ML] |
| usflow | Upstream flow: the water that arrives at the field [ML] |
| ks | The stress coefficient this step, 0 to 1 |
| kc | The value of the `kc` expression this step |
| et | Evapotranspiration [mm]: `ks × kc × evap`, no more than the water held |
| et\_vol | Evapotranspiration [ML]: `et × area` |
| rain | The value of the `rain` expression [mm] |
| rain\_vol | Rain on the field [ML]: `rain × area` |
| intercepted | Rain that did not reach the soil [mm]: `min(rain, interception × evap)` |
| evap | The value of the `evap` expression [mm] |
| excess | Rain the soil could not hold [ML], drained down `ds_1` |
| supply | The water the field takes from what arrives [ML] |
| escape | The share of `supply` that does not reach the soil [ML], which leaves the model here |
| bypass | The water that arrives and is not taken [ML], passed down `ds_1` whole: the irrigator would have refused it at the pump |
| return\_flow | The runoff that goes on down `ds_1` [ML]: `return_fraction × excess`. The rest of the excess is lost |
| dsflow | Downstream flow [ML]: `bypass + return_flow` |
| ds\_1 | Downstream flow on link ds\_1 [ML], the same |
| area | The declared `area` (a static property) |
| efficiency | The declared `efficiency` (a static property) |

## The crop model, from the top

Think of the soil under a crop as a bucket. Each day, rain and irrigation put water in, the crop
takes water out, and the field keeps track of how far below full the bucket is. Everything else
is deciding when to top it up. Depths are in mm over the cropped area; P is rain and E₀ is
reference evapotranspiration.

1. **Rain that counts.** Some rain never reaches the soil: it wets leaves and evaporates.
   Effective rain is P_e = max(0, P − 0.2·E₀) (FAO-56); the 0.2 is `interception`, and
   P − P_e is `intercepted`.
2. **The bucket.** The root zone is a column of soil of depth Z_r. Between full (field capacity)
   and empty (wilting point) it holds a depth of water TAW = θ_cap · Z_r, where θ_cap is the
   soil's water-holding capacity as a fraction (0.1–0.3 is typical). In Kalix this is one
   number, `capacity`.
3. **The state: depletion.** D is how far the bucket is below full, in mm. D = 0 is full and
   D = `capacity` is empty. This is the field's `depletion`.
4. **What the crop wants.** A well-watered crop uses E_c = K_c · E₀, where the crop coefficient
   K_c depends on the crop and how far through its growth it is (`kc`).
5. **What it gets when the bucket is low.** A crop drinks freely until it has used a fraction
   `p` of the capacity, then less and less:
   K_s = 1 if D ≤ p·TAW, otherwise (TAW − D) / ((1 − p)·TAW), and E = K_s · E_c.
   K_s is computed from D at the start of the day (the field's `ks`). A crop whose K_s stays
   near zero is dead: its area goes back to fallow and it orders no more water. *Later version.*
6. **The daily balance.** With irrigation I reaching the soil:
   D_today = D_yesterday − P_e − I + E + runoff + percolation.
   Part of the water applied is lost on the way in, as runoff and as percolation below the
   roots; in Kalix that share is 1 − `efficiency`, and leaves as `escape`. Whatever would push D
   below full runs off, as `excess`.
7. **Ordering.** The farmer keeps the bucket near a target depletion T. Each day the model works
   out what will bring the bucket back to the target when the water arrives, counting what is
   already on its way (`orders_en_route`), grossed up for the losses in step 6:
   order = max(0, D − T) / efficiency × area.
   A refill trigger holds the order back until D reaches a threshold, then fills to T. In Kalix
   the rule is the `order` expression; see [The irrigation rule](#the-irrigation-rule).
8. **Planting and area.** On the plant date the area is set, from a number, a series, or the
   water available. The new crop's bucket starts from the fallow's depletion, scaled by root
   depth: D_new = D_fallow · min(1, Z_new / Z_fallow). Area can later be reduced, never
   increased, with the surplus going back to fallow. Fallow is itself a crop with no irrigation
   and a small K_c. *Later version.*
9. **Yield.** FAO-33: relative yield = 1 − K_y(1 − ΣE / ΣE_c), the sums running from planting to
   today. A dead crop yields 0. *Later version, if wanted.*

That is the whole model: two inputs (P, E₀), one state (D), three soil numbers (Z_r, θ_cap, p),
a K_c curve, a loss fraction and a target.

## How the node works

The steps above, as this version runs them each day, exactly:

The field works in mm over its area: 1 mm × 1 km² = 1 ML. In this order:

1. **Stress.** From the depletion `D` at the start of the step:
   `ks = clamp((capacity − D) / ((1 − p) × capacity), 0, 1)`. The crop transpires freely while it
   has used less than `p` of the capacity, and less and less as the soil dries beyond that
   (FAO-56, equation 84).
2. **Evapotranspiration.** `et = ks × kc × evap`, no more than the water the soil holds.
3. **Rain** goes on the soil, less `intercepted = min(rain, interception × evap)`. What would
   take the depletion below zero leaves as `excess`.
4. **Irrigation.** The field takes from what arrives no more than the soil has room for after the
   rain, allowing for the share that escapes: `supply = min(usflow, room / efficiency)`, of which
   `escape = supply × (1 − efficiency)` and the rest infiltrates. What it does not take is
   `bypass`. So irrigation never overfills the soil, whatever was ordered.

Rain goes on before irrigation so that a day's rain reduces what the field takes, rather than
running off a profile that irrigation has just filled. Effective rainfall is `rain − intercepted`.

**The balance closes every step, to machine precision:**

`(rain − intercepted) × area + (supply − escape) = et_vol + excess + Δ(water held)`, with
`usflow = supply + bypass` and `ds_1 = bypass + return_flow`. Each term is a result or follows from
one; the runoff lost is `excess − return_flow`.

#### The irrigation rule

The field owns the physics. When to irrigate, and how much, is the farmer's decision, and it is
written in the `order` expression, in ML. The field publishes what the decision needs:

- `this.depletion[-1, 0]`, the soil at the start of today [mm];
- `this.orders_en_route[-1, 0]`, what was ordered before today and has not arrived before today
  [ML], which includes what arrives today;
- `this.area` [km²] and `this.efficiency`.

`depletion` and `orders_en_route` are states, reported at the end of each step as a storage's
`volume` is, so the rule reads the previous step's value; the `0` is what it reads on the first
step, before any value exists.

The rule the IDE template carries tops the soil up to a target depletion of 40 mm, at most 120 mm
in a day, grossed up for escape, less what is already on its way:

```ini
order = this.area * clamp(this.depletion[-1, 0] - 40, 0, 120) / this.efficiency - this.orders_en_route[-1, 0]
```

`order` means what it means everywhere in Kalix, the order placed on the network. Nothing is
transformed behind the modeller's back: the allowance for escape is in the line. Other rules are
one line each. A refill trigger, irrigating to a target once the depletion passes a threshold:

```ini
order = if(this.depletion[-1, 0] >= 60, this.area * (this.depletion[-1, 0] - 20) / this.efficiency, 0)
```

Stopping irrigation once the soil is past the point of saving the crop:

```ini
order = if(this.depletion[-1, 0] >= 100, 0, this.area * clamp(this.depletion[-1, 0] - 40, 0, 120) / this.efficiency - this.orders_en_route[-1, 0])
```

`this.orders_en_route[-1, 0]` matters where there is travel time between the supply and the field.
Without it, a rule that orders the deficit places the same order every day until the first
delivery lands.

#### Ordering

A field orders like a regulated user: its order travels upstream to the supply, and the field
acts on it after its travel time (see [Ordering](ordering.md)). The order decides what is released
for the field; what the field takes is decided by the soil, from whatever arrives. So a field
outside any regulated zone, on an unregulated creek say, never places an order (and its `order`
and `order_due` results are not written), but irrigates from what reaches it all the same.

The links leaving a field are not regulated. A field's `ds_1` carries bypass and excess back to
the river: it is a drain, not a delivery path. No order travels up it, and the travel time to the
field is no part of the travel time to anything below it.

#### Returning runoff to the farm storage

Runoff has two fates. Where it drains to the river, `return_fraction` says how much of it gets
there, and it goes down `ds_1` with the bypass. Where the farm catches it and pumps it back to the
storage that supplies the field, the storage is defined above the field, and a Kalix model file
reads downstream, so no link can carry water back up to it. The loop is written with a one-step
delay, which any loop on a fixed timestep has anyway: set `return_fraction = 0`, so that the field
loses its runoff, and have an inflow node above the storage recreate the caught share from the
previous step:

```ini
[node.returns]
type = inflow
loc = 20, 10
inflow = 0.8 * node.paddock.excess[-1, 0]   ; 80% of yesterday's runoff, caught and pumped back
ds_1 = ofs

[node.ofs]
type = storage
...
ds_1 = paddock

[node.paddock]
type = field
...
return_fraction = 0
ds_1 = river                                 ; bypass goes on; runoff does not
```

Two things to know. The offset is required: without it the run stops with "no value yet", because
today's excess does not exist when the node above the field runs; nothing can build a same-step
loop. And use `return_fraction = 0` with the loop, not the fraction you write at the inflow node:
with both, the same water would go down `ds_1` to the river and be recreated at the storage.

In the mass balance report the loop appears as a loss at the field and a gain of the same size at
the inflow node, one step later; the two pair up by name. The last step's runoff is in transit when
the run ends.

#### Mass balance

Only what the field keeps and loses leaves the model at the field: the water the soil holds,
evapotranspiration, intercepted rain, escape, and the runoff that does not survive the drains.
`bypass` and `return_flow` are still in the model, on `ds_1`.

## References

Allen, R.G., Pereira, L.S., Raes, D. and Smith, M. (1998). *Crop evapotranspiration — Guidelines for
computing crop water requirements.* FAO Irrigation and Drainage Paper 56. Chapter 8, the root-zone
water balance and the stress coefficient Ks.
