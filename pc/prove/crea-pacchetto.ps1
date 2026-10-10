<#
.SINOSSI
    Costruisce il pacchetto del programma per il computer: pc/dist/sol-pc.zip.

    Fa `dotnet publish` self-contained per win-x64 A CARTELLA (non single-file): niente
    eseguibile che si scompatta da solo. Lo zip contiene una cartella `Sol\` con `Sol.exe`,
    le dll del runtime .NET e la cartella `ui`. Il programma gira da lì: non si copia da
    nessuna parte, l'avvio all'accesso (se scelto) punta alla cartella scompattata.

    Lo zip va messo sul server, che lo serve da GET /scarica/sol-pc.zip.
    Usa lo SDK .NET 8 scompattato in %LOCALAPPDATA%\Microsoft\dotnet-sdk-8 (o quello nel PATH).

    Questo file è salvato in UTF-8 con BOM: PowerShell 5.1 legge i .ps1 senza BOM come ANSI.

.ESEMPIO
    powershell -ExecutionPolicy Bypass -File pc\prove\crea-pacchetto.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$radicePc = Split-Path -Parent $PSScriptRoot            # ...\pc
$progetto = Join-Path $radicePc 'Sol\Sol.csproj'
$dist = Join-Path $radicePc 'dist'
$staging = Join-Path $dist 'Sol'                        # diventa la cartella dentro lo zip
$zip = Join-Path $dist 'sol-pc.zip'

$dotnet = Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet-sdk-8\dotnet.exe'
if (-not (Test-Path $dotnet)) {
    $c = Get-Command dotnet -ErrorAction SilentlyContinue
    if (-not $c) { throw "Non trovo dotnet. Scompatta lo SDK .NET 8 in $env:LOCALAPPDATA\Microsoft\dotnet-sdk-8." }
    $dotnet = $c.Source
}

if (-not (Test-Path (Join-Path $radicePc 'ui\index.html'))) { throw "Manca pc\ui\index.html: senza interfaccia il pacchetto non serve." }

Write-Host "Pulizia..." -ForegroundColor Cyan
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null

$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_NOLOGO = '1'

Write-Host "Pubblicazione (self-contained, a cartella)..." -ForegroundColor Cyan
& $dotnet publish $progetto -c Release -r win-x64 "-p:PubblicazioneSol=true" -o $staging
if ($LASTEXITCODE -ne 0) { throw "publish fallito (codice $LASTEXITCODE)" }

$exe = Join-Path $staging 'Sol.exe'
if (-not (Test-Path $exe)) { throw "Sol.exe non prodotto." }
if (-not (Test-Path (Join-Path $staging 'ui\index.html'))) { throw "La cartella ui non è finita accanto all'exe." }
if (-not (Test-Path (Join-Path $staging 'coreclr.dll'))) { throw "Manca coreclr.dll: la pubblicazione non è self-contained a cartella." }
$versione = (Get-Item $exe).VersionInfo.FileVersion

Write-Host "Creo lo zip..." -ForegroundColor Cyan
Remove-Item $zip -ErrorAction SilentlyContinue
# -Path sulla cartella (senza \*): lo zip contiene la cartella Sol\ con tutto dentro.
Compress-Archive -Path $staging -DestinationPath $zip -CompressionLevel Optimal

$mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
$nFile = (Get-ChildItem $staging -Recurse -File).Count
Write-Host ""
Write-Host "Fatto: Sol $versione" -ForegroundColor Green
Write-Host "  zip:  $zip  ($mb MB, $nFile file nella cartella Sol\)"
Write-Host ""
Write-Host "Prova rapida (cartella dati temporanea, niente avvio automatico, si chiude dopo 5 secondi):" -ForegroundColor DarkGray
Write-Host "  & '$exe' --no-installa --dati `"`$env:TEMP\sol-prova`" --esci-dopo 5"
