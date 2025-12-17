@echo off
echo Creating multi-resolution ICO file...

set "prefix=kalix_white"

rem Windows uses resolution 16, 24, 32, 48, 256

magick %prefix%-16.png %prefix%-24.png %prefix%-48.png %prefix%-256.png %prefix%-icon.ico

echo.
echo Created %prefix%-icon.ico with multiple resolutions
pause