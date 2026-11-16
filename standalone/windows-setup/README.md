# Vigilo-Setup.exe (Windows)

Programma di installazione per la versione Docker, fatto con [NSIS](https://nsis.sourceforge.io).
Non contiene Vigilo: installa in `%LOCALAPPDATA%\Programs\Vigilo` gli script `install-windows.ps1`,
`uninstall-windows.ps1` e il lanciatore `avvia-installazione.ps1`, crea i collegamenti nel menu Start e la voce in
«App installate», poi apre la console con l'installazione vera e propria (dati in `%USERPROFILE%\Vigilo`).
Non chiede i permessi di amministratore: se serve aggiornare WSL, lo script chiede il permesso solo per quel
comando.

Costruirlo (da Linux, dalla radice del repository):

```bash
standalone/windows-setup/build.sh        # crea standalone/windows-setup/out/Vigilo-Setup.exe
```
