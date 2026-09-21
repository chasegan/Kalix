#!/usr/bin/env python3
"""
Runs the Kalix speed test suite.

Automatically finds every numbered subdirectory containing a kalix.ini, runs it
several times with the release CLI binary, and reports per-phase timing
statistics (loading / simulation / output / total, as printed by `kalix sim -p`).

Results are printed as a table and appended to speed_results.csv — a long-term
database (one row per test per run) recording the commit, environment, and
per-phase metrics, so the team can track performance drift across months of
development. Rows from a dirty working tree are flagged (commit_dirty) so drift
analysis can filter them.

Interpretation: prefer the MIN columns when comparing engine changes — they are
the least contaminated by OS scheduling noise. Medians are shown for context.
Numbers are only comparable within one machine (hostname).

Usage:
    ./run_speed_tests.py                          # run all tests
    ./run_speed_tests.py 2                        # only tests starting with "2"
    ./run_speed_tests.py --binary B --commit REF  # measure a historical build,
                                                  # attributing rows to REF
                                                  # (e.g. a release tag)
"""

import argparse
import csv
import getpass
import json
import platform
import re
import statistics
import subprocess
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


def run_cmd(args, cwd=HERE):
    try:
        return subprocess.run(args, cwd=cwd, capture_output=True, text=True).stdout.strip()
    except OSError:
        return ""


def cpu_class():
    """Best-effort CPU model string, per platform."""
    system = platform.system()
    if system == "Darwin":
        return run_cmd(["sysctl", "-n", "machdep.cpu.brand_string"]) or platform.processor()
    if system == "Linux":
        try:
            with open("/proc/cpuinfo") as f:
                for line in f:
                    if line.startswith("model name"):
                        return line.split(":", 1)[1].strip()
        except OSError:
            pass
    return platform.processor() or platform.machine()


def environment_info(commit_ref=None):
    """Identity + environment fields recorded with every result row.

    With `commit_ref` (e.g. a release tag, for measuring a historical build
    via --binary), the commit fields describe that ref — assumed clean — and
    kalix_version is read from the ref's VERSION file.
    """
    if commit_ref:
        # ^{commit} peels annotated tags to the commit they point at (a bare
        # tag ref would otherwise yield the tag object's sha and header text).
        commit = f"{commit_ref}^{{commit}}"
        sha = run_cmd(["git", "rev-parse", "--short", commit]) or "unknown"
        dirty = False
        commit_date = run_cmd(["git", "log", "-1", "--format=%cI", commit]) or "unknown"
        kalix_version = run_cmd(["git", "show", f"{commit_ref}:VERSION"]) or "unknown"
    else:
        sha = run_cmd(["git", "rev-parse", "--short", "HEAD"]) or "unknown"
        dirty = bool(run_cmd(["git", "status", "--porcelain"]))
        commit_date = run_cmd(["git", "show", "-s", "--format=%cI", "HEAD"]) or "unknown"
        version_file = HERE.parent.parent / "VERSION"
        try:
            kalix_version = version_file.read_text().strip()
        except OSError:
            kalix_version = "unknown"
    return {
        "test_date": datetime.now().isoformat(timespec="seconds"),
        "commit_sha": sha,
        "commit_dirty": str(dirty).lower(),
        "commit_date": commit_date,
        "kalix_version": kalix_version,
        "user": run_cmd(["git", "config", "user.name"]) or getpass.getuser(),
        "hostname": platform.node(),
        "os": f"{platform.system()} {platform.release()} {platform.machine()}",
        "cpu_class": cpu_class(),
        "rustc_version": run_cmd(["rustc", "--version"]) or "unknown",
    }


RESULTS_CSV = HERE / "speed_results.csv"
CSV_FIELDS = [
    "test_date", "commit_sha", "commit_dirty", "commit_date", "kalix_version",
    "user", "hostname", "os", "cpu_class", "rustc_version",
    "test_name", "repeats",
    "sim_min_ms", "sim_median_ms", "sim_sd_ms",
    "load_min_ms", "output_min_ms", "total_min_ms",
]


def append_results(rows):
    """Append result rows to the long-term CSV database (header if new file)."""
    is_new = not RESULTS_CSV.exists()
    with open(RESULTS_CSV, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        if is_new:
            writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description="Run the Kalix speed test suite.")
    parser.add_argument("filter", nargs="?", help="only run tests whose folder starts with this")
    parser.add_argument("--binary", help="path to a kalix binary (default: target/release/kalix)")
    parser.add_argument("--commit", help="git ref the binary was built from (required with --binary)")
    args = parser.parse_args()

    if bool(args.binary) != bool(args.commit):
        raise SystemExit("--binary and --commit must be used together, so rows "
                         "are attributed to the right commit.")

    binary = Path(args.binary) if args.binary else find_release_binary()
    if not binary.exists():
        raise SystemExit(f"Binary not found: {binary}")
    tests = find_tests(args.filter)
    if not tests:
        raise SystemExit("No speed tests found.")

    env = environment_info(commit_ref=args.commit)
    commit = env["commit_sha"] + ("+dirty" if env["commit_dirty"] == "true" else "")
    print(f"Kalix speed tests | commit {commit} | {env['hostname']} | {env['test_date']}\n")
    header = (f"{'test':26s} {'n':>3s}  "
              f"{'sim min':>9s} {'sim med':>9s} {'sim sd':>7s}  "
              f"{'load min':>9s} {'out min':>9s} {'total min':>10s}")
    print(header)
    print("-" * len(header))

    rows = []
    with tempfile.TemporaryDirectory() as tmp_dir:
        for test_dir in tests:
            s = run_test(binary, test_dir, tmp_dir)
            print(f"{test_dir.name:26s} {s['repeats']:3d}  "
                  f"{s['sim']['min']:8.1f}m {s['sim']['median']:8.1f}m {s['sim']['sd']:6.1f}m  "
                  f"{s['load']['min']:8.1f}m {s['output']['min']:8.1f}m {s['total']['min']:9.1f}m")
            rows.append({
                **env,
                "test_name": test_dir.name,
                "repeats": s["repeats"],
                "sim_min_ms": f"{s['sim']['min']:.1f}",
                "sim_median_ms": f"{s['sim']['median']:.1f}",
                "sim_sd_ms": f"{s['sim']['sd']:.2f}",
                "load_min_ms": f"{s['load']['min']:.1f}",
                "output_min_ms": f"{s['output']['min']:.1f}",
                "total_min_ms": f"{s['total']['min']:.1f}",
            })

    append_results(rows)
    print(f"\nAppended {len(rows)} row(s) to speed_results.csv (units: ms).")


if __name__ == "__main__":
    main()
