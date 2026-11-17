@echo off
rem Vigilo - installazione automatica per Windows (doppio clic).
rem Usa install-windows.ps1 se si trova accanto a questo file, altrimenti lo scarica da GitHub.
setlocal
title Vigilo - installazione
set "PS1=%~dp0install-windows.ps1"
set "URL=https://raw.githubusercontent.com/redeye-labs/Vigilo/standalone-docker/standalone/install-windows.ps1"
if exist "%PS1%" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor 3072; $b=(New-Object Net.WebClient).DownloadData('%URL%'); iex ([Text.Encoding]::UTF8.GetString($b).TrimStart([char]0xFEFF))"
)
if errorlevel 1 (
  echo.
  echo Impossibile avviare PowerShell o scaricare il programma di installazione.
  echo Controlla la connessione a internet e riprova.
  pause
)
endlocal
