#!/usr/bin/env python3
"""Render the simulation-speed figure for the Code page.

This is a standalone port of the FIRST plot in
``regression_tests/speed/speed_results_analysis.ipynb``
(``plot_metric_timeline("sim_min_ms", ...)``) — small multiples, one panel per
test, one line per machine — reading the same ``speed_results.csv``. Kept in
lockstep with that notebook: if the notebook's first plot changes, update here.

Writes ``website/docs/assets/speed-plot.png``. Safe to run on every build; if the
CSV is missing it leaves any committed fallback image untouched.
"""

import math
from pathlib import Path

import matplotlib
matplotlib.use("Agg")  # headless (CI / build)
import matplotlib.pyplot as plt
import pandas as pd

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
CSV = REPO_ROOT / "regression_tests" / "speed" / "speed_results.csv"
OUT = HERE.parent / "docs" / "assets" / "speed-plot.png"

SURFACE, INK, INK_2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e8e8e6"
PALETTE = ["#2a78d6", "#1baf7a", "#eda100", "#008300",
           "#4a3aa7", "#e34948", "#e87ba4", "#eb6834"]


def short_host(h):
    return h.split(".")[0]


def main() -> int:
    if not CSV.exists():
        print(f"[gen_speed_plot] {CSV} not found; leaving existing image.")
        return 0

    df = pd.read_csv(CSV, parse_dates=["test_date"])
    df["commit_date"] = pd.to_datetime(df["commit_date"], utc=True, format="ISO8601")
    df = df.sort_values(["commit_date", "test_date"])

    tests = list(df.drop_duplicates("test_name")["test_name"])
    machines = list(df.drop_duplicates("hostname")["hostname"])
    machine_colours = {m: PALETTE[i % len(PALETTE)] for i, m in enumerate(machines)}

    runs = (df[["commit_date", "test_date", "commit_sha", "commit_dirty", "kalix_version"]]
            .drop_duplicates(subset=["commit_date", "test_date"])
            .sort_values(["commit_date", "test_date"])
            .reset_index(drop=True))
    runs["run_id"] = runs.index + 1
    runs["run_label"] = runs.apply(
        lambda r: f"{r.commit_sha}{'*' if r.commit_dirty else ''}\n"
                  f"v{r.kalix_version}\n{r.commit_date:%d %b %y}", axis=1)
    df = df.merge(runs[["commit_date", "test_date", "run_id"]],
                  on=["commit_date", "test_date"])

    def style_axes(ax, title=None):
        ax.set_facecolor(SURFACE)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(GRID)
        ax.grid(axis="y", color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        ax.tick_params(colors=INK_2, labelsize=9)
        if title:
            ax.set_title(title, loc="left", fontsize=11, color=INK)

    def apply_run_axis(ax, max_ticks=8):
        step = max(1, math.ceil(len(runs) / max_ticks))
        ax.set_xticks(runs.run_id[::step], runs.run_label[::step], fontsize=7.5)
        ax.set_xlim(0.5, len(runs) + 0.5)

    def machine_legend(fig):
        if len(machines) > 1:
            from matplotlib.lines import Line2D
            handles = [Line2D([], [], color=machine_colours[m], linewidth=2,
                              label=short_host(m)) for m in machines]
            fig.legend(handles=handles, frameon=False, fontsize=9, labelcolor=INK_2,
                       ncols=min(len(machines), 5), loc="upper right")

    metric = "sim_min_ms"
    n_rows = math.ceil(len(tests) / 2)
    fig, axes = plt.subplots(n_rows, 2, figsize=(11, 3 * n_rows), facecolor=SURFACE,
                             sharex=True, squeeze=False)
    for ax in axes.flat[len(tests):]:
        ax.set_visible(False)

    for ax, test in zip(axes.flat, tests):
        d = df[df.test_name == test]
        style_axes(ax, test)
        for machine in machines:
            dm = d[d.hostname == machine]
            if dm.empty:
                continue
            colour = machine_colours[machine]
            ax.plot(dm.run_id, dm[metric], color=colour, linewidth=2, zorder=2)
            clean = dm[dm.commit_dirty == False]
            dirty = dm[dm.commit_dirty == True]
            ax.plot(clean.run_id, clean[metric], "o", color=colour, markersize=5, zorder=3)
            ax.plot(dirty.run_id, dirty[metric], "o", markerfacecolor=SURFACE,
                    markeredgecolor=colour, markersize=5, zorder=3)
        ax.set_ylim(bottom=0)
        apply_run_axis(ax)

    machine_legend(fig)
    fig.supxlabel("benchmark run (commit sha / version / commit date; * = dirty tree)",
                  fontsize=10, color=INK_2)
    fig.supylabel("simulation time, min of repeats (ms)", fontsize=10, color=INK_2)
    fig.suptitle("Simulation time per test — all machines",
                 x=0.01, ha="left", fontsize=13, color=INK)
    fig.tight_layout(rect=(0.01, 0, 1, 0.97))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT, dpi=140, facecolor=SURFACE)
    plt.close(fig)
    print(f"[gen_speed_plot] wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
