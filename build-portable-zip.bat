@echo off
setlocal enabledelayedexpansion

:: Preflight: python is required (used by bump-version.py)
where python >nul 2>&1
if errorlevel 1 (
    echo ERROR: python is required but was not found on PATH
    exit /b 1
)

:: Read version from VERSION file
set /p VERSION=<VERSION
echo ========================================
echo Building Kalix v%VERSION%
echo ========================================

:: Sync VERSION into Cargo.toml + python package files
python bump-version.py

echo Building Rust CLI...
cargo build --release
if errorlevel 1 exit /b 1

echo Building KalixIDE...
cd kalixide
call gradlew.bat clean --no-daemon
if errorlevel 1 (cd .. & exit /b 1)
call gradlew.bat assemble jpackageImage --no-daemon
if errorlevel 1 (cd .. & exit /b 1)
cd ..

echo Preparing distribution...
if not exist dist mkdir dist

echo Preparing KalixIDE distribution...
set DIST_FOLDER=dist\KalixIDE-Windows-%VERSION%
set ZIP_NAME=KalixIDE-Windows-%VERSION%-Portable.zip
if exist "%DIST_FOLDER%" rmdir /s /q "%DIST_FOLDER%"
mkdir "%DIST_FOLDER%"
xcopy /E /I /Y kalixide\build\jpackage\KalixIDE "%DIST_FOLDER%"
if errorlevel 1 exit /b 1
if not exist "%DIST_FOLDER%\KalixIDE.exe" (
    echo ERROR: KalixIDE.exe missing from jpackage output
    exit /b 1
)

echo Copying Kalix CLI into distribution...
copy /Y target\release\kalix.exe "%DIST_FOLDER%\"
if errorlevel 1 exit /b 1

echo Creating KalixIDE zip...
powershell -Command "Compress-Archive -Path '%DIST_FOLDER%' -DestinationPath 'dist\%ZIP_NAME%' -Force"

echo ========================================
echo Build Complete - Kalix v%VERSION%
echo ========================================
echo Portable zip: dist\%ZIP_NAME%
echo   Contains: KalixIDE + kalix.exe
dir dist\%ZIP_NAME%
