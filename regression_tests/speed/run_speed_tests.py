#!/usr/bin/env python3
"""
Runs the Kalix speed test suite.

Automatically finds every numbered subdirectory containing a kalix.ini, runs it
several times with the release CLI binary, and reports per-phase timing
statistics (loading / simulation / output / total, as printed by `kalix sim -p`).

Results are printed as a table and appended to speed_log.txt together with the
git commit and machine identity, so the log accumulates a timing history for
this machine across engine changes.

Interpretation: prefer the MIN column when comparing engine changes — it is the
least contaminated by OS scheduling noise. Medians are shown for context.
Numbers are only comparable within one machine.

Usage:
    ./run_speed_tests.py             # run all tests
    ./run_speed_tests.py 2           # run only tests whose folder starts with "2"
"""

import json
import platform
import re
import statistics
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).parent
DEFAULT_REPEATS = 5
WARMUP_RUNS = 1

PROFILE_PATTERN = re.compile(
    r"Loading time:\s+([\d.]+) ms.*?"
    r"Simulation time:\s+([\d.]+) ms.*?"
    r"Output time:\s+([\d.]+) ms.*?"
    r"Total time:\s+([\d.]+) ms",
    re.DOTALL,
)


def find_release_binary():
    """Speed tests are only meaningful against the release build."""
    repo_root = HERE.parent.parent
    exe = "kalix.exe" if platform.system() == "Windows" else "kalix"
    binary = repo_root / "target" / "release" / exe
    if not binary.exists():
        raise SystemExit(
            "No release binary at target/release/kalix. Speed tests require a "
            "release build: run `cargo build --release` first."
        )
    return binary


def find_tests(name_filter=None):
    tests = []
    for d in sorted(HERE.iterdir()):
        if d.is_dir() and (d / "kalix.ini").exists():
            if name_filter and not d.name.startswith(name_filter):
                continue
            tests.append(d)
    return tests


def run_once(binary, test_dir, output_path):
    result = subprocess.run(
        [str(binary), "sim", "kalix.ini", "-p", "-o", str(output_path)],
        cwd=test_dir, capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"{test_dir.name}: kalix sim failed:\n{result.stdout}\n{result.stderr}"
        )
    match = PROFILE_PATTERN.search(result.stdout)
    if not match:
        raise RuntimeError(f"{test_dir.name}: could not parse profile output")
    load, sim, output, total = (float(g) for g in match.groups())
    return {"load": load, "sim": sim, "output": output, "total": total}


def run_test(binary, test_dir, tmp_dir):
    config_path = test_dir / "bench.json"
    repeats = DEFAULT_REPEATS
    if config_path.exists():
        repeats = json.loads(config_path.read_text()).get("repeats", DEFAULT_REPEATS)

    output_path = Path(tmp_dir) / f"{test_dir.name}_out.csv"
    for _ in range(WARMUP_RUNS):
        run_once(binary, test_dir, output_path)

    runs = [run_once(binary, test_dir, output_path) for _ in range(repeats)]
    stats = {}
    for phase in ("load", "sim", "output", "total"):
        values = [r[phase] for r in runs]
        stats[phase] = {
            "min": min(values),
            "median": statistics.median(values),
            "mean": statistics.mean(values),
            "sd": statistics.stdev(values) if len(values) > 1 else 0.0,
        }
    stats["repeats"] = repeats
    return stats


def git_commit():
    try:
        commit = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=HERE, capture_output=True, text=True,
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=HERE, capture_output=True, text=True,
        ).stdout.strip()
        return commit + ("+dirty" if dirty else "")
    except OSError:
        return "unknown"


def main():
    name_filter = sys.argv[1] if len(sys.argv) > 1 else None
    binary = find_release_binary()
    tests = find_tests(name_filter)
    if not tests:
        raise SystemExit("No speed tests found.")

    commit = git_commit()
    machine = f"{platform.node()} {platform.machine()}"
    timestamp = datetime.now().isoformat(timespec="seconds")

    print(f"Kalix speed tests | commit {commit} | {machine} | {timestamp}\n")
    header = (f"{'test':26s} {'n':>3s}  "
              f"{'sim min':>9s} {'sim med':>9s} {'sim sd':>7s}  "
              f"{'load min':>9s} {'out min':>9s} {'total min':>10s}")
    print(header)
    print("-" * len(header))

    log_lines = [f"\n[{timestamp}] commit={commit} machine={machine}"]
    with tempfile.TemporaryDirectory() as tmp_dir:
        for test_dir in tests:
            s = run_test(binary, test_dir, tmp_dir)
            print(f"{test_dir.name:26s} {s['repeats']:3d}  "
                  f"{s['sim']['min']:8.1f}m {s['sim']['median']:8.1f}m {s['sim']['sd']:6.1f}m  "
                  f"{s['load']['min']:8.1f}m {s['output']['min']:8.1f}m {s['total']['min']:9.1f}m")
            log_lines.append(
                f"  {test_dir.name}: n={s['repeats']} "
                f"sim_min={s['sim']['min']:.1f} sim_median={s['sim']['median']:.1f} "
                f"sim_sd={s['sim']['sd']:.2f} load_min={s['load']['min']:.1f} "
                f"output_min={s['output']['min']:.1f} total_min={s['total']['min']:.1f}"
            )

    with open(HERE / "speed_log.txt", "a") as f:
        f.write("\n".join(log_lines) + "\n")
    print("\nAppended results to speed_log.txt (units: ms).")


if __name__ == "__main__":
    main()
