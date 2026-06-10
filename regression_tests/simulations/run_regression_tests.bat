@echo off
setlocal

:: Navigate to script directory
set SCRIPT_DIR=%~dp0

:: Run the regression tests using kalix PyPI package
echo Running regression tests...
python "%SCRIPT_DIR%verify_all_models.py"
exit /b %errorlevel%
