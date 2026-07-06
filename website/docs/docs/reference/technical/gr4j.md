---
title: "GR4J"
---

# GR4J

This page is the technical reference for the GR4J rainfall-runoff model used by the [GR4J node](https://www.notion.so/2883cd7417a2804cbba1c2023f7c8465). For configuration syntax, properties, and result outputs, see the node page. This page covers the underlying model structure, equations, parameter behaviour, and calibration guidance.

## Model overview

GR4J ("Génie Rural à 4 paramètres Journalier") is a lumped, conceptual, daily rainfall-runoff model developed at INRAE (formerly Cemagref). It transforms catchment-average rainfall and potential evapotranspiration (PE) into daily streamflow using four calibrated parameters and a fixed structure of two non-linear stores and two unit hydrographs.

GR4J is widely adopted for its parsimony, computational efficiency, and good performance across a range of climates. It was developed and refined through testing on hundreds of catchments; the structure described here corresponds to the version published in Perrin et al. (2003).

## Conceptual structure

GR4J represents the catchment as a chain of conceptual processes:

1. **Net inputs.** Rainfall and PE are netted off against each other, so only one is non-zero on any timestep.

2. **Production store** (capacity X1) handles soil-moisture accounting, partitioning rainfall between catchment storage and percolation.

3. **Percolation** drains the production store via a non-linear flux.

4. **Split.** Effective rainfall is split 90% / 10% between two parallel routing paths.

5. **Unit hydrographs.** UH1 (base time X4) on the 90% path; UH2 (base time 2·X4) on the 10% path.

6. **Routing store** (reference capacity X3) on the 90% path, also non-linear.

7. **Groundwater exchange** (controlled by X2) adds or removes water from both routing paths, representing intercatchment groundwater transfer.

## Mathematical formulation

In the equations below, P is daily rainfall [mm], E is daily PE [mm], and S, R are the current states of the production and routing stores [mm].

### Net inputs

Only one of these is non-zero on any timestep:

- If P ≥ E: Pn = P − E, En = 0

- If P < E: Pn = 0, En = E − P

### Production store

Fraction of Pn entering the production store:

```jsx
Ps = X1 · (1 − (S/X1)²) · tanh(Pn/X1) / (1 + (S/X1) · tanh(Pn/X1))
```

Fraction of En drawn from the production store:

```jsx
Es = S · (2 − S/X1) · tanh(En/X1) / (1 + (1 − S/X1) · tanh(En/X1))
```

Update S: S ← S − Es + Ps

Percolation:

```jsx
Perc = S · { 1 − [ 1 + (4S / 9X1)⁴ ]^(−1/4) }
```

Update S: S ← S − Perc

### Effective rainfall and split

Pr = Perc + (Pn − Ps)

90% of Pr enters UH1; 10% enters UH2.

### Unit hydrographs

UH1 has base time X4 days; UH2 has base time 2·X4 days. Both are derived from a power-law S-curve and convolved with their respective inputs to produce delayed outputs Q9 (from UH1) and Q1 (from UH2).

### Groundwater exchange

```jsx
F = X2 · (R/X3)^(7/2)
```

F can be positive (water gained) or negative (water lost), depending on the sign of X2.

### Routing store

Update R: R ← max(0, R + Q9 + F)

Outflow from the routing store:

```jsx
Qr = R · { 1 − [ 1 + (R/X3)⁴ ]^(−1/4) }
```

Update R: R ← R − Qr

### Direct flow

Qd = max(0, Q1 + F)

### Total streamflow

Q = Qr + Qd

Q is the model's daily runoff depth [mm]. In Kalix, this is reported as `runoff_depth` and converted to `runoff_volume` [ML] using the catchment area.

## Parameters

The four parameters are passed to the GR4J node in this order: X1, X2, X3, X4.

### X1 — Production store capacity [mm]

Maximum capacity of the soil-moisture (production) store. Larger X1 means more buffering of rainfall and slower drying of the catchment.

- Typical bounds: 10 to 6000 mm

- Sensitivity: usually the most important parameter for water balance. Low values produce flashy systems with little soil-moisture memory.

### X2 — Groundwater exchange coefficient [mm/day]

Magnitude (and sign) of the intercatchment exchange flux. Positive X2 means water is gained by the system; negative X2 means water is lost.

- Typical bounds: −20 to +10 mm/day

- Note: X2 is conceptual rather than directly observable. It compensates for water-balance closure errors and sub-surface losses or gains the model otherwise cannot represent.

### X3 — Routing store reference capacity [mm]

Reference (one-day-ahead maximum) capacity of the routing store. Larger X3 means slower routing response.

- Typical bounds: 1 to 4000 mm

- Sensitivity: controls the recession behaviour of simulated flow.

### X4 — Unit hydrograph time base [days]

Base time of UH1 (UH2 has base time 2·X4). Controls the timing of the runoff response.

- Typical bounds: 0.5 to 20 days. Some studies use a lower bound as small as 0.04 days for very flashy catchments.

## Calibration guidance

- **Initial states.** GR4J is sensitive to the initial values of S and R. Use a warm-up period of at least one year (longer in arid catchments) to remove their influence.

- **Joint calibration.** All four parameters are typically calibrated jointly because of correlations between them. X1 and X3 trade off when balancing fast and slow flow components; X2 trades off against the production store to close the water balance.

- **Identifiability.** X1 and X4 are usually well identified. X2 and X3 can be less well identified, especially in catchments with stable baseflow.

- **Objective functions.** NSE on flows emphasises high flows; NSE on log-flows or KGE-on-log emphasises low flows; KGE balances bias, correlation, and variability. Multi-objective calibration is common.

## Strengths and limitations

### Strengths

- Parsimonious — only four free parameters.

- Computationally cheap.

- Well validated on hundreds of catchments globally.

- Good general performance across a wide range of climates and catchment scales.

### Limitations

- Lumped — no spatial representation of within-catchment variability.

- Daily timestep — not appropriate for sub-daily flood-event modelling without modification.

- X2 absorbs water-balance closure errors; it is not a directly measurable physical property.

- No snow processes; couple with a snow module (e.g. CemaNeige) upstream of inputs for snow-affected catchments.

- Performance can degrade in arid catchments with long zero-flow periods, and in heavily regulated systems where the rainfall–runoff relationship is broken.

## Implementation notes

- The Kalix GR4J node passes the four parameters in the order X1, X2, X3, X4 (see the [GR4J node page](https://www.notion.so/2883cd7417a2804cbba1c2023f7c8465)).

- All internal calculations are in mm; conversion to ML uses the configured catchment area.

- Model states (S and R) are carried between timesteps and initialised at the start of the simulation.

## References

Perrin, C., Michel, C., and Andréassian, V. (2003). "Improvement of a parsimonious model for streamflow simulation." *Journal of Hydrology* 279(1–4): 275–289.

Edijatno, Nascimento, N. de O., Yang, X., Makhlouf, Z., and Michel, C. (1999). "GR3J: a daily watershed model with three free parameters." *Hydrological Sciences Journal* 44(2): 263–277.

Le Moine, N. (2008). *Le bassin versant de surface vu par le souterrain : une voie d'amélioration des performances et du réalisme des modèles pluie–débit?* PhD thesis, Université Pierre et Marie Curie (Paris 6).
