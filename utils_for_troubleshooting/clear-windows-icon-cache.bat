@echo off
REM ============================================================================
REM clear-windows-icon-cache.bat
REM
REM Clears the Windows icon cache to force Windows to reload icons.
REM This is useful when application icons have changed but Windows
REM still shows the old cached versions.
REM
REM After running this script:
REM 1. You may need to log out and log back in, OR
REM 2. Restart Windows Explorer (this script does it automatically)
REM
REM Note: This will temporarily affect all icons on your system while
REM the cache rebuilds, but they will return to normal quickly.
REM ============================================================================

echo ============================================
echo Windows Icon Cache Clearer
echo ============================================
echo.

REM Check for admin rights (some cache files may need admin access)
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo WARNING: Not running as administrator.
    echo Some cache files may not be cleared.
    echo For best results, right-click this script and "Run as administrator"
    echo.
)

echo Step 1: Closing Windows Explorer...
taskkill /f /im explorer.exe >nul 2>&1

echo Step 2: Clearing icon cache files...

REM Clear the main icon cache database files
attrib -h -s -r "%LOCALAPPDATA%\IconCache.db" >nul 2>&1
del /f "%LOCALAPPDATA%\IconCache.db" >nul 2>&1

REM Clear the Explorer icon cache files (Windows 8+)
attrib -h -s -r "%LOCALAPPDATA%\Microsoft\Windows\Explorer\iconcache_*.db" >nul 2>&1
del /f "%LOCALAPPDATA%\Microsoft\Windows\Explorer\iconcache_*.db" >nul 2>&1

REM Clear thumbnail cache as well (sometimes helps)
attrib -h -s -r "%LOCALAPPDATA%\Microsoft\Windows\Explorer\thumbcache_*.db" >nul 2>&1
del /f "%LOCALAPPDATA%\Microsoft\Windows\Explorer\thumbcache_*.db" >nul 2>&1

echo Step 3: Restarting Windows Explorer...
start explorer.exe

echo.
echo ============================================
echo Icon cache cleared!
echo ============================================
echo.
echo If the Jump List icon still shows incorrectly:
echo 1. Unpin KalixIDE from the taskbar
echo 2. Delete any shortcuts to the old KalixIDE.exe
echo 3. Run the new KalixIDE.exe
echo 4. Pin it fresh to the taskbar
echo.
pause
