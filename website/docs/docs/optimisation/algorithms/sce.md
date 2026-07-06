---
title: "Shuffled Complex Evolution (SCE)"
---

# Shuffled Complex Evolution (SCE)

## Implementation

There are some details that vary between different SCE implementations. These are small tweaks (automatic calculation of metaparameters, elitism, behaviour at the param bounds, etc) which can be important.

The implementation in Kalix follows that of Fors, which is as follows:

**Main algorithm**

1. Input parameters:
   1. `n_complexes` (mandatory)
   2. `termination_evaluations` (mandatory)
   3. `n_params` (defined by problem)
   4. `n_threads` (optional)
   5. `random_seed` (optional)

2. Compute metaparameters:
   1. `m = 2 * n_params + 1` = number of points per complex
   2. `s = n_complexes * m` = total size of population across all complexes
   3. `b = m` = number of breeding iterations in the CCE algorithm
   4. `p = n_params + 1` = number of parents in the simplex
   5. `elitism = 1` = weighting scheme (1 → trapezoidal weighting per Duan etal 1994)

3. Use **latin hypercube sampling** method to generate random parameters for all `s` members of the initial population.

4. Evaluate the objective function values for all `s` members. Keep track of the total number of evaluations we have done `n_evaluations = s`.

5. Sort them from best to worst (lowest-to-highest objective value). Keep a record of the best solution.

6. Use **round robin** method to divide the population into `n_complexes` complexes. This means member number `i` goes into complex number `i % n_complexes`. After this each new complex will have `m` members.

7. While `n_evaluations < termination_evaluations` iterate:
   1. Run the CCE algorithm independently (sequentially or in parallel) to evolve each complex. After this, each complex will have some different members, and all their objective function values will be evaluated.
   2. Update the number of evaluations based on how many were done to evolve the complexes `n_evaluations = n_evaluations + new_evaluations` .
   3. Combine the members from all complexes into a single population of `s` members.
   4. Sort them from best to worst (lowest-to-highest objective value). Keep a record of the best solution.
   5. Use **round robin** method to divide the population into `n_complexes` complexes. This means member number `i` goes into complex number `i % n_complexes`. After this each new complex will have `m` members.
   6. Begin next iteration.

8. Report the best solution.

**CCE algorithm**

1. Do `b` breeding iterations to the given complex. (Recall that `b = m` in this implementation, meaning that we do as many breeding iterations as we have members). Each breeding iteration involves the following steps:
   1. Use **trapezoidal selection** method to select `p` members (called a simplex) from the current complex. In our implementation the simplex contains just over half of the complex members. Trapezoidal selection goes as follows:
      1. Make a new list of all the complex members, sorted by objective value from best to worst (lowest-to-highest). Calculate a selection weight for each of the complex members, `weight[i] = pow(n - i, elitism)`.
      2. Randomly select `p` members, without replacement, forming the simplex.
   2. Sort the members of the simplex from best to worst (lowest-to-highest). Make a note of the worst objective value in the simplex, `obj_worst`.
   3. Calculate the **centroid point** of the simplex while ignoring the worst member. That is, calculate the average value for each model parameter across all members except for the worst member.
   4. Calculate the **reflection point** by reflecting the worst point across the centroid (to the other side of the centroid).
   5. Calculate a **random point** by assigning completely random values to the parameters.
   6. Calculate the **contraction point** which is half way between the worst point and the centroid.
   7. Proposed params = reflection point. But if the reflection point is invalid (outside parameter bounds) then proposed params = random point. Evaluate the objective function. If `obj_proposed` is better than `obj_worst` replace it in the complex…
   8. … else proposed params = contraction point, and evaluate the objective function. If `obj_proposed` is better than `obj_worst` replace it in the complex…
   9. … else as a last resort calculate a **new random point** and by assigning completely random values to the parameters and evaluate the objective function. Regardless of whether `obj_proposed` is better than `obj_worst` replace it in the complex.

2. After `b` breeding iterations, return the updated complex to the main algorithm.

## Planned improvements

Fors definitely isn’t the endgame here, for example I know it becomes very inefficient late in the optimisation.

## Modern variations

- Chu Gao and Sorooshian did a paper in 2010. This is attached below.

  [Chu 2010 - Water Resources Research - Improving the shuffled complex evolution scheme for optimization of complex.pdf](../../../assets/docs-optimisation-algorithms-sce/Water_Resources_Research_-_2010_-_Chu_-_Improving_the_shuffled_complex_evolution_scheme_for_optimization_of_complex.pdf)

- Sorooshian did SC-SAHEL in 2020. It involves combining multiple evolutionary algorithms within a SCE framework… which is a lot of work to code up 😄 Shuffled Complex Self Adaptive Hybrid Evolution (SC-SAHEL)

- Some guys at Victoria University have made some improvements that look good when benchmarked against basic test functions.

  [Muttil 2008 - Shuffled Complex Evolution Model Calibrating Algorithm.pdf](../../../assets/docs-optimisation-algorithms-sce/08_-_Shuffled_Complex_Evolution_Model_Calibrating_Algor_.pdf)

## References

<https://www.mathworks.com/matlabcentral/fileexchange/7671-shuffled-complex-evolution-sce-ua-method>
