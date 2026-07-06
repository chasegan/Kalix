---
title: Objective functions
---

# Objective functions

Objective functions available in Kalix for hydrological model calibration. All objective functions are configured for minimisation during optimisation.

---

## NSE - Nash-Sutcliffe Efficiency

### Description

The Nash-Sutcliffe Efficiency (NSE) is one of the most widely used statistics for assessing the goodness-of-fit of hydrological models. It compares the performance of the model against a simple baseline (the mean of observed values).

### Formula

`NSE=1−∑i=1n(Qo[i]−Qo)2∑i=1n(Qo[i]−Qm[i])2`

Where:  
- *Qo*[*i*] = observed value at timestep *i*  
- *Qm*[*i*] = modeled/simulated value at timestep *i*  
- $\overline{Q\_o}$ = mean of observed values  
- *n* = number of timesteps

### Optimisation

- **Original range**: (−∞, 1] where 1 = perfect fit

- **Minimisation**: NSE is **negated** so objective = −NSE

- **Optimal value**: -1.0 (corresponds to NSE = 1.0)

### When to Use

- General-purpose calibration metric

- Emphasizes high flows (squared errors give more weight to large deviations)

- NSE = 1: perfect fit

- NSE = 0: model performs as well as using the mean

- NSE < 0: model performs worse than using the mean

---

## LNSE - Log Nash-Sutcliffe Efficiency

### Description

Log-transformed version of NSE that applies logarithmic transformation to flows before calculating the Nash-Sutcliffe metric. This gives more weight to low flows compared to standard NSE.

### Formula

`LNSE=1−∑i=1n(ln(Qo[i]+ϵ)−ln(Qo+ϵ))2∑i=1n(ln(Qo[i]+ϵ)−ln(Qm[i]+ϵ))2`

Where *ϵ* = 0.01 is a small constant added to avoid ln (0)

### Optimisation

- **Original range**: (−∞, 1] where 1 = perfect fit

- **Minimization**: LNSE is **negated** so objective = −LNSE

- **Optimal value**: -1.0 (corresponds to LNSE = 1.0)

### When to Use

- When accurate simulation of low flows is important

- Baseflow-dominated catchments

- Water quality or ecological flow studies

- Reduces influence of extreme high flows

---

## RMSE - Root Mean Square Error

### Description

RMSE measures the average magnitude of errors between observed and simulated values. It gives more weight to larger errors due to squaring.

### Formula

`RMSE=n1∑i=1n(Qo[i]−Qm[i])2`

### Optimization

- **Range**: [0, ∞) where 0 = perfect fit

- **Minimization**: Already a minimization metric (lower is better)

- **Optimal value**: 0.0

### When to Use

- When you want errors in the same units as your data

- Penalizes large errors more heavily than MAE

- Standard error metric across many fields

- More sensitive to outliers than MAE

---

## MAE - Mean Absolute Error

### Description

MAE measures the average absolute difference between observed and simulated values. Unlike RMSE, it treats all errors proportionally.

### Formula

`MAE=n1∑i=1n∣Qo[i]−Qm[i]∣`

### Optimization

- **Range**: [0, ∞) where 0 = perfect fit

- **Minimization**: Already a minimization metric (lower is better)

- **Optimal value**: 0.0

### When to Use

- When outliers should not dominate the error metric

- More robust to extreme values than RMSE

- Easier to interpret (average error magnitude)

- When all errors should be weighted equally

---

## KGE - Kling-Gupta Efficiency

### Description

The Kling-Gupta Efficiency provides a decomposed analysis of model performance by separately evaluating correlation, variability bias, and mean bias. It was developed to address limitations in NSE.

### Formula

`KGE=1−(r−1)2+(α−1)2+(β−1)2`

Where:  
- *r* = Pearson correlation coefficient between observed and simulated  
- `α=σoσm` = ratio of simulated to observed standard deviations  
- `β=μoμm` = ratio of simulated to observed means

### Optimization

- **Original range**: (−∞, 1] where 1 = perfect fit

- **Minimization**: KGE is **negated** so objective = −KGE

- **Optimal value**: -1.0 (corresponds to KGE = 1.0)

### When to Use

- Provides balanced assessment of three model aspects:
  - **Correlation** (timing and dynamics)
  - **Variability** (capturing the spread)
  - **Bias** (volume errors)

- Preferred over NSE in many recent hydrological studies

- Less sensitive to systematic bias than NSE

- Good for diagnosing specific model deficiencies

---

## PBIAS - Percent Bias

### Description

Percent Bias measures the average tendency of simulated values to be larger or smaller than observed values. Positive values indicate model overestimation, negative values indicate underestimation.

### Formula

`PBIAS=100×∑i=1nQo[i]∑i=1n(Qm[i]−Qo[i])`

### Optimization

- **Range**: (−∞, ∞) where 0 = no bias

- **Minimization**: Absolute value is taken, so objective = |PBIAS|

- **Optimal value**: 0.0

### When to Use

- Simple measure of volume balance

- Easy to interpret (percentage over/under prediction)

- Often used in conjunction with other metrics

- **Important**: PBIAS alone doesn’t assess timing or variability

---

## SDEB - Sorted Data Error with Bias

### Description

SDEB is a specialized objective function that combines temporal error (SD), distributional error (SE), and volume bias (B). It uses square-root transformation to balance emphasis between high and low flows and compares both chronological sequences and flow duration curves.

### Formula

`SDEB = (0.1 × SD + 0.9 × SE) × B`

Where:

**Temporal Error (SD)**:  
`SD=∑i=1n(Qo[i]−Qm[i])2`

**Distributional Error (SE)**:  
`SE=∑i=1n(Ro[i]−Rm[i])2`

Where *Ro* and *Rm* are observed and simulated values sorted in ascending order (exceedance).

**Bias Penalty (B)**:  
`B=1+∑i=1nQo[i]∣∑i=1nQo[i]−∑i=1nQm[i]∣`

### Optimization

- **Range**: [0, ∞) where 0 = perfect fit

- **Minimization**: Already a minimization metric (lower is better)

- **Optimal value**: 0.0

### When to Use

- Balanced emphasis on flow distribution (90%) and timing (10%)

- Square-root transformation reduces dominance of high flows

- Better for matching flow duration curves

- Includes volume balance through bias penalty

- Good for applications where flow distribution matters (e.g., hydropower, ecology)

### Special Features

- Uses lazy caching for efficiency

- Penalizes volume errors through multiplicative bias term

---

## PEARS\_R - Pearson’s Correlation Coefficient

### Description

Pearson’s R measures the linear correlation between observed and simulated values. It assesses how well the model captures the dynamics and timing of the observed data, independent of bias or scale.

### Formula `r=∑i=1n(Qo[i]−Qo)2×∑i=1n(Qm[i]−Qm)2∑i=1n(Qo[i]−Qo)(Qm[i]−Qm)`

Where:  
- $\overline{Q\_o}$ = mean of observed values  
- $\overline{Q\_m}$ = mean of simulated values

### Optimization

- **Original range**: [−1, 1] where 1 = perfect positive correlation

- **Minimization**: R is **negated** so objective = −*r*

- **Optimal value**: -1.0 (corresponds to R = 1.0)

### When to Use

- Focus on timing and dynamics rather than volume

- Insensitive to systematic bias (can have high R but large bias)

- Insensitive to scale differences

- **Best used with other metrics** (e.g., PBIAS for volume balance)

- Good for pattern matching

- R > 0.9: very high correlation

- R > 0.7: high correlation

- R > 0.5: moderate correlation

### Limitations

- Does not penalize bias or variability differences

- Can give high scores even with systematic over/under prediction

- Should be combined with bias metrics for comprehensive assessment

---

## Summary Table

| Objective | Config Name | Optimal | Emphasizes |
| --- | --- | --- | --- |
| NSE | `NSE` | -1.0 | High flows |
| LNSE | `LNSE` | -1.0 | Low flows |
| RMSE | `RMSE` | 0.0 | Large errors |
| MAE | `MAE` | 0.0 | All errors equally |
| KGE | `KGE` | -1.0 | Correlation + variability + bias |
| PBIAS | `PBIAS` | 0.0 | Volume balance only |
| SDEB | `SDEB` | 0.0 | Flow distribution + timing |
| PEARS\_R | `PEARS_R` | -1.0 | Timing/dynamics only |

---

## Configuration Example

To use an objective function in your calibration INI file:

```
objective_function = KGE
```

Valid options: `NSE`, `LNSE`, `RMSE`, `MAE`, `KGE`, `PBIAS`, `SDEB`, `PEARS_R`

---

## Recommendations

### For General Hydrological Calibration

- **First choice**: `SDEB` - provides balanced assessment

- **Alternative:** `KGE` - provides balanced assessment

- **Alternative**: `NSE` - widely used

### For Low-Flow Focused Applications

- **First choice**: `LNSE` - emphasizes baseflow

- **Alternative**: `SDEB` - balanced high/low flows

### For Volume Balance

- Use `PBIAS` in combination with other metrics

### For Timing Matching

- **First choice**: `PEARS_R` - focuses on timing

### For Error Measurement

- `MAE` - less sensitive to outliers

- `RMSE` - more sensitive to large errors
