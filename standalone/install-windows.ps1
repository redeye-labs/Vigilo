# Vigilo standalone - installazione automatica per Windows (Docker Desktop).
# Nota per chi modifica: il file deve restare solo ASCII (niente BOM), perche "irm | iex" e
# Windows PowerShell 5.1 leggono la codifica in modo diverso. Lettere accentate: $([char]0x00E8) = e grave.
#
# Doppio clic su install-windows.bat, oppure (tasto Windows + R, incolla, Invio):
#   powershell -ExecutionPolicy Bypass -c "irm https://raw.githubusercontent.com/redeye-labs/Vigilo/standalone-docker/standalone/install-windows.ps1 | iex"
#
# Rilanciarlo aggiorna Vigilo e conserva dati (standalone\data) e configurazione (standalone\.env).
# Variabili facoltative: VIGILO_DIR (cartella, predefinita %USERPROFILE%\Vigilo), VIGILO_TS_HOSTNAME
# (nome Tailscale, senza domanda), VIGILO_REF (ramo), VIGILO_SRC (cartella locale, per sviluppo),
# VIGILO_NO_PAUSE=1 (non aspetta Invio alla fine).
# Compatibile con Windows PowerShell 5.1 e PowerShell 7.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # rende Invoke-WebRequest molto piu veloce in PowerShell 5.1
# TLS 1.2 per tutti i download (Windows PowerShell 5.1 a volte usa ancora protocolli vecchi).
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }

$script:Repo = 'redeye-labs/Vigilo'
$script:DefaultHost = 'vigilo-docker'
$script:Port = 8099
$script:MinWsl = [version]'2.1.5'
$script:StepNo = 0
$script:Docker = 'docker'

function Write-Step([string]$Text) {
    $script:StepNo++
    Write-Host ''
    Write-Host ("[{0}] {1}" -f $script:StepNo, $Text) -ForegroundColor Cyan
}
function Write-Ok([string]$Text)   { Write-Host "    OK  $Text" -ForegroundColor Green }
function Write-Note([string]$Text) { Write-Host "    !   $Text" -ForegroundColor Yellow }
function Write-Info([string]$Text) { Write-Host "        $Text" }

# Interrompe l'installazione con un messaggio chiaro (gestito in fondo al file).
function Stop-Install([string]$Text) { throw [System.InvalidOperationException]::new($Text) }

function Confirm-Yes([string]$Question) {
    if ($env:VIGILO_YES -eq '1') { return $true }
    $a = Read-Host "    $Question [s/N]"
    return ($a -match "^(s|si|s$([char]0x00EC)|y|yes)`$")
}

# Esegue un comando esterno senza far diventare errori le righe su stderr (PowerShell 5.1).
function Invoke-Quiet([string]$Exe, [string[]]$Arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Exe @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ Code = $LASTEXITCODE; Out = $out }
    } catch {
        return [pscustomobject]@{ Code = -1; Out = $_.Exception.Message }
    } finally {
        $ErrorActionPreference = $old
    }
}

# Esegue un comando esterno mostrando l'output a schermo; restituisce il codice di uscita.
function Invoke-Visible([string]$Exe, [string[]]$Arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Exe @Arguments | Out-Host; return $LASTEXITCODE } finally { $ErrorActionPreference = $old }
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Esegue SOLO questo comando come amministratore (finestra di conferma di Windows).
function Invoke-Elevated([string]$Exe, [string]$Arguments) {
    if (Test-Admin) {
        $p = Start-Process -FilePath $Exe -ArgumentList $Arguments -Wait -PassThru -NoNewWindow
        return $p.ExitCode
    }
    Write-Info "Windows chieder$([char]0x00E0) il permesso di amministratore: premi $([char]0x00AB)S$([char]0x00EC)$([char]0x00BB)."
    try {
        $p = Start-Process -FilePath $Exe -ArgumentList $Arguments -Verb RunAs -Wait -PassThru
        return $p.ExitCode
    } catch {
        Stop-Install "Permesso di amministratore non concesso: senza, WSL non si pu$([char]0x00F2) aggiornare."
    }
}

# ---------- WSL ----------

# Modern = WSL dallo Store/da GitHub (conosce --version e --update); altrimenti la vecchia versione di Windows.
function Get-WslState {
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
        return [pscustomobject]@{ Present = $false; Modern = $false; Version = $null }
    }
    $env:WSL_UTF8 = '1'
    $r = Invoke-Quiet 'wsl.exe' @('--version')
    $ver = $null
    if ($r.Code -eq 0 -and ($r.Out -replace "`0", '') -match '(\d+\.\d+\.\d+)') { $ver = [version]$Matches[1] }
    return [pscustomobject]@{ Present = $true; Modern = ($null -ne $ver); Version = $ver }
}

# Riavvio in sospeso? (componenti di Windows appena attivati, installazioni che lo chiedono)
function Test-RebootPending {
    $keys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired'
    )
    foreach ($k in $keys) { if (Test-Path $k) { return $true } }
    return $false
}

# Piano B: scarica da GitHub (microsoft/WSL) l'ultimo pacchetto .msi e lo installa.
# Serve quando "wsl --install/--update" fallisce, per esempio con "Errore nel supporto per il canale sicuro".
# Restituisce 'ok' oppure 'reboot'.
function Install-WslMsi {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x64' }
    Write-Info "Scarico WSL da GitHub (microsoft/WSL, pacchetto $arch)..."
    try {
        $rel = Invoke-RestMethod -UseBasicParsing -Uri 'https://api.github.com/repos/microsoft/WSL/releases/latest' -Headers @{ 'User-Agent' = 'vigilo-installer' }
    } catch {
        Stop-Install "Impossibile leggere l'ultima versione di WSL da GitHub ($($_.Exception.Message)). Scaricala a mano da https://github.com/microsoft/WSL/releases/latest (file che finisce in $arch.msi)."
    }
    $asset = $rel.assets | Where-Object { $_.name -match "$arch\.msi$" } | Select-Object -First 1
    if (-not $asset) { Stop-Install "Nella versione $($rel.tag_name) di WSL non trovo il pacchetto $arch.msi: scaricalo a mano da https://github.com/microsoft/WSL/releases/latest" }
    $msi = Join-Path ([System.IO.Path]::GetTempPath()) $asset.name
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $msi
    } catch {
        Stop-Install "Download di WSL non riuscito ($($_.Exception.Message))."
    }
    Write-Ok "scaricato $($asset.name) ($($rel.tag_name))"
    $code = Invoke-Elevated 'msiexec.exe' ('/i "' + $msi + '" /passive /norestart')
    Remove-Item -LiteralPath $msi -Force -ErrorAction SilentlyContinue
    # 0 = fatto, 3010 = fatto ma serve il riavvio, 1641 = riavvio avviato dall'installazione
    if ($code -eq 3010 -or $code -eq 1641) { return 'reboot' }
    if ($code -ne 0) { Stop-Install "Installazione di WSL non riuscita (msiexec, codice $code)." }
    Write-Ok "WSL $($rel.tag_name) installato"
    if (Test-RebootPending) { return 'reboot' }
    return 'ok'
}

# Restituisce 'ok' oppure 'reboot'.
function Repair-Wsl {
    $s = Get-WslState
    if ($s.Modern -and $s.Version -ge $script:MinWsl) {
        Write-Ok "WSL $($s.Version)"
        return 'ok'
    }
    if ($s.Modern) {
        Write-Note "WSL $($s.Version) $([char]0x00E8) vecchio: lo aggiorno."
        $code = Invoke-Elevated 'wsl.exe' '--update --web-download'
        if ($code -ne 0) { $code = Invoke-Elevated 'wsl.exe' '--update' }
        $s = Get-WslState
        if ($code -ne 0 -or -not ($s.Version -ge $script:MinWsl)) {
            Write-Note 'wsl --update non ha funzionato: installo il pacchetto da GitHub.'
            $r = Install-WslMsi
            if ($r -eq 'reboot') { return 'reboot' }
        }
        $null = Invoke-Quiet 'wsl.exe' @('--shutdown')
        $s = Get-WslState
        Write-Ok "WSL aggiornato $(if ($s.Version) { "a $($s.Version)" })"
        return 'ok'
    }
    Write-Note "WSL manca o $([char]0x00E8) la vecchia versione di Windows, che Docker Desktop non usa pi$([char]0x00F9): installo quello nuovo."
    # Attiva i componenti di Windows (Piattaforma macchina virtuale, Sottosistema per Linux) e scarica WSL.
    $code = Invoke-Elevated 'wsl.exe' '--install --no-distribution --web-download'
    $s = Get-WslState
    if (-not $s.Modern) {
        # Spesso i componenti si installano ma il download si ferma (errore del canale sicuro): piano B.
        if ($code -ne 0) { Write-Note "wsl --install non ha completato il download (codice $code): installo il pacchetto da GitHub." }
        else { Write-Info 'Completo con il pacchetto WSL da GitHub.' }
        $null = Install-WslMsi
    }
    # I componenti di Windows appena attivati funzionano solo dopo un riavvio.
    return 'reboot'
}

# ---------- Docker Desktop ----------

function Find-DockerDesktop {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Docker\Docker\Docker Desktop.exe')
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

function Find-DockerCli {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $p = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
    if (Test-Path $p) { return $p }
    return $null
}

function Test-DockerEngine { return ((Invoke-Quiet $script:Docker @('info')).Code -eq 0) }

function Wait-DockerEngine([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    Write-Host -NoNewline '        attendo Docker'
    while ((Get-Date) -lt $deadline) {
        if (Test-DockerEngine) { Write-Host ''; return $true }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 5
    }
    Write-Host ''
    return $false
}

# ---------- file ----------

function Get-EnvValue([string]$Path, [string]$Name) {
    if (-not (Test-Path $Path)) { return '' }
    $line = Get-Content -LiteralPath $Path | Where-Object { $_ -match "^$Name=" } | Select-Object -Last 1
    if ($line) { return ($line -replace "^$Name=", '').Trim() }
    return ''
}

# Scrive in UTF-8 senza BOM e con a capo LF (Docker Compose non vuole il BOM nel file .env).
function Write-TextFile([string]$Path, [string[]]$Lines) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, (($Lines -join "`n") + "`n"), $enc)
}

function Set-EnvValue([string]$Path, [string]$Name, [string]$Value) {
    $lines = @(Get-Content -LiteralPath $Path)
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^$Name=") { $lines[$i] = "$Name=$Value"; $found = $true }
    }
    if (-not $found) { $lines += "$Name=$Value" }
    Write-TextFile $Path $lines
}

function Test-HostName([string]$Name) { return ($Name -cmatch '^[a-z0-9-]{1,40}$') }

# Copia i file nuovi in $Dir: sostituisce tutto tranne standalone\data e standalone\.env.
function Copy-VigiloFiles([string]$Source, [string]$Dir) {
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $sa = Join-Path $Dir 'standalone'
    Get-ChildItem -LiteralPath $Dir -Force | Where-Object { $_.Name -ne 'standalone' } | Remove-Item -Recurse -Force
    if (Test-Path $sa) {
        Get-ChildItem -LiteralPath $sa -Force | Where-Object { $_.Name -ne 'data' -and $_.Name -ne '.env' } | Remove-Item -Recurse -Force
    }
    foreach ($item in Get-ChildItem -LiteralPath $Source -Force) {
        if ($item.Name -eq '.git') { continue }
        if ($item.Name -eq 'standalone') {
            New-Item -ItemType Directory -Force -Path $sa | Out-Null
            foreach ($s in Get-ChildItem -LiteralPath $item.FullName -Force) {
                if ($s.Name -eq 'data' -or $s.Name -eq '.env') { continue }
                Copy-Item -LiteralPath $s.FullName -Destination $sa -Recurse -Force
            }
        } else {
            Copy-Item -LiteralPath $item.FullName -Destination $Dir -Recurse -Force
        }
    }
}

# ---------- installazione ----------

function Install-Vigilo {
    $ref = if ($env:VIGILO_REF) { $env:VIGILO_REF } else { 'standalone-docker' }
    $dir = if ($env:VIGILO_DIR) { $env:VIGILO_DIR } else { Join-Path $env:USERPROFILE 'Vigilo' }
    $sa = Join-Path $dir 'standalone'

    Write-Host 'Vigilo - installazione automatica (versione Docker, senza Home Assistant)' -ForegroundColor White
    Write-Host 'Vigilo su Home Assistant non viene toccato.'

    # 1) Docker Desktop installato?
    Write-Step 'Controllo di Docker Desktop'
    $desktop = Find-DockerDesktop
    $cli = Find-DockerCli
    if (-not $desktop -and -not $cli) {
        Write-Note "Docker Desktop non $([char]0x00E8) installato."
        if ((Get-Command winget -ErrorAction SilentlyContinue) -and (Confirm-Yes 'Installarlo ora con winget (gratuito per uso personale)?')) {
            $code = Invoke-Visible 'winget' @('install', '-e', '--id', 'Docker.DockerDesktop', '--accept-package-agreements', '--accept-source-agreements')
            if ($code -ne 0) { Stop-Install "Installazione di Docker Desktop non riuscita (codice $code)." }
            Write-Ok 'Docker Desktop installato.'
            Write-Info 'Ora riavvia il PC, apri Docker Desktop una volta e accetta le condizioni,'
            Write-Info 'poi rilancia questa installazione.'
            return
        }
        Stop-Install 'Installa Docker Desktop da https://www.docker.com/products/docker-desktop/ e rilancia.'
    }
    if ($cli) { $script:Docker = $cli }
    Write-Ok 'Docker Desktop trovato'

    # 2) Motore Docker attivo? WSL si controlla (e si ripara) solo se Docker non parte.
    Write-Step 'Avvio di Docker'
    if (Test-DockerEngine) {
        Write-Ok "Docker $([char]0x00E8) gi$([char]0x00E0) in funzione"
    } else {
        Write-Info "Docker non $([char]0x00E8) in funzione: controllo prima WSL."
        if ((Repair-Wsl) -eq 'reboot') {
            Write-Ok 'WSL installato.'
            Write-Note 'Serve un riavvio del PC. Dopo il riavvio rilancia questa installazione: riprende da qui.'
            Write-Info '(La macchina virtuale di Home Assistant si ferma durante il riavvio: controlla poi che sia ripartita.)'
            if (Confirm-Yes 'Riavviare il PC adesso?') { Restart-Computer -Force }
            return
        }
        if ($desktop) {
            Write-Info 'Apro Docker Desktop...'
            Start-Process -FilePath $desktop | Out-Null
        }
        if (-not (Wait-DockerEngine 180)) {
            Stop-Install ("Docker non $([char]0x00E8) partito entro 3 minuti. Apri Docker Desktop: se mostra un messaggio " +
                "(condizioni da accettare, WSL da aggiornare, riavvio) seguilo, aspetta $([char]0x00AB)Engine running$([char]0x00BB) e rilancia.")
        }
        Write-Ok "Docker $([char]0x00E8) in funzione"
    }
    $c = Invoke-Quiet $script:Docker @('compose', 'version')
    if ($c.Code -ne 0) { Stop-Install 'Docker Compose non trovato: aggiorna Docker Desktop e rilancia.' }

    # 3) Scaricamento (o aggiornamento) dei file
    Write-Step "Scaricamento di Vigilo in $dir"
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('vigilo-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        if ($env:VIGILO_SRC) {
            $src = $env:VIGILO_SRC
            Write-Ok "copia locale da $src"
        } else {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            $zip = Join-Path $tmp 'vigilo.zip'
            try {
                Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$($script:Repo)/archive/refs/heads/$ref.zip" -OutFile $zip
            } catch {
                Stop-Install "Download non riuscito: controlla la connessione a internet. ($($_.Exception.Message))"
            }
            Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $tmp 'x') -Force
            $src = (Get-ChildItem -LiteralPath (Join-Path $tmp 'x') -Directory | Select-Object -First 1).FullName
            Write-Ok "scaricato il ramo $ref"
        }
        if (-not (Test-Path (Join-Path $src 'standalone\docker-compose.yml'))) { Stop-Install 'Archivio incompleto: manca standalone\docker-compose.yml.' }
        $updating = Test-Path $sa
        Copy-VigiloFiles $src $dir
        if ($updating) { Write-Ok 'aggiornamento: data e .env conservati' } else { Write-Ok 'file pronti' }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }

    # Recupera .env e dati da una prova fatta a mano nella cartella Vigilo-standalone-docker.
    $old = Join-Path $env:USERPROFILE 'Vigilo-standalone-docker\standalone'
    $envFile = Join-Path $sa '.env'
    if ((Test-Path $old) -and ($old -ne $sa)) {
        if (-not (Test-Path $envFile) -and (Test-Path (Join-Path $old '.env'))) {
            Copy-Item -LiteralPath (Join-Path $old '.env') -Destination $envFile
            Write-Ok 'ripreso il file .env della prova precedente'
        }
        if (-not (Test-Path (Join-Path $sa 'data')) -and (Test-Path (Join-Path $old 'data'))) {
            Copy-Item -LiteralPath (Join-Path $old 'data') -Destination (Join-Path $sa 'data') -Recurse
            Write-Ok 'ripresi i dati della prova precedente'
        }
    }

    # 4) Configurazione
    Write-Step 'Configurazione'
    if (-not (Test-Path $envFile)) { Copy-Item -LiteralPath (Join-Path $sa '.env.example') -Destination $envFile }
    $current = Get-EnvValue $envFile 'VIGILO_TS_HOSTNAME'
    $default = if ($env:VIGILO_TS_HOSTNAME) { $env:VIGILO_TS_HOSTNAME } elseif ($current -and $current -ne 'vigilo') { $current } else { $script:DefaultHost }
    $name = $default
    if (-not $env:VIGILO_TS_HOSTNAME) {
        while ($true) {
            $ans = Read-Host "    Nome del dispositivo su Tailscale (Invio = $default)"
            $name = if ([string]::IsNullOrWhiteSpace($ans)) { $default } else { $ans.Trim().ToLower() }
            if (Test-HostName $name) { break }
            Write-Note 'Solo lettere minuscole, numeri e trattino, al massimo 40 caratteri. Riprova.'
        }
    } elseif (-not (Test-HostName $name)) {
        Stop-Install "VIGILO_TS_HOSTNAME non valido: $([char]0x00AB)$name$([char]0x00BB)."
    }
    Set-EnvValue $envFile 'VIGILO_TS_HOSTNAME' $name
    Write-Ok "nome Tailscale: $name"

    # 5) Avvio del container
    Write-Step 'Avvio di Vigilo (la prima volta 5-15 minuti: scarica e compila)'
    $wd = Invoke-Quiet $script:Docker @('inspect', '-f', '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}', 'vigilo')
    $wdPath = $wd.Out.Trim()
    if ($wd.Code -eq 0 -and $wdPath -and ($wdPath.TrimEnd('\') -ne $sa.TrimEnd('\'))) {
        Write-Note "trovato un container vigilo di un'altra cartella ($wdPath): lo sostituisco (i dati restano nella cartella)."
        $null = Invoke-Quiet $script:Docker @('rm', '-f', 'vigilo')
    }
    Push-Location $sa
    try {
        $code = Invoke-Visible $script:Docker @('compose', 'up', '-d', '--build')
    } finally { Pop-Location }
    if ($code -ne 0) { Stop-Install 'Avvio non riuscito: guarda i messaggi qui sopra (spesso basta rilanciare).' }
    Write-Host -NoNewline '        attendo che Vigilo sia pronto'
    $status = ''
    for ($i = 0; $i -lt 60; $i++) {
        $status = (Invoke-Quiet $script:Docker @('inspect', '-f', '{{.State.Health.Status}}', 'vigilo')).Out.Trim()
        if ($status -eq 'healthy') { break }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 3
    }
    Write-Host ''
    if ($status -ne 'healthy') {
        Stop-Install "Vigilo non risulta pronto (stato: $status). Registro: cd `"$sa`" ; docker compose logs --tail 50"
    }
    Write-Ok "Vigilo $([char]0x00E8) avviato"

    # 6) Password negli appunti (mai a schermo) e apertura della pagina
    Write-Step 'Fatto'
    $pw = Get-EnvValue $envFile 'VIGILO_UI_PASSWORD'
    $pwFile = Join-Path $sa 'data\ui_password'
    if (-not $pw) {
        for ($i = 0; $i -lt 30 -and -not (Test-Path $pwFile); $i++) { Start-Sleep -Seconds 1 }
        if (Test-Path $pwFile) { $pw = (Get-Content -LiteralPath $pwFile -Raw).Trim() }
    }
    $copied = $false
    if ($pw) {
        try { Set-Clipboard -Value $pw; $copied = $true } catch { $copied = $false }
    }
    $url = "http://localhost:$($script:Port)"
    Write-Host ''
    Write-Host "    Pagina di Vigilo:  $url" -ForegroundColor White
    Write-Host '    Utente:            vigilo' -ForegroundColor White
    if ($copied) {
        Write-Host "    Password:          gi$([char]0x00E0) copiata negli appunti: incollala con Ctrl+V" -ForegroundColor White
    } else {
        Write-Host "    Password:          nel file $pwFile (aprilo con il Blocco note)" -ForegroundColor White
    }
    Write-Host ''
    Write-Info "Nella pagina segui i passi, a partire da $([char]0x00AB)Genera link di accesso$([char]0x00BB) per Tailscale."
    Write-Info 'Aggiornare: rilancia questa installazione. Disinstallare: uninstall-windows.ps1 nella cartella standalone.'
    try { Start-Process $url | Out-Null } catch { Write-Note "Apri tu il browser su $url" }
}

if ($env:VIGILO_NO_RUN -ne '1') {
    try {
        Install-Vigilo
    } catch {
        Write-Host ''
        Write-Host ('ERRORE: ' + $_.Exception.Message) -ForegroundColor Red
        Write-Host 'Puoi rilanciare l''installazione quando vuoi: riprende da dove serve e non cancella i dati.'
    }
    if ($env:VIGILO_NO_PAUSE -ne '1') { $null = Read-Host "`nPremi Invio per chiudere" }
}
