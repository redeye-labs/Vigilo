; Vigilo-Setup.exe - installazione della versione Docker di Vigilo (senza Home Assistant).
; Costruire con build.sh (NSIS 3, makensis). Installazione per utente, senza permessi di amministratore.

Unicode true
SetCompressor /SOLID lzma
RequestExecutionLevel user
ManifestDPIAware true

!define APPNAME     "Vigilo (Docker)"
!define PUBLISHER   "RedEye Labs"
!define VERSION     "1.0.8"
!define VERSION4    "1.0.8.0"
!define UNINSTKEY   "Software\Microsoft\Windows\CurrentVersion\Uninstall\VigiloDocker"
!define URL         "https://github.com/redeye-labs/Vigilo"
!define PAGEURL     "http://localhost:8099"
!ifndef OUTFILE
  !define OUTFILE "out\Vigilo-Setup.exe"
!endif

Name "${APPNAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\Vigilo"
BrandingText "${PUBLISHER} - Vigilo ${VERSION}"
ShowInstDetails show
ShowUninstDetails show

VIProductVersion "${VERSION4}"
VIFileVersion "${VERSION4}"
VIAddVersionKey /LANG=0 "ProductName" "Vigilo"
VIAddVersionKey /LANG=0 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=0 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=0 "CompanyName" "${PUBLISHER}"
VIAddVersionKey /LANG=0 "FileDescription" "Installazione di Vigilo (versione Docker)"
VIAddVersionKey /LANG=0 "LegalCopyright" "MIT - ${PUBLISHER}"
VIAddVersionKey /LANG=0 "OriginalFilename" "Vigilo-Setup.exe"

!include "MUI2.nsh"
!include "LogicLib.nsh"

!define MUI_ICON   "vigilo.ico"
!define MUI_UNICON "vigilo.ico"
!define MUI_ABORTWARNING

!define MUI_WELCOMEPAGE_TITLE "$(WelcomeTitle)"
!define MUI_WELCOMEPAGE_TEXT  "$(WelcomeText)"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_TITLE "$(FinishTitle)"
!define MUI_FINISHPAGE_TEXT  "$(FinishText)"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "$(FinishRun)"
!define MUI_FINISHPAGE_RUN_FUNCTION StartInstaller
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Italian"
!insertmacro MUI_LANGUAGE "English"

LangString WelcomeTitle ${LANG_ITALIAN} "Installazione di Vigilo (versione Docker)"
LangString WelcomeTitle ${LANG_ENGLISH} "Vigilo setup (Docker version)"
LangString WelcomeText ${LANG_ITALIAN} "Questo programma prepara Vigilo senza Home Assistant.$\r$\n$\r$\nAlla fine si apre una finestra che controlla WSL e Docker Desktop (se mancano te lo chiede prima di installarli), scarica Vigilo e lo avvia. Poi si apre la pagina di configurazione nel browser.$\r$\n$\r$\nVigilo su Home Assistant non viene toccato. Non servono permessi di amministratore."
LangString WelcomeText ${LANG_ENGLISH} "This program sets up Vigilo without Home Assistant.$\r$\n$\r$\nAt the end a window checks WSL and Docker Desktop (it asks before installing anything missing), downloads Vigilo and starts it. Then the setup page opens in your browser.$\r$\n$\r$\nVigilo on Home Assistant is not touched. No administrator rights needed."
LangString FinishTitle ${LANG_ITALIAN} "Quasi fatto"
LangString FinishTitle ${LANG_ENGLISH} "Almost done"
LangString FinishText ${LANG_ITALIAN} "Premi Fine: si apre una finestra che installa e avvia Vigilo (la prima volta qualche minuto). Quando ti chiede il nome premi Invio.$\r$\n$\r$\nPer entrare nella pagina: nome utente vigilo, password mostrata alla fine nella finestra (e copiata negli appunti).$\r$\n$\r$\nNel menu Start trovi $\"Vigilo - Apri pagina$\", $\"Vigilo - Mostra password$\" e $\"Vigilo - Aggiorna$\"."
LangString FinishText ${LANG_ENGLISH} "Click Finish: a console window installs and starts Vigilo (a few minutes the first time). When it asks for the name, press Enter.$\r$\n$\r$\nTo log in: user vigilo, the password is shown at the end of that window (and copied to the clipboard).$\r$\n$\r$\nThe Start menu has $\"Vigilo - Apri pagina$\" (open page), $\"Vigilo - Mostra password$\" (show password) and $\"Vigilo - Aggiorna$\" (update)."
LangString FinishRun ${LANG_ITALIAN} "Installa e avvia Vigilo adesso"
LangString FinishRun ${LANG_ENGLISH} "Install and start Vigilo now"
LangString PwDesc ${LANG_ITALIAN} "Password della pagina di Vigilo (nome utente: vigilo)"
LangString PwDesc ${LANG_ENGLISH} "Password for the Vigilo page (user: vigilo)"
LangString UnCancelled ${LANG_ITALIAN} "Disinstallazione annullata."
LangString UnCancelled ${LANG_ENGLISH} "Uninstall cancelled."
LangString UnDockerErr ${LANG_ITALIAN} "Il container Vigilo non è stato rimosso (Docker spento?).$\r$\nRimuovere comunque collegamenti e programma? I dati restano in %USERPROFILE%\Vigilo."
LangString UnDockerErr ${LANG_ENGLISH} "The Vigilo container was not removed (is Docker running?).$\r$\nRemove the shortcuts and program anyway? Your data stays in %USERPROFILE%\Vigilo."

!define PS '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe"'

Section "Vigilo" SecMain
  SetOutPath "$INSTDIR"
  File "..\install-windows.ps1"
  File "..\uninstall-windows.ps1"
  File "avvia-installazione.ps1"
  File "vigilo.ico"
  WriteUninstaller "$INSTDIR\Disinstalla.exe"

  ; Menu Start
  CreateDirectory "$SMPROGRAMS\Vigilo"
  WriteINIStr "$SMPROGRAMS\Vigilo\Vigilo – Apri pagina.url" "InternetShortcut" "URL" "${PAGEURL}"
  WriteINIStr "$SMPROGRAMS\Vigilo\Vigilo – Apri pagina.url" "InternetShortcut" "IconFile" "$INSTDIR\vigilo.ico"
  WriteINIStr "$SMPROGRAMS\Vigilo\Vigilo – Apri pagina.url" "InternetShortcut" "IconIndex" "0"
  CreateShortcut "$SMPROGRAMS\Vigilo\Vigilo – Aggiorna.lnk" ${PS} \
    '-NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\avvia-installazione.ps1"' "$INSTDIR\vigilo.ico" 0
  CreateShortcut "$SMPROGRAMS\Vigilo\Vigilo – Mostra password.lnk" "$WINDIR\notepad.exe" \
    '"$PROFILE\Vigilo\standalone\data\ui_password"' "$INSTDIR\vigilo.ico" 0 SW_SHOWNORMAL "" "$(PwDesc)"
  CreateShortcut "$SMPROGRAMS\Vigilo\Disinstalla Vigilo.lnk" "$INSTDIR\Disinstalla.exe" "" "$INSTDIR\vigilo.ico" 0

  ; App installate (per utente)
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTKEY}" "Publisher" "${PUBLISHER}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\vigilo.ico"
  WriteRegStr HKCU "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTKEY}" "URLInfoAbout" "${URL}"
  WriteRegStr HKCU "${UNINSTKEY}" "UninstallString" '"$INSTDIR\Disinstalla.exe"'
  WriteRegStr HKCU "${UNINSTKEY}" "QuietUninstallString" '"$INSTDIR\Disinstalla.exe" /S'
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "EstimatedSize" 200
SectionEnd

; Console visibile con l'installazione vera e propria (non in modalita' silenziosa).
Function StartInstaller
  Exec '${PS} -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\avvia-installazione.ps1"'
FunctionEnd

Section "Uninstall"
  ; Ferma e rimuove il container; i dati si cancellano solo se l'utente scrive CANCELLA.
  ; In modalita' silenziosa (/S) non si toccano Docker e dati: si toglie solo il programma.
  ${IfNot} ${Silent}
  ${AndIf} ${FileExists} "$INSTDIR\uninstall-windows.ps1"
    ExecWait '${PS} -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\uninstall-windows.ps1"' $0
    ${If} $0 == 2
      MessageBox MB_ICONINFORMATION "$(UnCancelled)"
      Abort "$(UnCancelled)"
    ${ElseIf} $0 != 0
      MessageBox MB_YESNO|MB_ICONQUESTION "$(UnDockerErr)" IDYES +2
      Abort "$(UnCancelled)"
    ${EndIf}
  ${EndIf}

  Delete "$SMPROGRAMS\Vigilo\Vigilo – Apri pagina.url"
  Delete "$SMPROGRAMS\Vigilo\Vigilo – Aggiorna.lnk"
  Delete "$SMPROGRAMS\Vigilo\Vigilo – Mostra password.lnk"
  Delete "$SMPROGRAMS\Vigilo\Disinstalla Vigilo.lnk"
  RMDir "$SMPROGRAMS\Vigilo"
  Delete "$INSTDIR\install-windows.ps1"
  Delete "$INSTDIR\install-windows.latest.ps1"
  Delete "$INSTDIR\install-windows.latest.ps1.tmp"
  Delete "$INSTDIR\uninstall-windows.ps1"
  Delete "$INSTDIR\avvia-installazione.ps1"
  Delete "$INSTDIR\vigilo.ico"
  Delete "$INSTDIR\Disinstalla.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINSTKEY}"
SectionEnd
