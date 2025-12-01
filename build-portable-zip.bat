@echo off
echo Building Rust CLI...
cargo build --release

echo Building KalixIDE...
cd kalixide
call gradlew.bat clean --no-daemon
call gradlew.bat build jpackageImage --no-daemon
cd ..

echo Preparing distribution...
if not exist dist mkdir dist

echo Copying standalone KalixCLI...
copy /Y target\release\kalixcli.exe dist\

echo Preparing KalixIDE distribution...
if exist dist\KalixIDE-Windows rmdir /s /q dist\KalixIDE-Windows
mkdir dist\KalixIDE-Windows
xcopy /E /I /Y kalixide\build\jpackage\KalixIDE dist\KalixIDE-Windows

echo Creating KalixIDE zip...
powershell -Command "Compress-Archive -Path 'dist\KalixIDE-Windows' -DestinationPath 'dist\KalixIDE-Windows-Portable.zip' -Force"

echo Done!
echo Standalone CLI: dist\kalixcli.exe
echo IDE Portable: dist\KalixIDE-Windows-Portable.zip
dir dist\kalixcli.exe dist\*.zip
