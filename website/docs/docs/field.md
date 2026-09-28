---
title: "Field"
---

# Field

## At a glance…

The field node represents an irrigated field: paddocks under crops on a soil that dries by
evapotranspiration, fills with rain and irrigation, and orders water upstream to meet each crop's
deficit. Each crop's root zone is the FAO-56 daily depletion balance, with the crop's coefficient
and a stress coefficient that reduces evapotranspiration as the soil dries. The soil below the
roots is kept in layers, so that water left by one crop is there for the next.

A field is supplied by the node above it: a [storage](storage.md) outlet, or a supply outlet
(`ds_2` to `ds_4`) of a [regulated user](regulated-user.md#supply-outlets) or an
[unregulated user](unregulated-user.md#supply-outlets). What it does not take, and what runs off
the soil, drains down `ds_1`; the share of the runoff the farm catches leaves on `ds_2` instead.

The field has a **fallow**, which is what is not planted, and up to four **crop slots**. A crop
is declared once, in a `[crop.*]` section: what is true of the plant wherever it grows. A slot's
properties are the farmer's decisions for that crop: when to plant, how much, how to irrigate,
when to give up. Planting takes area from the fallow; harvest gives it back.

```ini
[crop.cotton]
root_depth = 900                    ; mm
p = 0.65
kc = Day, Kc,                       ; by days since planting
     0,   0.35,
     30,  0.35,
     70,  1.20,
     130, 1.20,
     180, 0.60,
season_len = 180                    ; harvested this many days after planting

[crop.fallow]
root_depth = 600
kc = 0.4

[node.paddock]
type = field
loc = 30, 40
area = 4.2                          ; km2
available_water = 150               ; mm of water per m of soil
rain = data.climate_csv.by_name.rain
evap = data.climate_csv.by_name.et0
efficiency = 0.8
fallow = fallow
crop_1 = cotton
crop_1_plant = sim.month == 10 && sim.day == 15
crop_1_plant_area = min(this.area, 0.01 * node.ofs.volume[-1, 0] / 8)
crop_1_order = this.crop_1_area[-1, 0] * clamp(this.crop_1_depletion[-1, 0] - 40, 0, 120) / this.efficiency - this.crop_1_orders_en_route[-1, 0]
ds_1 = drain
```

## Crops: `[crop.*]`

A crop is a section in the `crop.*` namespace, referenced by name from any number of fields. It
is a declaration: numbers and one table, nothing evaluated. The name is lowercase letters,
digits and underscores, starting with a letter.

| Property | Description |
| --- | --- |
| root\_depth (compulsory) | How deep the crop roots [mm]. Its bucket holds `available_water × root_depth / 1000` mm of water between full and empty. Example: `root_depth = 900` |
| p (optional) | The depletion fraction: the share of the bucket the crop can use before stress begins (FAO-56 Table 22). Default 0.5. Example: `p = 0.65` |
| kc (compulsory) | The crop coefficient: a number, or a table of (days since planting, kc) with the FAO-56 curve as its rows, interpolated between rows and held flat beyond the first and last. A text header row is allowed. Example: `kc = 0.95` |
| season\_len (optional) | Days from planting to harvest. Omitted, the crop is a perennial and is never harvested. Example: `season_len = 180` |

Every field has a fallow: a crop with no plants, declared like any other and named by the field's
`fallow`. It is never irrigated and never planted; it receives area at harvest and abandonment
and gives it up at planting. A field with no crop slots is all fallow, all the time. A fallow's
`kc` is its bare-soil evaporation factor, and its `root_depth` is how deep the fallow ground
dries.

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Example: `[node.paddock]` |
| type (compulsory) | The node type, which is "field" in this case. `type = field` |
| loc (compulsory) | The location of the node in cartesian coordinates. Example: `loc = 30, 40` |
| area (compulsory) | The area of the field [km²]. 1 mm over 1 km² is 1 ML, so 4.2 km² is 420 ha. Readable in expressions as `this.area`. Example: `area = 4.2` |
| available\_water (compulsory) | The water the soil holds between full (field capacity) and empty (wilting point), per metre of soil [mm/m] (FAO-56 Table 19: sand 60 to 100, loam 130 to 180, clay 120 to 200). A crop's bucket holds this times its root depth. Example: `available_water = 150` |
| fallow (compulsory) | The crop that covers what is not planted: the name of a `[crop.*]` section. Example: `fallow = fallow` |
| rain (optional) | Rainfall on the field [mm]. Omitted, no rain falls. Example: `rain = data.climate_csv.by_name.rain` |
| evap (optional) | Reference evapotranspiration [mm], the reference the crops' `kc` values were derived for (ET₀ for FAO-56 coefficients). Omitted, nothing evaporates. Example: `evap = data.climate_csv.by_name.et0` |
| efficiency (optional) | The share of the water supplied that reaches the soil at all. The rest is `escape` (spray evaporation, wind drift, delivery loss, tailwater the field does not keep) and leaves the model here. Never percolation, which the soil's layers model. Readable as `this.efficiency`. Default 1. Example: `efficiency = 0.8` |
| interception (optional) | Rain reaches the soil only beyond this fraction of the day's `evap`; the rest wets the canopy and evaporates (`intercepted`). Default 0.2, FAO-56's interception loss. Write `0` to take rain as given. Example: `interception = 0.2` |
| return\_fraction (optional) | The share of the field's runoff (`excess`) that the farm's drains catch. It leaves on `ds_2` as `return_flow`; the rest goes down `ds_1` with the bypass. Default 0. See [Returning runoff to the farm storage](#returning-runoff-to-the-farm-storage). Example: `return_fraction = 0.8` |
| initial\_depletion (optional) | How far below full the soil starts, over the whole profile to the deepest roots [mm], every layer alike. Default 0, a full profile top to bottom. Example: `initial_depletion = 20` |
| crop\_N (optional) | The crop in slot N, for N from 1 to 4: the name of a `[crop.*]` section. Slots are numbered without gaps. Example: `crop_1 = cotton` |
| crop\_N\_plant (compulsory with crop\_N) | An expression, read every day the slot is empty: true plants the crop, taking area from the fallow with its water. A slot already in the ground does not fire; where two fire the same day, the lower N plants first. A trigger that stays true plants again the day after a harvest. Example: `crop_1_plant = sim.month == 10 && sim.day == 15` |
| crop\_N\_plant\_area (compulsory with crop\_N) | The area planted [km²], read on the day the trigger fires, capped at the fallow's area that day. Example: `crop_1_plant_area = min(this.area, 0.01 * node.ofs.volume[-1, 0] / 8)` |
| crop\_N\_order (optional) | The irrigation rule for this crop: the order it places upstream each step [ML]. An expression, read only while the crop is in the ground; see [The irrigation rule](#the-irrigation-rule). Omitted, the crop is rain-fed. |
| crop\_N\_viable\_area (optional) | An expression, read every day the crop is in the ground: the area becomes `min(area, value)` [km²], and what leaves goes back to the fallow with its water. Zero is death. Omitted, the built-in rule applies: a crop whose stress coefficient is 0.05 or below at the start of the day dies, and its area returns to the fallow. Writing any expression replaces that rule entirely. A negative or non-numeric value stops the run. |
| ds\_1 (optional) | Name of the downstream node on the river: `bypass` and the river's share of the runoff drain down it. Example: `ds_1 = river` |
| ds\_2 (optional) | Name of the node the caught runoff (`return_flow`) drains to: a blackhole when it is pumped back to the farm storage through an inflow node, a tailwater dam, or a drain. Example: `ds_2 = drain` |

## Results associated with this node

| Result | Description |
| --- | --- |
| crop\_N\_area | The area under slot N's crop at the end of the step [km²]; 0 when nothing is in the ground |
| crop\_N\_days | Days since planting, 0 on the day it is planted; not a number when nothing is in the ground |
| crop\_N\_depletion | How far the crop's root zone is below full at the end of the step [mm]: 0 is full, the bucket's capacity is empty. A state, reported at the end of the step like a storage's `volume`; the irrigation rule reads the previous step's value, `this.crop_N_depletion[-1, 0]`, the soil at the start of today. Not a number when nothing is in the ground |
| crop\_N\_ks | The crop's stress coefficient this step, 0 to 1, from the depletion at the start of the day |
| crop\_N\_order | The order slot N placed this step [ML] |
| crop\_N\_order\_due | The order placed earlier for slot N that is due to arrive this step [ML] |
| crop\_N\_orders\_en\_route | Water on its way to slot N at the end of the step [ML]: ordered, today's order included, and not yet arrived. Zero without travel time from the supply. A state, like `crop_N_depletion`; the irrigation rule reads `this.crop_N_orders_en_route[-1, 0]` |
| fallow\_depletion | How far the fallow's root zone is below full at the end of the step [mm] |
| usflow | Upstream flow: the water that arrives at the field [ML] |
| et | Evapotranspiration over the whole field [mm]: the partitions' `ks × kc × evap`, each no more than the water its bucket holds, weighted by area |
| et\_vol | Evapotranspiration [ML]: `et × area` |
| rain | The value of the `rain` expression [mm] |
| rain\_vol | Rain on the field [ML]: `rain × area` |
| intercepted | Rain that did not reach the soil [mm]: `min(rain, interception × evap)` |
| evap | The value of the `evap` expression [mm] |
| excess | Rain the soil could not hold, to the bottom of the deepest roots [ML]: the runoff, split between `ds_1` and `ds_2` by `return_fraction` |
| supply | The water the field takes from what arrives [ML] |
| escape | The share of `supply` that does not reach the soil [ML], which leaves the model here |
| bypass | The water that arrives and is not taken [ML], passed down `ds_1` whole: the irrigator would have refused it at the pump |
| return\_flow | The runoff the farm catches [ML]: `return_fraction × excess`, on `ds_2` |
| dsflow | Downstream flow [ML], both outlets: `bypass + excess` |
| ds\_1 | Flow on link ds\_1 [ML]: `bypass + excess − return_flow` |
| ds\_2 | Flow on link ds\_2 [ML]: `return_flow` |
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
   soil's water-holding capacity as a fraction (0.1–0.3 is typical). In Kalix θ_cap is the
   field's `available_water` and Z_r the crop's `root_depth`.
3. **The state: depletion.** D is how far the bucket is below full, in mm. D = 0 is full and
   D = TAW is empty. This is the crop's `crop_N_depletion`.
4. **What the crop wants.** A well-watered crop uses E_c = K_c · E₀, where the crop coefficient
   K_c depends on the crop and how far through its growth it is (`kc`).
5. **What it gets when the bucket is low.** A crop drinks freely until it has used a fraction
   `p` of the capacity, then less and less:
   K_s = 1 if D ≤ p·TAW, otherwise (TAW − D) / ((1 − p)·TAW), and E = K_s · E_c.
   K_s is computed from D at the start of the day (the crop's `crop_N_ks`). A crop whose K_s
   opens the day at 0.05 or below is dead: its area goes back to the fallow and it orders no
   more water. That is the built-in rule; `crop_N_viable_area` replaces it.
6. **The daily balance.** With irrigation I reaching the soil:
   D_today = D_yesterday − P_e − I + E + drainage.
   Part of the water applied never reaches the soil: spray evaporation, wind drift, delivery
   loss; in Kalix that share is 1 − `efficiency`, and leaves as `escape`. Whatever would push D
   below full drains into the soil below the roots, layer by layer, and once that is full runs
   off, as `excess`.
7. **Ordering.** The farmer keeps the bucket near a target depletion T. Each day the model works
   out what will bring the bucket back to the target when the water arrives, counting what is
   already on its way (`orders_en_route`), grossed up for the losses in step 6:
   order = max(0, D − T) / efficiency × area.
   A refill trigger holds the order back until D reaches a threshold, then fills to T. In Kalix
   the rule is the crop's `crop_N_order` expression; see [The irrigation rule](#the-irrigation-rule).
8. **Planting and area.** On the plant date the area is set, from a number, a series, or the
   water available (`crop_N_plant`, `crop_N_plant_area`). The new crop's bucket starts as wet
   as the ground it is planted into, to its own root depth. Area can later be reduced, never
   increased, with the surplus going back to the fallow (`crop_N_viable_area`); at harvest
   (`season_len`) all of it goes back. The fallow is itself a crop with no irrigation and a
   small K_c.
9. **Yield.** FAO-33: relative yield = 1 − K_y(1 − ΣE / ΣE_c), the sums running from planting to
   today. A dead crop yields 0. *Later version, if wanted.*

That is the whole model: two inputs (P, E₀), one state per crop (D), three soil numbers (Z_r,
θ_cap, p), a K_c curve, a loss fraction and a target.

## How the node works

The steps above, as this version runs them each day, exactly.

The field is partitioned: the fallow, and each crop slot with a crop in the ground. Each
partition works in mm over its own area: 1 mm × 1 km² = 1 ML. The soil is kept in layers whose
boundaries are the field's distinct root depths, fallow included, so every crop's root zone is a
whole number of layers; a layer holds `available_water` times its thickness. Each partition has
one bucket for its root zone, which the plants and the irrigator work, and one depletion for
each layer below its roots, which only drainage and transfers touch. Below the deepest roots on
the field nothing draws, so water that passes is gone.

In this order:

1. **Transfers**, so that the day's orders and fluxes use the day's areas. Harvest: a crop whose
   days since planting reach its `season_len` goes back to the fallow. Abandonment: a crop's
   area becomes `min(area, crop_N_viable_area)`, or 0 under the built-in rule when its stress
   coefficient at the start of the day is 0.05 or below; what leaves goes to the fallow.
   Planting: a slot with no crop whose `crop_N_plant` is true takes `crop_N_plant_area` from
   the fallow, at most what the fallow has. Every transfer moves area with its water, layer by
   layer, in proportion to area: the giver's wetness does not change, and the receiver's is the
   area-weighted mix. Where a root zone shrinks (a deep crop's land going to a shallower
   fallow) the bucket's water is spread evenly over the layers it covered and the deeper ones
   keep it; where it grows, the new bucket pools the layers it reaches, with whatever each held.
   Nothing is created or lost in a transfer.
2. **Stress**, for each partition, from the depletion `D` of its bucket at the start of the day:
   `ks = clamp((TAW − D) / ((1 − p) × TAW), 0, 1)`, with `TAW` the bucket's capacity and `p` the
   crop's. The crop transpires freely while it has used less than `p` of its bucket, and less
   and less as the soil dries beyond that (FAO-56, equation 84).
3. **Evapotranspiration.** `et = ks × kc × evap`, no more than the water the bucket holds, with
   `kc` from the crop's curve at its days since planting (the fallow's counts from the start of
   the run).
4. **Rain** goes on every partition, less `intercepted = min(rain, interception × evap)`. What
   would take a bucket below zero drains into the layers below it, filling each in turn; what
   passes the last layer leaves as `excess`.
5. **Irrigation.** Each crop in the ground takes from what arrives up to its own `order_due`,
   and no more than its bucket has room for after the rain, allowing for the share that
   escapes: `room / efficiency`. Water arriving beyond the orders is a forced watering the
   modeller intended: it is poured over the crops in the ground so as to level their depletion,
   driest first, each again capped by its room. The fallow is never irrigated. What no crop can
   take is `bypass`. Of what is taken, `escape = supply × (1 − efficiency)` and the rest
   infiltrates. So irrigation never overfills a root zone, whatever was ordered.

Rain goes on before irrigation so that a day's rain reduces what a crop takes, rather than
running off a bucket that irrigation has just filled. Effective rainfall is `rain − intercepted`.

**The balance closes every step, to machine precision:**

`(rain − intercepted) × area + (supply − escape) = et_vol + excess + Δ(water held)`, with
`usflow = supply + bypass`, `ds_1 = bypass + excess − return_flow` and `ds_2 = return_flow`. The
water held is every partition's buckets and layers; the results show the buckets
(`crop_N_depletion`, `fallow_depletion`), and the layers below the roots hold the rest. Nothing
leaves the field except on its links, as evapotranspiration, intercepted rain or escape.

#### The irrigation rule

The field owns the physics. When to irrigate, and how much, is the farmer's decision, and it is
written per crop in the `crop_N_order` expression, in ML. The field publishes what the decision
needs:

- `this.crop_N_depletion[-1, 0]`, the crop's soil at the start of today [mm];
- `this.crop_N_orders_en_route[-1, 0]`, what was ordered for it before today and has not arrived
  before today [ML], which includes what arrives today;
- `this.crop_N_area[-1, 0]` [km²], and `this.area` and `this.efficiency`.

These are states, reported at the end of each step as a storage's `volume` is, so the rule
reads the previous step's value; the `0` is what it reads on the first step, before any value
exists. The rule is read only while the crop is in the ground; a slot with nothing planted orders
nothing.

The rule the IDE template carries tops the soil up to a target depletion of 40 mm, at most 120 mm
in a day, grossed up for escape, less what is already on its way:

```ini
crop_1_order = this.crop_1_area[-1, 0] * clamp(this.crop_1_depletion[-1, 0] - 40, 0, 120) / this.efficiency - this.crop_1_orders_en_route[-1, 0]
```

`order` means what it means everywhere in Kalix, the order placed on the network; the field
places the sum of its crops' orders. Nothing is transformed behind the modeller's back: the
allowance for escape is in the line. Other rules are one line each. A refill trigger, irrigating
to a target once the depletion passes a threshold:

```ini
crop_1_order = if(this.crop_1_depletion[-1, 0] >= 60, this.crop_1_area[-1, 0] * (this.crop_1_depletion[-1, 0] - 20) / this.efficiency, 0)
```

Stopping irrigation once the soil is past the point of saving the crop:

```ini
crop_1_order = if(this.crop_1_depletion[-1, 0] >= 100, 0, this.crop_1_area[-1, 0] * clamp(this.crop_1_depletion[-1, 0] - 40, 0, 120) / this.efficiency - this.crop_1_orders_en_route[-1, 0])
```

`this.crop_N_orders_en_route[-1, 0]` matters where there is travel time between the supply and
the field. Without it, a rule that orders the deficit places the same order every day until the
first delivery lands.

#### Planting, harvest and giving up

`crop_N_plant` is read every day the slot is empty, and plants when true. A date is the common
trigger; the water in hand decides the area:

```ini
crop_1 = cotton
crop_1_plant = sim.month == 10 && sim.day == 15
crop_1_plant_area = min(this.area, 0.01 * (node.ofs.volume[-1, 0] + acc.farm.closing_balance[-1, 0]) / 8)
```

The `0.01` turns hectares into km²; this line plants a hectare for every 8 ML in the storage and
the account. A perennial is planted once and stays (`crop_1_plant = sim.year == 1990 && sim.month
== 3 && sim.day == 1`, with no `season_len` on the crop). Several slots may be in the ground at
once: a winter crop in one and a summer crop in another, or the same crop in two slots planted a
month apart.

Harvest is `season_len` days after planting, the whole area back to the fallow with its water.
Giving up is `crop_N_viable_area`: each day the crop keeps at most that area, so it can only
fall. Written against the crop's own state it is a rule for abandoning a failing crop, and 0 is
death:

```ini
crop_1_viable_area = if(this.crop_1_ks[-1, 0] < 0.2, 0, this.crop_1_area[-1, 0])
```

With no rule written, the field applies its own: a crop whose stress coefficient is 0.05 or below
at the start of the day dies. Because stress reduces evapotranspiration, a dry bucket empties
along a flattening curve, and that default is slow to arrive; a written rule can be quicker.

#### Ordering

A field orders like a regulated user: its order travels upstream to the supply, and the field
acts on it after its travel time (see [Ordering](ordering.md)). The order decides what is released
for the field; what the crops take is decided by the soil, from whatever arrives. So a field
outside any regulated zone, on an unregulated creek say, never places an order (and its
`crop_N_order` and `crop_N_order_due` results are not written), but irrigates from what reaches
it all the same.

The links leaving a field are not regulated. A field's outlets carry bypass and runoff away: they
are drains, not delivery paths. No order travels up it, and the travel time to the
field is no part of the travel time to anything below it.

#### Returning runoff to the farm storage

Runoff has two fates, and the field puts them on two links: the share the farm's drains catch,
`return_fraction`, leaves on `ds_2` as `return_flow`, and the rest goes down `ds_1` with the
bypass. Where the caught runoff is pumped back to the storage that supplies the field, the
storage is defined above the field, and a Kalix model file reads downstream, so no link can carry
water back up to it. The loop is written with a one-step delay, which any loop on a fixed timestep
has anyway: `ds_2` ends in a blackhole, and an inflow node above the storage reads the return
flow from the previous step:

```ini
[node.returns]
type = inflow
loc = 20, 10
inflow = node.paddock.return_flow[-1, 0]
ds_1 = ofs

[node.ofs]
type = storage
...
ds_1 = paddock

[node.paddock]
type = field
...
return_fraction = 0.8
ds_1 = river        ; bypass and the runoff the farm does not catch
ds_2 = drain        ; the caught runoff, recreated at `returns` next step

[node.drain]
type = blackhole
```

The offset is required: without it the run stops with "no value yet", because today's return flow
does not exist when the node above the field runs; nothing can build a same-step loop. In the mass
balance report the loop appears as a loss at the blackhole and a gain of the same size at the
inflow node, one step later; the two pair up by name. The last step's return flow is in transit
when the run ends.

Runoff the farm loses for good is `ds_2` to a blackhole with no inflow node reading it; runoff
that drains to a tailwater dam or a channel is `ds_2` to that node.

#### Mass balance

Only what the field keeps and loses leaves the model at the field: the water the soil holds, in
every bucket and layer, evapotranspiration, intercepted rain, and escape. `bypass`, `excess` and `return_flow` are still
in the model, on `ds_1` and `ds_2`.

## References

Allen, R.G., Pereira, L.S., Raes, D. and Smith, M. (1998). *Crop evapotranspiration — Guidelines for
computing crop water requirements.* FAO Irrigation and Drainage Paper 56. Chapter 8, the root-zone
water balance and the stress coefficient Ks; Table 19 for available water by soil texture and
Table 22 for crop depletion fractions.
