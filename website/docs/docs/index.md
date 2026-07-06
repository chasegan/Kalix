---
title: Documentation
---

# Documentation

Model components, top to bottom.

<div class="kx-docs-hub" markdown>

<!-- SPINE: top zone -->
<div style="display: grid; grid-template-columns: 190px 1fr; gap: 14px 32px; align-items: center; border-left: 2px solid var(--kx-border); padding: 4px 0 34px 34px; margin-left: 6px; position: relative;">
<span style="position: absolute; left: -8px; top: 6px; width: 14px; height: 14px; border-radius: 4px; background: var(--kx-accent-600); border: 3px solid var(--kx-bg);"></span>
<a class="kx-section-chip" href="concepts/model-file-structure/" style="justify-self: start; font-family: var(--kx-font-mono); font-size: 14px; color: var(--kx-primary-700); text-decoration: none; background: var(--kx-primary-50); border: 1px solid var(--kx-primary-200); border-radius: var(--kx-radius-sm); padding: 8px 14px;">[kalix]</a>
<span style="font-size: 14.5px; color: var(--kx-text-muted); line-height: 1.5;">Run settings — timestep, start and end dates, model version.</span>
<a class="kx-section-chip" href="concepts/input-data/" style="justify-self: start; font-family: var(--kx-font-mono); font-size: 14px; color: var(--kx-primary-700); text-decoration: none; background: var(--kx-primary-50); border: 1px solid var(--kx-primary-200); border-radius: var(--kx-radius-sm); padding: 8px 14px;">[inputs]</a>
<span style="font-size: 14.5px; color: var(--kx-text-muted); line-height: 1.5;">Input data series the model can reference.</span>
<a class="kx-section-chip" href="concepts/dynamic-expressions/constants/" style="justify-self: start; font-family: var(--kx-font-mono); font-size: 14px; color: var(--kx-primary-700); text-decoration: none; background: var(--kx-primary-50); border: 1px solid var(--kx-primary-200); border-radius: var(--kx-radius-sm); padding: 8px 14px;">[constants]</a>
<span style="font-size: 14.5px; color: var(--kx-text-muted); line-height: 1.5;">Named fixed values reused across nodes.</span>
</div>

<!-- SPINE: nodes zone -->
<div style="display: grid; grid-template-columns: 190px 1fr; gap: 32px; align-items: start; border-left: 2px solid var(--kx-border); padding: 4px 0 34px 34px; margin-left: 6px; position: relative;">
<span style="position: absolute; left: -8px; top: 6px; width: 14px; height: 14px; border-radius: 4px; background: var(--kx-primary-600); border: 3px solid var(--kx-bg);"></span>
<div style="display: flex; flex-direction: column; gap: 10px;">
<a class="kx-section-chip" href="nodes/" style="align-self: start; font-family: var(--kx-font-mono); font-size: 14px; color: var(--kx-primary-700); text-decoration: none; background: var(--kx-primary-50); border: 1px solid var(--kx-primary-200); border-radius: var(--kx-radius-sm); padding: 8px 14px;">[node.*]</a>
<span style="font-size: 13px; color: var(--kx-text-subtle); line-height: 1.5; max-width: 150px;">The network — 13 node types.</span>
</div>
<div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px;" markdown="0">
<a class="kx-hover-card" href="nodes/inflow/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #4a9dae; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">In</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Inflow</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Flow into the network</span></span></a>
<a class="kx-hover-card" href="nodes/gr4j/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #3d8f6a; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Rn</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Runoff</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">GR4J · Sacramento</span></span></a>
<a class="kx-hover-card" href="nodes/storage/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: var(--kx-primary-600); color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">St</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Storage</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Reservoirs &amp; weirs</span></span></a>
<a class="kx-hover-card" href="nodes/routing/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #4a9dae; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Rt</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Routing</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Channel routing</span></span></a>
<a class="kx-hover-card" href="nodes/confluence/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #4a9dae; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Cf</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Confluence</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Merge branches</span></span></a>
<a class="kx-hover-card" href="nodes/splitter/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #4a9dae; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Sp</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Splitter</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Split flow</span></span></a>
<a class="kx-hover-card" href="nodes/loss/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #c68a2e; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Lo</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Loss</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Channel losses</span></span></a>
<a class="kx-hover-card" href="nodes/regulated-user/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #e08a3c; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">RU</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Regulated User</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Ordered extraction</span></span></a>
<a class="kx-hover-card" href="nodes/unregulated-user/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #e08a3c; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">UU</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Unregulated User</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Capped extraction</span></span></a>
<a class="kx-hover-card" href="nodes/gauge/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: #d24b3f; color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Ga</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Gauge</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Record flow</span></span></a>
<a class="kx-hover-card" href="nodes/order-control/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: var(--kx-accent-600); color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">OC</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Order Control</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Manage orders</span></span></a>
<a class="kx-hover-card" href="nodes/blackhole/" style="display: flex; align-items: center; gap: 10px; text-decoration: none; border: 1px solid var(--kx-border); border-radius: var(--kx-radius-md); padding: 12px 14px;"><span style="width: 26px; height: 26px; border-radius: 6px; background: var(--kx-neutral-700); color: #fff; font-family: var(--kx-font-mono); font-size: 11px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex: none;">Bh</span><span><span style="font-size: 13.5px; font-weight: 600; color: var(--kx-text); display: block;">Blackhole</span><span style="font-size: 11.5px; color: var(--kx-text-muted);">Terminate flow</span></span></a>
</div>
</div>

<!-- SPINE: bottom zone -->
<div style="display: grid; grid-template-columns: 190px 1fr; gap: 32px; align-items: center; border-left: 2px solid var(--kx-border); padding: 4px 0 6px 34px; margin-left: 6px; position: relative;">
<span style="position: absolute; left: -8px; top: 6px; width: 14px; height: 14px; border-radius: 4px; background: var(--kx-accent-600); border: 3px solid var(--kx-bg);"></span>
<a class="kx-section-chip" href="concepts/model-outputs/" style="justify-self: start; font-family: var(--kx-font-mono); font-size: 14px; color: var(--kx-primary-700); text-decoration: none; background: var(--kx-primary-50); border: 1px solid var(--kx-primary-200); border-radius: var(--kx-radius-sm); padding: 8px 14px;">[outputs]</a>
<span style="font-size: 14.5px; color: var(--kx-text-muted); line-height: 1.5;">Results and series written out at the end of a run.</span>
</div>

<!-- USING KALIX -->
<div style="margin-top: 52px; border-top: 1px solid var(--kx-border); padding-top: 40px;">
<p style="margin: 0 0 22px; font-family: var(--kx-font-mono); font-size: 12px; letter-spacing: var(--kx-tracking-caps); text-transform: uppercase; color: var(--kx-primary-600);">Using Kalix</p>
<div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px;">
<div style="background: var(--kx-surface); border: 1px solid var(--kx-border); border-radius: var(--kx-radius-lg); padding: 26px;">
<div style="width: 38px; height: 38px; border-radius: var(--kx-radius-sm); background: var(--kx-primary-50); display: flex; align-items: center; justify-content: center; font-family: var(--kx-font-mono); font-size: 15px; color: var(--kx-primary-600); margin-bottom: 16px;">▦</div>
<h3 style="margin: 0 0 6px; font-size: 18px; font-weight: 600;">KalixIDE</h3>
<p style="margin: 0 0 16px; font-size: 13.5px; line-height: 1.55; color: var(--kx-text-muted);">The desktop modelling environment — editor, map, plots and runs.</p>
<span style="display: flex; flex-direction: column; gap: 8px; font-size: 13.5px;"><a href="using/ide/" style="color: var(--kx-primary-700); text-decoration: none;">Overview</a><a href="using/ide/" style="color: var(--kx-primary-700); text-decoration: none;">Editor &amp; linting</a><a href="using/ide/" style="color: var(--kx-primary-700); text-decoration: none;">Plotting &amp; stats</a><a href="using/run-manager/" style="color: var(--kx-primary-700); text-decoration: none;">Run Manager</a></span>
</div>
<div style="background: var(--kx-surface); border: 1px solid var(--kx-border); border-radius: var(--kx-radius-lg); padding: 26px;">
<div style="width: 38px; height: 38px; border-radius: var(--kx-radius-sm); background: var(--kx-primary-50); display: flex; align-items: center; justify-content: center; font-family: var(--kx-font-mono); font-size: 15px; color: var(--kx-primary-600); margin-bottom: 16px;">›_</div>
<h3 style="margin: 0 0 6px; font-size: 18px; font-weight: 600;">Kalix CLI</h3>
<p style="margin: 0 0 16px; font-size: 13.5px; line-height: 1.55; color: var(--kx-text-muted);">Run models headless from the commandline — scriptable and CI-ready.</p>
<span style="display: flex; flex-direction: column; gap: 8px; font-size: 13.5px;"><a href="using/cli/" style="color: var(--kx-primary-700); text-decoration: none;">Overview</a><a href="using/cli/" style="color: var(--kx-primary-700); text-decoration: none;">Running models</a><a href="using/cli/" style="color: var(--kx-primary-700); text-decoration: none;">Benchmarking</a><a href="optimisation/" style="color: var(--kx-primary-700); text-decoration: none;">Optimisation</a></span>
</div>
<div style="background: var(--kx-surface); border: 1px solid var(--kx-border); border-radius: var(--kx-radius-lg); padding: 26px;">
<div style="width: 38px; height: 38px; border-radius: var(--kx-radius-sm); background: var(--kx-accent-50); display: flex; align-items: center; justify-content: center; font-family: var(--kx-font-mono); font-size: 15px; color: var(--kx-accent-600); margin-bottom: 16px;">py</div>
<h3 style="margin: 0 0 6px; font-size: 18px; font-weight: 600;">Kalix in Python</h3>
<p style="margin: 0 0 16px; font-size: 13.5px; line-height: 1.55; color: var(--kx-text-muted);">Drive Kalix from a notebook or script via the Python package.</p>
<span style="display: flex; flex-direction: column; gap: 8px; font-size: 13.5px;"><a href="using/python/" style="color: var(--kx-primary-700); text-decoration: none;">Overview</a><a href="using/python/" style="color: var(--kx-primary-700); text-decoration: none;">Loading &amp; running</a><a href="optimisation/" style="color: var(--kx-primary-700); text-decoration: none;">Optimisation</a><a href="using/python/" style="color: var(--kx-primary-700); text-decoration: none;">Pixie datasets</a></span>
</div>
</div>
</div>

<!-- DEEP TOPICS -->
<div style="margin-top: 52px; border-top: 1px solid var(--kx-border); padding-top: 40px;">
<h2 style="margin: 0 0 6px; font-size: 24px; font-weight: 600; letter-spacing: -0.01em;">Deep topics</h2>
<p style="margin: 0 0 24px; font-size: 15px; color: var(--kx-text-muted);">Concepts, optimisation, reference and developer material.</p>
<div class="kx-tree" style="background: var(--kx-neutral-900); border-radius: var(--kx-radius-md); padding: 24px 28px; font-family: var(--kx-font-mono); font-size: 13.5px; color: var(--kx-neutral-300); line-height: 2.05; overflow-x: auto;">
<div style="color: var(--kx-accent-300);">docs/</div>
<div><span style="color: var(--kx-neutral-500);">├─ </span><span style="color: #fff;">concepts/</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="concepts/model-file-structure/" style="color: var(--kx-primary-200);">Model File Structure</a> &nbsp;<span style="color: var(--kx-neutral-500);">how a .ini model is organised</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="concepts/conventions/" style="color: var(--kx-primary-200);">Conventions</a> &nbsp;<span style="color: var(--kx-neutral-500);">naming, units and sign rules</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="concepts/ordering/" style="color: var(--kx-primary-200);">Ordering</a> &nbsp;<span style="color: var(--kx-neutral-500);">how orders propagate upstream</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="concepts/dynamic-expressions/" style="color: var(--kx-primary-200);">Dynamic Expressions</a> &nbsp;<span style="color: var(--kx-neutral-500);">formulas in model properties</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="concepts/parameter-types/" style="color: var(--kx-primary-200);">Parameter types</a> &nbsp;<span style="color: var(--kx-neutral-500);">scalars, series and tables</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;└─ </span><a href="concepts/data-cache/" style="color: var(--kx-primary-200);">Data cache</a> &nbsp;<span style="color: var(--kx-neutral-500);">how results are stored and reused</span></div>
<div><span style="color: var(--kx-neutral-500);">├─ </span><span style="color: #fff;">optimisation/</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="optimisation/objective-functions/" style="color: var(--kx-primary-200);">Objective functions</a> &nbsp;<span style="color: var(--kx-neutral-500);">defining fitness terms</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;└─ </span><a href="optimisation/algorithms/sce/" style="color: var(--kx-primary-200);">Algorithms</a> &nbsp;<span style="color: var(--kx-neutral-500);">SCE, CMA-ES, DE, DREAM</span></div>
<div><span style="color: var(--kx-neutral-500);">├─ </span><span style="color: #fff;">reference/</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;├─ </span><a href="reference/glossary/" style="color: var(--kx-primary-200);">Glossary</a> &nbsp;<span style="color: var(--kx-neutral-500);">terms and definitions</span></div>
<div><span style="color: var(--kx-neutral-500);">│&nbsp;&nbsp;└─ </span><a href="reference/technical/gr4j/" style="color: var(--kx-primary-200);">Technical Reference</a> &nbsp;<span style="color: var(--kx-neutral-500);">GR4J, Sacramento, solvers</span></div>
<div><span style="color: var(--kx-neutral-500);">└─ </span><span style="color: #fff;">developing/</span></div>
<div><span style="color: var(--kx-neutral-500);">&nbsp;&nbsp;&nbsp;├─ </span><a href="developing/start-developing/" style="color: var(--kx-primary-200);">Start Developing</a> &nbsp;<span style="color: var(--kx-neutral-500);">build Kalix from source</span></div>
<div><span style="color: var(--kx-neutral-500);">&nbsp;&nbsp;&nbsp;├─ </span><a href="developing/dev-stack/" style="color: var(--kx-primary-200);">The Dev Stack</a> &nbsp;<span style="color: var(--kx-neutral-500);">Rust core and tooling</span></div>
<div><span style="color: var(--kx-neutral-500);">&nbsp;&nbsp;&nbsp;└─ </span><a href="developing/gory-details/" style="color: var(--kx-primary-200);">Gory Details</a> &nbsp;<span style="color: var(--kx-neutral-500);">architecture decision records</span></div>
</div>
</div>

</div>
