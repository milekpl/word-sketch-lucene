@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem usage: kill-server.cmd [/force]
set "FORCE=0"
if /I "%~1"=="/force" set "FORCE=1"

set "KILLED=0"
set "SKIPPED=0"

echo Looking for processes listening on port 8080...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":8080 .*LISTENING"') do (
    set "PID=%%P"
    rem get command line for PID
    set "CMDLINE="
    for /f "usebackq tokens=1* delims==" %%A in (`wmic process where processid^=%%P get CommandLine /value ^2^>nul ^| find "="`) do (
        set "CMDLINE=%%B"
    )

    if defined CMDLINE (
        echo Found listener PID %%P: "!CMDLINE!"
    ) else (
        echo Found listener PID %%P (no command-line available)
    )

    set "MATCH=0"
    echo !CMDLINE! | findstr /I "word-sketch word_sketch word-sketch-lucene" >nul 2>&1 && set "MATCH=1"
    if "%FORCE%"=="1" set "MATCH=1"

    if "!MATCH!"=="1" (
        echo Killing PID %%P ...
        taskkill /PID %%P /T /F >nul 2>&1 && (
            echo   killed PID %%P
            set /a KILLED+=1
        ) || (
            echo   failed to kill PID %%P
        )
    ) else (
        echo Skipping PID %%P (does not look like word-sketch). Use /force to override.
        set /a SKIPPED+=1
    )
)

if %KILLED% EQU 0 if %SKIPPED% EQU 0 (
    echo No listeners on port 8080 found.
) else (
    if %KILLED% GTR 0 echo Killed %KILLED% process^(es^).
    if %SKIPPED% GTR 0 echo Skipped %SKIPPED% process^(es^) (not matched).
)

echo Verifying port 8080...
set "PORT_BUSY="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":8080 .*LISTENING"') do (
    echo Port 8080 is still listening by PID %%P
    set "PORT_BUSY=1"
)
if not defined PORT_BUSY (
    echo Port 8080 is free.
)

endlocal
exit /b 0
