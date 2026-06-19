#!/usr/bin/env python3
"""
Automatically finds and verifies all Kalix model INI files against their mass balance reports.
"""

import difflib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

import numpy as np

# NOTE: `kalix` (the Python package) is imported lazily inside the package
# backend only, so the CLI backend works without the wheel installed.


def find_model_files(root_dir):
    """Find all .ini files in the directory tree."""
    ini_files = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.ini'):
                ini_files.append(os.path.join(root, file))
    return sorted(ini_files)


def get_numerical_content(line: str):
    match = re.search(r'[=,]\s*(-?\d+\.?\d*)\s*$', line)
    return float(match.group(1)) if match else None


def compare_mass_balance_files(file1, file2):
    """
    Compare two mass balance files for equality.

    Args:
        file1: Path to first mass balance file
        file2: Path to second mass balance file
        rtol: Relative tolerance (float comparison)
        atol: Absolute tolerance (float comparison)

    Returns:
        tuple: (match: bool, detail: str) — detail is '' on match, mismatch description otherwise
    """
    node_heading_pattern = re.compile(r'^\w+ NODES$')
    line_num = 0

    with open(file1, 'r') as f1, open(file2, 'r') as f2:
        while True:
            content1 = f1.readline()
            content2 = f2.readline()
            line_num += 1
            if not content1 and not content2:
                break  # end of both files
            if not content1 or not content2:
                return False, f"line {line_num}: files have different lengths"

            if node_heading_pattern.match(content1) and node_heading_pattern.match(content2):
                # Now in a group of nodes
                while True:
                    node_content1 = f1.readline()
                    node_content2 = f2.readline()
                    line_num += 1
                    if node_content1.strip() == '' and node_content2.strip() == '':
                        break  # end of this group of nodes
                    num1 = get_numerical_content(node_content1)
                    num2 = get_numerical_content(node_content2)
                    if num1 is None or num2 is None:
                        return False, f"line {line_num}: expected numerical content"
                    if not np.isclose(num1, num2, rtol=1e-5, atol=1e-8):
                        return False, f"line {line_num}: numerical mismatch {num1} vs {num2}"
            elif content1.startswith("TOTAL ="):
                num1 = get_numerical_content(content1)
                num2 = get_numerical_content(content2)
                if num1 is None or num2 is None:
                    return False, f"line {line_num}: expected numerical content"
                if not np.isclose(num1, num2, rtol=1e-5, atol=1e-13):
                    return False, f"line {line_num}: numerical mismatch {num1} vs {num2}"
            else:
                if content1 != content2:
                    return False, f"line {line_num}: {content1.rstrip()!r} != {content2.rstrip()!r}"
        return True, ''


def find_repo_root(start):
    """Walk up from `start` to find the repository root (Cargo.toml / .git)."""
    p = Path(start).resolve()
    for parent in [p, *p.parents]:
        if (parent / 'Cargo.toml').exists() or (parent / '.git').exists():
            return parent
    return None


def find_cli_binary():
    """Return the path to a locally-built kalix CLI binary, or None.

    Presence of this binary is what distinguishes a developer machine (where
    `cargo build --release` produces it) from CI (which only builds the Python
    wheel via maturin and never produces the root `kalix` bin target).
    """
    repo_root = find_repo_root(__file__)
    if repo_root is None:
        return None
    exe = 'kalix.exe' if os.name == 'nt' else 'kalix'
    candidate = repo_root / 'target' / 'release' / exe
    return candidate if candidate.exists() else None


def resolve_backend(explicit=None):
    """Decide which simulation backend to use.

    Returns (backend, cli_bin) where backend is 'cli' or 'package'. `cli_bin`
    is the Path to the CLI binary for the 'cli' backend, else None.

    With no explicit choice, auto-detect: prefer a locally-built CLI (the
    artifact a developer just compiled), falling back to the installed Python
    package (CI, where only the wheel exists).
    """
    cli_bin = find_cli_binary()
    if explicit == 'cli':
        if cli_bin is None:
            raise SystemExit(
                "--backend cli requested but no binary was found at "
                "target/release/kalix; build it with `cargo build --release`"
            )
        return 'cli', cli_bin
    if explicit == 'package':
        return 'package', None
    # auto
    if cli_bin is not None:
        return 'cli', cli_bin
    return 'package', None


def _simulate(backend, cli_bin, model_path, mass_balance_path):
    """Run a simulation via the chosen backend, writing the mass balance file."""
    if backend == 'cli':
        result = subprocess.run(
            [str(cli_bin), 'simulate', model_path, '-m', mass_balance_path],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or '').strip()
            raise RuntimeError(detail or f"kalix exited with code {result.returncode}")
    else:
        import kalix
        kalix.simulate(model_path, mass_balance=mass_balance_path)


def verify_model(model_path, backend, cli_bin, mbal_filename='mbal.txt'):
    """
    Verify a model against its mass balance report via the selected backend.

    Args:
        model_path: Path to the .ini model file
        backend: 'cli' or 'package' (see resolve_backend)
        cli_bin: Path to the CLI binary when backend == 'cli', else None
        mbal_filename: Name of the mass balance file to verify against

    Returns:
        tuple: (success: bool, message: str)
    """
    model_dir = os.path.dirname(model_path)
    mbal_path = os.path.join(model_dir, mbal_filename)

    # Check if reference mass balance file exists
    if not os.path.exists(mbal_path):
        return False, f"Mass balance file not found: {mbal_path}"

    try:
        # Create a temporary file for the new mass balance
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as tmp_mbal:
            tmp_mbal_path = tmp_mbal.name

        try:
            # Run simulation to generate new mass balance
            _simulate(backend, cli_bin, model_path, tmp_mbal_path)

            # Compare the generated mass balance with the reference
            matched, detail = compare_mass_balance_files(tmp_mbal_path, mbal_path)
            if matched:
                return True, "VERIFIED!"
            else:
                # Copy the generated file and write a diff for inspection
                shutil.copy(tmp_mbal_path, mbal_path + '.log')
                diff_path = mbal_path + '.diff'
                with open(mbal_path, 'r') as ref_f, open(tmp_mbal_path, 'r') as gen_f:
                    diff = difflib.unified_diff(
                        ref_f.readlines(), gen_f.readlines(),
                        fromfile='reference', tofile='generated',
                    )
                    with open(diff_path, 'w') as diff_f:
                        diff_f.writelines(diff)
                rel_diff = Path(diff_path).relative_to(Path.cwd()).as_posix()
                return False, f"Mass balance mismatch ({detail}): diff saved to {rel_diff}"
        finally:
            # Clean up temporary file
            if os.path.exists(tmp_mbal_path):
                os.unlink(tmp_mbal_path)
    except Exception as e:
        return False, f"Error: {str(e)}"


def _run_tests():

    def _tmp(s):
        f = tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False)
        f.write(s)
        f.close()
        return f.name

    def cmp(a, b):
        fa, fb = _tmp(a), _tmp(b)
        try:
            return compare_mass_balance_files(fa, fb)
        finally:
            os.unlink(fa)
            os.unlink(fb)

    assert get_numerical_content("flow, 1.23") == 1.23
    assert get_numerical_content("count = 42") == 42.0
    assert get_numerical_content("deficit = -3.14") == -3.14
    assert get_numerical_content("Node count: 14") is None
    assert get_numerical_content("heading") is None
    assert get_numerical_content("") is None

    assert cmp("line A\n", "line A\n") == (True, '')
    m, d = cmp("line A\n", "line B\n")
    assert not m and 'line 1' in d and 'line A' in d
    assert cmp("INLET NODES\nflow =, 1.0000010\n\n", "INLET NODES\nflow =, 1.0000020\n\n") == (True, '')
    m, d = cmp("INLET NODES\nflow =, 1.0\n\n", "INLET NODES\nflow =, 2.0\n\n")
    assert not m and '1.0' in d and '2.0' in d
    m, d = cmp("INLET NODES\nnot a number\n\n", "INLET NODES\nnot a number\n\n")
    assert not m and 'numerical' in d
    m, d = cmp("line A\n", "line A\nline B\n")
    assert not m and 'lengths' in d
    assert cmp("", "") == (True, '')

    print("All tests passed.")


def main():
    argv = sys.argv[1:]

    if '--test' in argv:
        _run_tests()
        sys.exit(0)

    # Optional --backend cli|package|auto override (default: auto-detect).
    backend_choice = None
    if '--backend' in argv:
        i = argv.index('--backend')
        try:
            value = argv[i + 1]
        except IndexError:
            sys.exit("--backend requires a value: cli, package, or auto")
        if value not in ('cli', 'package', 'auto'):
            sys.exit(f"Invalid --backend '{value}': choose cli, package, or auto")
        backend_choice = None if value == 'auto' else value
        del argv[i:i + 2]

    backend, cli_bin = resolve_backend(backend_choice)

    # Get the script's directory as the root search directory
    script_dir = os.path.dirname(os.path.abspath(__file__))
    log_file = os.path.join(script_dir, 'verify_all_models_log.txt')

    # Delete log file if it exists - a failed run should leave no file
    if os.path.exists(log_file):
        os.remove(log_file)

    # Collect output lines to write to log file at the end
    output_lines = []

    def log(message=''):
        """Print to screen and collect for log file."""
        print(message)
        output_lines.append(message)

    # Allow optional command line argument for different root directory
    if argv:
        root_dir = argv[0]
    else:
        root_dir = script_dir

    if backend == 'cli':
        log(f"Backend: CLI ({cli_bin})")
    else:
        log("Backend: Python package (kalix)")
    log(f"Searching for model files in: {root_dir}")
    log("=" * 80)

    # Find all model files
    model_files = find_model_files(root_dir)

    if not model_files:
        log("No .ini files found!")
        sys.exit(1)

    log(f"Found {len(model_files)} model file(s)\n")

    # Verify each model
    results = []
    for model_path in model_files:
        rel_path = os.path.relpath(model_path, root_dir)
        log(f"Verifying: {rel_path}")

        success, message = verify_model(model_path, backend, cli_bin)
        results.append((rel_path, success, message))

        if success:
            log(f"  [PASS] {message}")
        else:
            log(f"  [FAIL] {message}")
        log()

    # Summary
    log("=" * 80)
    log("SUMMARY")
    log("=" * 80)
    log(f"Timestamp: {datetime.now().isoformat()}")

    passed = sum(1 for _, success, _ in results if success)
    failed = len(results) - passed

    log(f"Total: {len(results)}")
    log(f"Passed: {passed}")
    log(f"Failed: {failed}")

    if failed > 0:
        log("\nFailed models:")
        for rel_path, success, message in results:
            if not success:
                log(f"  - {rel_path}")
                log(f"    {message}")
    else:
        log("\n[PASS] All models verified successfully!")

    # Write log file at the end (only if we got this far)
    with open(log_file, 'w') as f:
        f.write('\n'.join(output_lines) + '\n')

    sys.exit(1 if failed > 0 else 0)


if __name__ == '__main__':
    main()
