@echo off
setlocal
title ScreenMind - Home Simulator
pushd "%~dp0Mobile Client" || exit /b 1
if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" -m scripts.home_simulator.launch
) else (
    where py.exe >nul 2>&1
    if errorlevel 1 (
        python.exe -m scripts.home_simulator.launch
    ) else (
        py.exe -3 -m scripts.home_simulator.launch
    )
)
set "launch_result=%errorlevel%"
popd
if not "%launch_result%"=="0" pause
exit /b %launch_result%
