@echo off
setlocal enabledelayedexpansion

:: Read version from VERSION file
set /p VERSION=<VERSION
echo ========================================
echo Building Kalix v%VERSION%
echo ========================================

echo Building Rust CLI...
cargo build --release

echo Building KalixIDE...
cd kalixide
call gradlew.bat clean --no-daemon
call gradlew.bat build jpackageImage --no-daemon
cd ..

echo Preparing distribution...
if not exist dist mkdir dist

echo Preparing KalixIDE distribution...
set DIST_FOLDER=dist\KalixIDE-Windows-%VERSION%
set ZIP_NAME=KalixIDE-Windows-%VERSION%-Portable.zip
if exist "%DIST_FOLDER%" rmdir /s /q "%DIST_FOLDER%"
mkdir "%DIST_FOLDER%"
xcopy /E /I /Y kalixide\build\jpackage\KalixIDE "%DIST_FOLDER%"

echo Copying Kalix CLI into distribution...
copy /Y target\release\kalix.exe "%DIST_FOLDER%\"

echo Creating KalixIDE zip...
powershell -Command "Compress-Archive -Path '%DIST_FOLDER%' -DestinationPath 'dist\%ZIP_NAME%' -Force"

echo ========================================
echo Build Complete - Kalix v%VERSION%
echo ========================================
echo Portable zip: dist\%ZIP_NAME%
echo   Contains: KalixIDE + kalix.exe
dir dist\%ZIP_NAME%
