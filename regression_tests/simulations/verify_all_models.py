#!/usr/bin/env python3
"""
Automatically finds and verifies all Kalix model INI files against their mass balance reports.
"""

import difflib
import os
import sys
import tempfile
from datetime import datetime
from pathlib import Path
import shutil
import numpy as np

import kalix
import re


def find_model_files(root_dir):
    """Find all .ini files in the directory tree."""
    ini_files = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.ini'):
                ini_files.append(os.path.join(root, file))
    return sorted(ini_files)


def get_numerical_content(line: str):
    parts = line.split("=, ")
    for part in parts:
        stripped = part.strip()
        if stripped.replace('.', '', 1).isdigit():
            return float(stripped)
    return None


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


def verify_model(model_path, mbal_filename='mbal.txt'):
    """
    Verify a model against its mass balance report using kalix.sim.simulate().

    Args:
        model_path: Path to the .ini model file
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
            kalix.simulate(
                model_path,
                mass_balance=tmp_mbal_path
            )

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
    if len(sys.argv) > 1 and sys.argv[1] == '--test':
        _run_tests()
        sys.exit(0)

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
    if len(sys.argv) > 1:
        root_dir = sys.argv[1]
    else:
        root_dir = script_dir

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

        success, message = verify_model(model_path)
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
