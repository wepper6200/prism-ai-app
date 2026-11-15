param(
    [string]$AppDirectory,
    [string]$OutputDirectory,
    [string]$InstallerAppId = "ai.prism.desktop",
    [string]$InstallerAppName = "Prism AI"
)
$ErrorActionPreference = "Stop"
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$catalog = [IO.File]::ReadAllText((Join-Path $projectRoot "gradle/libs.versions.toml"))
function Read-CatalogVersion([string]$name) {
    $match = [regex]::Match($catalog, '(?m)^' + [regex]::Escape($name) + ' = "([^"]+)"')
    if (-not $match.Success) { throw "Missing version: $name" }
    return $match.Groups[1].Value
}
$appVersion = Read-CatalogVersion "prism-versionName"
$innoVersion = Read-CatalogVersion "inno-setup"
$innoHash = Read-CatalogVersion "inno-setup-sha256"
if (-not $AppDirectory) { $AppDirectory = Join-Path $projectRoot "desktopApp/build/compose/binaries/main/app/PrismAI" }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot "build/installer" }
$AppDirectory = (Resolve-Path -LiteralPath $AppDirectory).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
foreach ($required in @("PrismAI.exe", "app/PrismAI.cfg", "runtime/bin/server/jvm.dll", "app/resources/prism_llama.dll")) {
    if (-not (Test-Path -LiteralPath (Join-Path $AppDirectory $required) -PathType Leaf)) {
        throw "Incomplete Windows application: $required"
    }
}
$toolRoot = Join-Path $projectRoot "build/tools"
$compilerDir = Join-Path $toolRoot "inno-$innoVersion"
$compiler = Join-Path $compilerDir "ISCC.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $download = Join-Path $toolRoot "innosetup-$innoVersion-x64.exe"
    $releaseTag = "is-" + $innoVersion.Replace(".", "_")
    Invoke-WebRequest -Uri "https://github.com/jrsoftware/issrc/releases/download/$releaseTag/innosetup-$innoVersion-x64.exe" -OutFile $download
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ine $innoHash) { throw "Compiler checksum mismatch" }
    $signature = Get-AuthenticodeSignature -FilePath $download
    if ($signature.Status -ne "Valid" -or $signature.SignerCertificate.Subject -notlike "*Pyrsys B.V.*") {
        throw "Compiler publisher verification failed"
    }
    $extract = Start-Process -FilePath $download -ArgumentList @(
        "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CURRENTUSER", "/PORTABLE=1", "/NOICONS",
        ('/DIR="' + $compilerDir + '"')
    ) -WindowStyle Hidden -PassThru -Wait
    if ($extract.ExitCode -ne 0) { throw "Compiler extraction failed: $($extract.ExitCode)" }
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& $compiler "/DAppVersion=$appVersion" "/DAppSourceDir=$AppDirectory" "/DOutputDirectory=$OutputDirectory" "/DInstallerAppId=$InstallerAppId" "/DInstallerAppName=$InstallerAppName" (Join-Path $PSScriptRoot "PrismAI.iss")
if ($LASTEXITCODE -ne 0) { throw "Installer compilation failed: $LASTEXITCODE" }
$setup = Join-Path $OutputDirectory "PrismAI-$appVersion-windows-x64-Setup.exe"
if (-not (Test-Path -LiteralPath $setup)) { throw "Installer output missing" }
Write-Output $setup
