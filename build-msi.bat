@echo off
setlocal enabledelayedexpansion

:: ---------------------------------------------------------------------------
:: Build a Windows MSI installer from the portable app-image that
:: build-portable-zip.bat already assembled.
::
:: Why an MSI (do not "simplify" to a zip): the target site runs Ivanti
:: Application Control with Trusted Ownership. Files extracted from a zip by a
:: standard user are owned by that (untrusted) user and get blocked. An MSI
:: installed elevated is written by the Windows Installer service running as
:: SYSTEM, so the files end up SYSTEM-owned and pass whitelisting.
::
:: This is PER-MACHINE (jpackage's default -- no per-user flag) so elevation is
:: required and SYSTEM owns the files. We consume the existing app-image
:: (dist\KalixIDE-Windows-%VERSION%), which already contains kalix.exe, so the
:: MSI ships byte-identical contents to the portable zip.
::
:: Requires: jpackage (ships with JDK 14+) and WiX Toolset 3.x (candle.exe /
:: light.exe on PATH).
::
:: Run AFTER build-portable-zip.bat -- it depends on that script's output.
:: ---------------------------------------------------------------------------

:: Read version from VERSION file
set /p VERSION=<VERSION

:: jpackage --app-version requires N[.N[.N]] with no pre-release suffix.
:: Strip anything from the first '-' onward (e.g. 0.3.0-rc1 -> 0.3.0).
for /f "tokens=1 delims=-" %%v in ("%VERSION%") do set MSI_VERSION=%%v

set APP_IMAGE=dist\KalixIDE-Windows-%VERSION%
set ASSET_NAME=KalixIDE-Windows-%VERSION%.msi

echo ========================================
echo Building Kalix MSI v%MSI_VERSION%
echo ========================================

:: Preflight: jpackage must be available
where jpackage >nul 2>&1
if errorlevel 1 (
    echo ERROR: jpackage was not found on PATH ^(needs JDK 14+^)
    exit /b 1
)

if not exist "%APP_IMAGE%\KalixIDE.exe" (
    echo ERROR: app-image not found at %APP_IMAGE%
    echo Run build-portable-zip.bat first.
    exit /b 1
)

:: Package the existing app-image into a per-machine MSI.
::   --win-dir-chooser  lets the installing admin pick the directory (incl. a
::                      non-Program-Files / already-whitelisted folder).
::   No --win-per-user-install: per-machine is required for SYSTEM ownership.
jpackage ^
  --type msi ^
  --name KalixIDE ^
  --app-version %MSI_VERSION% ^
  --app-image "%APP_IMAGE%" ^
  --dest dist ^
  --vendor Kalix ^
  --copyright "Copyright 2024-2026 Kalix" ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut
if errorlevel 1 exit /b 1

:: jpackage names the output <name>-<app-version>.msi. Rename to the
:: full-version asset name so it parallels the portable zip and survives
:: pre-release suffixes (which are legal in the zip name but not the MSI version).
if not exist "dist\KalixIDE-%MSI_VERSION%.msi" (
    echo ERROR: expected MSI dist\KalixIDE-%MSI_VERSION%.msi was not produced
    exit /b 1
)
move /Y "dist\KalixIDE-%MSI_VERSION%.msi" "dist\%ASSET_NAME%"
if errorlevel 1 exit /b 1

echo ========================================
echo MSI Build Complete - Kalix v%MSI_VERSION%
echo ========================================
echo Installer: dist\%ASSET_NAME%
dir "dist\%ASSET_NAME%"
