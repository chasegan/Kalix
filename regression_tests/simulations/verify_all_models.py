#!/usr/bin/env python3
"""
Automatically finds and verifies all Kalix model INI files against their mass balance reports.
"""

import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path


def find_model_files(root_dir):
    """Find all .ini files in the directory tree."""
    ini_files = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.ini'):
                ini_files.append(os.path.join(root, file))
    return sorted(ini_files)


def verify_model(model_path, mbal_filename='mbal.txt'):
    """
    Verify a model against its mass balance report.

    Args:
        model_path: Path to the .ini model file
        mbal_filename: Name of the mass balance file to verify against

    Returns:
        tuple: (success: bool, message: str)
    """
    model_dir = os.path.dirname(model_path)
    model_file = os.path.basename(model_path)
    mbal_path = os.path.join(model_dir, mbal_filename)

    # Check if mass balance file exists
    if not os.path.exists(mbal_path):
        return False, f"Mass balance file not found: {mbal_path}"

    try:
        # Run kalix with verification
        result = subprocess.run(
            ['kalix', 'simulate', model_file, '-v', mbal_filename],
            cwd=model_dir,
            capture_output=True,
            text=True,
            timeout=300  # 5 minute timeout
        )

        if result.returncode == 0:
            if 'VERIFIED!' in result.stdout:
                return True, "VERIFIED!"
            else:
                return False, f"Verification unclear: {result.stdout}"
        else:
            error_msg = result.stderr if result.stderr else result.stdout
            return False, f"Failed: {error_msg}"

    except subprocess.TimeoutExpired:
        return False, "Timeout: Model took too long to run"
    except Exception as e:
        return False, f"Error: {str(e)}"


def main():
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
            log(f"  ✓ {message}")
        else:
            log(f"  ✗ {message}")
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
        log("\n✓ All models verified successfully!")

    # Write log file at the end (only if we got this far)
    with open(log_file, 'w') as f:
        f.write('\n'.join(output_lines) + '\n')

    sys.exit(1 if failed > 0 else 0)


if __name__ == '__main__':
    main()
