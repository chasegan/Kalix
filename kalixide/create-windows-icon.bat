@echo off
REM ============================================================================
REM create-windows-icon.bat
REM
REM Creates a Windows .ico file from the existing PNG icon files using ImageMagick.
REM The .ico file contains multiple sizes for proper display at different DPIs
REM and in different contexts (taskbar, file explorer, etc.)
REM
REM Required sizes for Windows .ico:
REM   16x16   - Small icons, system tray
REM   32x32   - Standard icons, taskbar (100% DPI)
REM   48x48   - Large icons, taskbar (150% DPI)
REM   256x256 - Extra large icons, high DPI displays
REM
REM Usage: Run this script from the kalixide directory
REM ============================================================================

setlocal

REM Try to find ImageMagick - check common installation paths
set MAGICK_CMD=magick

REM Test if magick is in PATH
where magick >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    REM Try common installation paths
    if exist "C:\Program Files\ImageMagick-7.1.1-Q16-HDRI\magick.exe" (
        set MAGICK_CMD="C:\Program Files\ImageMagick-7.1.1-Q16-HDRI\magick.exe"
    ) else if exist "C:\Program Files\ImageMagick-7.1.1-Q16\magick.exe" (
        set MAGICK_CMD="C:\Program Files\ImageMagick-7.1.1-Q16\magick.exe"
    ) else (
        REM Search for any ImageMagick installation
        for /d %%i in ("C:\Program Files\ImageMagick*") do (
            if exist "%%i\magick.exe" (
                set MAGICK_CMD="%%i\magick.exe"
                goto :found
            )
        )
        echo ERROR: ImageMagick not found. Please install ImageMagick or add it to PATH.
        echo Download from: https://imagemagick.org/script/download.php
        exit /b 1
    )
)
:found

echo Using ImageMagick: %MAGICK_CMD%

REM Define paths
set ICONS_DIR=src\main\resources\icons
set OUTPUT_FILE=%ICONS_DIR%\kalix.ico

REM Check that source PNG files exist
if not exist "%ICONS_DIR%\kalix-16.png" (
    echo ERROR: Source PNG files not found in %ICONS_DIR%
    exit /b 1
)

echo Creating Windows icon file from PNG sources...
echo   Source: %ICONS_DIR%\kalix-*.png
echo   Output: %OUTPUT_FILE%

REM Create .ico file with multiple sizes
REM Windows .ico format supports embedding multiple sizes
%MAGICK_CMD% convert ^
    "%ICONS_DIR%\kalix-16.png" ^
    "%ICONS_DIR%\kalix-32.png" ^
    "%ICONS_DIR%\kalix-48.png" ^
    "%ICONS_DIR%\kalix-256.png" ^
    "%OUTPUT_FILE%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS: Created %OUTPUT_FILE%
    echo.
    REM Show file info
    dir "%OUTPUT_FILE%"
) else (
    echo.
    echo ERROR: Failed to create .ico file
    exit /b 1
)

endlocal
