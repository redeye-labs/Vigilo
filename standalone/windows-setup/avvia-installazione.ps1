# Vigilo (Docker) - avvio dell'installazione/aggiornamento dal menu Start.
# Usa la versione piu' recente di install-windows.ps1 pubblicata sul ramo principale (main), cosi' le
# correzioni arrivano senza riscaricare Vigilo-Setup.exe. Se GitHub non risponde usa la copia installata.
# File solo ASCII (Windows PowerShell 5.1).

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bundled = Join-Path $here 'install-windows.ps1'
$latest = Join-Path $here 'install-windows.latest.ps1'
$url = 'https://raw.githubusercontent.com/redeye-labs/Vigilo/main/standalone/install-windows.ps1'

$host.UI.RawUI.WindowTitle = 'Vigilo - installazione'
$script = $bundled
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    $tmp = "$latest.tmp"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp -TimeoutSec 30
    # Usa il file scaricato solo se e' uno script PowerShell valido e completo.
    $errs = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($tmp, [ref]$null, [ref]$errs)
    if (-not $errs -and (Select-String -LiteralPath $tmp -Pattern 'function Install-Vigilo' -SimpleMatch -Quiet)) {
        Move-Item -LiteralPath $tmp -Destination $latest -Force
        $script = $latest
    } else {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
} catch {
    Write-Host "Versione aggiornata non raggiungibile, uso quella installata. ($($_.Exception.Message))" -ForegroundColor Yellow
    if (Test-Path $latest) { $script = $latest }
}
& $script
