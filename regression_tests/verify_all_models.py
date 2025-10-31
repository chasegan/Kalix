#!/usr/bin/env python3
"""
Automatically finds and verifies all Kalix model INI files against their mass balance reports.
"""

import os
import subprocess
import sys
from pathlib import Path


def find_model_files(root_dir):
    """Find all .ini files in the directory tree."""
    ini_files = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.ini'):
                ini_files.append(os.path.join(root, file))
    return sorted(ini_files)


def verify_model(model_path, mbal_filename='mbal_for_verification.txt'):
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
        # Run kalixcli with verification
        result = subprocess.run(
            ['kalixcli', 'sim', model_file, '-v', mbal_filename],
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

    # Allow optional command line argument for different root directory
    if len(sys.argv) > 1:
        root_dir = sys.argv[1]
    else:
        root_dir = script_dir

    print(f"Searching for model files in: {root_dir}")
    print("=" * 80)

    # Find all model files
    model_files = find_model_files(root_dir)

    if not model_files:
        print("No .ini files found!")
        sys.exit(1)

    print(f"Found {len(model_files)} model file(s)\n")

    # Verify each model
    results = []
    for model_path in model_files:
        rel_path = os.path.relpath(model_path, root_dir)
        print(f"Verifying: {rel_path}")

        success, message = verify_model(model_path)
        results.append((rel_path, success, message))

        if success:
            print(f"  ✓ {message}")
        else:
            print(f"  ✗ {message}")
        print()

    # Summary
    print("=" * 80)
    print("SUMMARY")
    print("=" * 80)

    passed = sum(1 for _, success, _ in results if success)
    failed = len(results) - passed

    print(f"Total: {len(results)}")
    print(f"Passed: {passed}")
    print(f"Failed: {failed}")

    if failed > 0:
        print("\nFailed models:")
        for rel_path, success, message in results:
            if not success:
                print(f"  - {rel_path}")
                print(f"    {message}")
        sys.exit(1)
    else:
        print("\n✓ All models verified successfully!")
        sys.exit(0)


if __name__ == '__main__':
    main()
