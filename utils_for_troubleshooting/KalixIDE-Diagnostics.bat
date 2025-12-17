@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo KalixIDE Diagnostics Report
echo ============================================================
echo.
echo This script collects system information to help diagnose
echo why KalixIDE may not be starting correctly.
echo.
echo Please run this from the same folder as KalixIDE.exe
echo and send the output file to the KalixIDE developers.
echo.
echo ============================================================

set "REPORT_FILE=%~dp0KalixIDE-Diagnostic-Report.txt"
set "SCRIPT_DIR=%~dp0"

echo Generating report: %REPORT_FILE%
echo.

:: Clear/create the report file
echo ============================================================ > "%REPORT_FILE%"
echo KalixIDE DIAGNOSTIC REPORT >> "%REPORT_FILE%"
echo Generated: %date% %time% >> "%REPORT_FILE%"
echo ============================================================ >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo WINDOWS VERSION >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
ver >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"
systeminfo | findstr /B /C:"OS Name" /C:"OS Version" /C:"System Type" /C:"Locale" >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo SYSTEM ARCHITECTURE >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo PROCESSOR_ARCHITECTURE: %PROCESSOR_ARCHITECTURE% >> "%REPORT_FILE%"
echo PROCESSOR_ARCHITEW6432: %PROCESSOR_ARCHITEW6432% >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo INSTALLATION PATH >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo Script location: %SCRIPT_DIR% >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo KALIXIDE FILES CHECK >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"

if exist "%SCRIPT_DIR%KalixIDE.exe" (
    echo [OK] KalixIDE.exe found >> "%REPORT_FILE%"
) else (
    echo [MISSING] KalixIDE.exe NOT FOUND >> "%REPORT_FILE%"
)

if exist "%SCRIPT_DIR%runtime\bin\jli.dll" (
    echo [OK] runtime\bin\jli.dll found >> "%REPORT_FILE%"
) else (
    echo [MISSING] runtime\bin\jli.dll >> "%REPORT_FILE%"
)

if exist "%SCRIPT_DIR%runtime\bin\java.dll" (
    echo [OK] runtime\bin\java.dll found >> "%REPORT_FILE%"
) else (
    echo [MISSING] runtime\bin\java.dll >> "%REPORT_FILE%"
)

if exist "%SCRIPT_DIR%runtime\bin\server\jvm.dll" (
    echo [OK] runtime\bin\server\jvm.dll found >> "%REPORT_FILE%"
) else (
    echo [MISSING] runtime\bin\server\jvm.dll >> "%REPORT_FILE%"
)
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo VISUAL C++ REDISTRIBUTABLES INSTALLED >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo Checking registry for VC++ Redistributables... >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo === 64-bit Redistributables === >> "%REPORT_FILE%"
reg query "HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" /v Version >> "%REPORT_FILE%" 2>&1
echo. >> "%REPORT_FILE%"

echo === 32-bit Redistributables === >> "%REPORT_FILE%"
reg query "HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x86" /v Version >> "%REPORT_FILE%" 2>&1
echo. >> "%REPORT_FILE%"

echo === All installed VC++ packages === >> "%REPORT_FILE%"
reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" /s /f "Visual C++" 2>nul | findstr "DisplayName" >> "%REPORT_FILE%"
reg query "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall" /s /f "Visual C++" 2>nul | findstr "DisplayName" >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo UCRT (Universal C Runtime) CHECK >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"

if exist "%SYSTEMROOT%\System32\ucrtbase.dll" (
    echo [OK] System ucrtbase.dll found in System32 >> "%REPORT_FILE%"
) else (
    echo [WARNING] System ucrtbase.dll NOT found in System32 >> "%REPORT_FILE%"
)

if exist "%SCRIPT_DIR%runtime\bin\ucrtbase.dll" (
    echo [OK] Bundled ucrtbase.dll found in runtime\bin >> "%REPORT_FILE%"
) else (
    echo [WARNING] Bundled ucrtbase.dll NOT found >> "%REPORT_FILE%"
)
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo BUNDLED RUNTIME DLLs >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo Checking for key bundled DLLs in runtime\bin: >> "%REPORT_FILE%"

if exist "%SCRIPT_DIR%runtime\bin\vcruntime140.dll" (
    echo [OK] vcruntime140.dll >> "%REPORT_FILE%"
) else (
    echo [MISSING] vcruntime140.dll >> "%REPORT_FILE%"
)

if exist "%SCRIPT_DIR%runtime\bin\vcruntime140_1.dll" (
    echo [OK] vcruntime140_1.dll >> "%REPORT_FILE%"
) else (
    echo [MISSING] vcruntime140_1.dll >> "%REPORT_FILE%"
)

if exist "%SCRIPT_DIR%runtime\bin\msvcp140.dll" (
    echo [OK] msvcp140.dll >> "%REPORT_FILE%"
) else (
    echo [MISSING] msvcp140.dll >> "%REPORT_FILE%"
)
echo. >> "%REPORT_FILE%"

echo API-MS-WIN DLLs found: >> "%REPORT_FILE%"
dir /b "%SCRIPT_DIR%runtime\bin\api-ms-win-*.dll" 2>nul | find /c /v "" >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo WINDOWS DEFENDER / ANTIVIRUS STATUS >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo Checking Windows Defender status... >> "%REPORT_FILE%"
powershell -Command "Get-MpComputerStatus 2>$null | Select-Object AntivirusEnabled,RealTimeProtectionEnabled | Format-List" >> "%REPORT_FILE%" 2>&1
echo. >> "%REPORT_FILE%"

echo Checking for third-party antivirus... >> "%REPORT_FILE%"
wmic /namespace:\\root\SecurityCenter2 path AntiVirusProduct get displayName 2>nul >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo ATTEMPTING TO READ jli.dll >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
powershell -Command "$p='%SCRIPT_DIR%runtime\bin\jli.dll'; if(Test-Path $p){$f=Get-Item $p; Write-Host '[OK] jli.dll size:' $f.Length 'bytes, LastWrite:' $f.LastWriteTime}else{Write-Host '[ERROR] jli.dll not found'}" >> "%REPORT_FILE%" 2>&1
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo ENVIRONMENT VARIABLES >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo JAVA_HOME: %JAVA_HOME% >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

echo ------------------------------------------------------------ >> "%REPORT_FILE%"
echo RECENT APPLICATION ERRORS >> "%REPORT_FILE%"
echo ------------------------------------------------------------ >> "%REPORT_FILE%"
powershell -Command "Get-WinEvent -LogName Application -MaxEvents 5 -FilterXPath '*[System[Level=2]]' 2>$null | Select-Object TimeCreated,Id,Message | Format-List" >> "%REPORT_FILE%" 2>&1
echo. >> "%REPORT_FILE%"

echo ============================================================ >> "%REPORT_FILE%"
echo END OF DIAGNOSTIC REPORT >> "%REPORT_FILE%"
echo ============================================================ >> "%REPORT_FILE%"

echo.
echo ============================================================
echo Report saved to: %REPORT_FILE%
echo ============================================================
echo.
echo Please send this file to the KalixIDE developers.
echo.
echo Opening report location...
explorer /select,"%REPORT_FILE%"
echo.
pause
