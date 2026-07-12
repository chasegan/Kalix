---
title: "Allocation systems"
---

# Allocation systems

## Overview

In most managed river systems, the right to water is separated from water itself. A ***water access entitlement*** is an ongoing right to a share of whatever water becomes available; a ***water allocation*** is the volume actually made available against that entitlement in a given season. An ***allocation system*** is the set of rules that determines and distributes the available water: each entitlement has an ***account***, which is credited when an allocation is made and debited when water is taken.

Everything else — however elaborate the water sharing plan — is detail layered onto that crediting and debiting.

## The named systems

Australian practice recognises several families of allocation system:

- ***Annual allocation*** — available water is assessed and announced as a percentage of entitlement, credited to accounts, and topped up if further inflows arrive. At the end of the water year, unused credits are forfeited and the pool is redistributed.

- ***Annual allocation with carryover*** — as above, but at year end each account is reduced to a *carryover limit* rather than zero. Variants add evaporation deductions on carried-over water, spill-forfeiture rules, or a second uncapped account holding the excess (Victoria's *spillable water accounts*).

- ***Continuous accounting*** — the end-of-year event is deleted. Account caps and use limits apply at all times, and old and new water are indistinguishable.

- ***Capacity sharing*** — instead of sharing releases, each user holds a share of the storage itself: a fraction of its capacity and a fraction of its inflows, bearing their share of losses, and deciding for themselves when to store and when to release. Nothing is announced, because there is nothing to announce. Queensland's *continuous sharing* schemes (St George, MacIntyre Brook) are the pragmatic, operating implementation.

- ***Opportunity systems*** — in unregulated rivers there is no public storage, so the allocation is the right to pump *if and when* flows occur, governed by commence- and cease-to-pump thresholds, daily and annual volumetric limits, and event announcements.

## A continuum, not a menu

These families are usually presented as distinct products. They are better understood as points on a continuum — ordered by *when* allocated water may be used (must-use-this-year through to no time constraint at all) and by *how individually* storage access is defined (pooled and manager-mediated through to fully individualised).

Real systems mix mechanisms freely. The interstate sharing of the River Murray — nominally "continuous accounting" — is simultaneously capacity sharing (each state owns half of the storages and inflows, with one state's surplus spilling internally to the other), continuous accounting (state accounts reconcile only when a share fills), and annual accounting (South Australia's fixed annual entitlement, and the annually reset loss reserve). The overall system is assembled from self-contained mechanisms.

## One kernel, many systems

Strip any volumetric allocation system to its mechanics and the same small set of dials appears:

| Dial | Typical settings |
|---|---|
| Credit policy | announced allocation from a resource assessment; share of inflow capped by share of storage; flow-event opportunity |
| Account cap | 100% of entitlement; 150% of entitlement; a capacity share |
| Use limits | annual caps; rolling multi-year caps |
| Events | end-of-year reset or reduce-to-carryover; spill forfeiture; internal spills; reconciliation |
| Loss treatment | socialised into reserves; attributed to balances; fixed delivery factors |
| Reserves & priorities | drought reserves and high-priority classes filled first — themselves just accounts with their own rules |

Every named system is a configuration of these dials, and every real-world variant — of which there are dozens across jurisdictions — is a renegotiation of a few settings, usually under drought pressure.

## Kalix's approach

This is why Kalix builds ***the dials, not the named systems***. Accounts, credit and debit policies, limits, triggered events, and resource assessments are small, orthogonal components; annual accounting, continuous accounting, and capacity sharing are configurations of them — as is the next system that a water manager invents, including systems that extend, merge, or fall between today's. A model should support water policy, not determine what policy is expressible.

Three commitments follow from Kalix's broader philosophy:

- ***Authored, not selected.*** Assessments, thresholds, and triggers are written as expressions and tables in the model file, legible and version-controllable — the model file reads like the water sharing plan.

- ***Auditable.*** Every credit, debit, forfeiture, and announcement is a recordable series. A modeller (or an entitlement holder) can replay the accounting and check it, line by line.

- ***Fast.*** Assessing an allocation policy means simulating whole-of-system reliability over century-scale climate sequences, across ensembles of options. Engine speed turns that from a project into a loop.

## Further reading

- Barma Water Resources et al. (2011), *Water allocation systems: exploring opportunities for reform*, Waterlines Report No 65, National Water Commission, Canberra.
- Hughes, N. & Goesch, T. (2009), *Capacity sharing in the St George and MacIntyre Brook irrigation schemes in southern Queensland*, ABARE research report 09.12, Canberra.
