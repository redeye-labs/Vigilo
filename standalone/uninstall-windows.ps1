# Vigilo standalone - disinstallazione per Windows.
#   powershell -ExecutionPolicy Bypass -File uninstall-windows.ps1
# Ferma e rimuove il container; la cartella (con chiavi e token) si cancella solo se confermi.

$ErrorActionPreference = 'Stop'

function Invoke-Quiet([string]$Exe, [string[]]$Arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Exe @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ Code = $LASTEXITCODE; Out = $out }
    } catch {
        return [pscustomobject]@{ Code = -1; Out = $_.Exception.Message }
    } finally { $ErrorActionPreference = $old }
}

function Uninstall-Vigilo {
    $dir = if ($env:VIGILO_DIR) { $env:VIGILO_DIR } else { Join-Path $env:USERPROFILE 'Vigilo' }
    $sa = Join-Path $dir 'standalone'
    $docker = 'docker'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        $p = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
        if (Test-Path $p) { $docker = $p }
    }
    Write-Host 'Vigilo - disinstallazione (versione Docker). Vigilo su Home Assistant non viene toccato.' -ForegroundColor White
    $a = Read-Host '    Fermare e rimuovere il container Vigilo? [s/N]'
    if ($a -notmatch "^(s|si|s$([char]0x00EC)|y|yes)`$") { Write-Host '    Nessuna modifica.'; $script:ExitCode = 2; return }

    if ((Invoke-Quiet $docker @('info')).Code -ne 0) {
        throw "Docker non $([char]0x00E8) in funzione: apri Docker Desktop, aspetta $([char]0x00AB)Engine running$([char]0x00BB) e rilancia."
    }
    if (Test-Path (Join-Path $sa 'docker-compose.yml')) {
        Push-Location $sa
        try { $r = Invoke-Quiet $docker @('compose', 'down') } finally { Pop-Location }
        if ($r.Code -eq 0) { Write-Host '    OK  container fermato e rimosso' -ForegroundColor Green }
        else { Write-Host "    !   compose down: $($r.Out.Trim())" -ForegroundColor Yellow }
    }
    $null = Invoke-Quiet $docker @('rm', '-f', 'vigilo')
    $removed = $false
    foreach ($img in @('ghcr.io/redeye-labs/vigilo-standalone:preview', 'vigilo-standalone:local')) {
        if ((Invoke-Quiet $docker @('image', 'rm', $img)).Code -eq 0) { $removed = $true }
    }
    if ($removed) { Write-Host '    OK  immagine rimossa' -ForegroundColor Green }

    if (Test-Path $dir) {
        Write-Host "    !   In $sa\data ci sono la chiave virtuale, i token Tesla e lo stato di Tailscale." -ForegroundColor Yellow
        $a = Read-Host "    Cancellare anche la cartella $dir con tutti i dati? Scrivi CANCELLA per confermare"
        if ($a -ceq 'CANCELLA') {
            Remove-Item -LiteralPath $dir -Recurse -Force
            Write-Host '    OK  cartella cancellata' -ForegroundColor Green
        } else {
            Write-Host "    OK  cartella conservata: $dir"
        }
    }
    Write-Host ''
    Write-Host "    Da fare a mano, se non usi pi$([char]0x00F9) questa prova:"
    Write-Host '    - login.tailscale.com: elimina il dispositivo (es. vigilo-docker);'
    Write-Host '    - developer.tesla.com: elimina la seconda app; sull''auto togli la sua chiave (Comandi > Blocchi).'
}

# Codice di uscita (usato dal programma di disinstallazione): 0 fatto, 1 errore, 2 annullato.
$script:ExitCode = 0
try { Uninstall-Vigilo } catch { Write-Host ('ERRORE: ' + $_.Exception.Message) -ForegroundColor Red; $script:ExitCode = 1 }
if ($env:VIGILO_NO_PAUSE -ne '1') { $null = Read-Host "`nPremi Invio per chiudere" }
exit $script:ExitCode
