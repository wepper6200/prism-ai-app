param([Parameter(Mandatory)][string]$AppDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$catalog = [IO.File]::ReadAllText((Join-Path $projectRoot 'gradle/libs.versions.toml'))
function CatalogValue([string]$name) {
    $match = [regex]::Match($catalog, '(?m)^' + [regex]::Escape($name) + ' = "([^"]+)"')
    if (-not $match.Success) { throw "Missing catalog value: $name" }
    $match.Groups[1].Value
}
$version = CatalogValue 'prism-versionName'
$compilerVersion = CatalogValue 'inno-setup'
$compiler = Join-Path $projectRoot "build/tools/inno-$compilerVersion/ISCC.exe"
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Run installer/build.ps1 first' }
$testId = [guid]::NewGuid().ToString('N')
$testRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot "build/setup-autolaunch-$testId"))
$workspacePrefix = $projectRoot.TrimEnd('\') + '\'
if (-not $testRoot.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test target escapes workspace' }
$source = Join-Path $testRoot 'source'
$installed = Join-Path $testRoot 'Prism AI Launch Test'
$record = Join-Path $testRoot 'launched.txt'
$classes = Join-Path $testRoot 'classes'
New-Item -ItemType Directory -Force -Path $testRoot,$classes | Out-Null
Copy-Item -LiteralPath (Resolve-Path -LiteralPath $AppDirectory).Path -Destination $source -Recurse

# Exercise the real EXE launcher and bundled JVM, with a harmless main class.
# The application database, preferences and model folders are never opened.
$java = @'
import java.nio.file.Files;
import java.nio.file.Path;
public final class SetupLaunchProbe {
  public static void main(String[] args) throws Exception {
    Files.writeString(Path.of(System.getProperty("prism.setup.probe.output")),
      "STARTED\n" + System.getProperty("user.dir") + "\n");
  }
}
'@
$javaFile = Join-Path $testRoot 'SetupLaunchProbe.java'
[IO.File]::WriteAllText($javaFile, $java)
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') --release 21 -d $classes $javaFile
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
& (Join-Path $env:JAVA_HOME 'bin/jar.exe') --create --file (Join-Path $source 'app/setup-probe.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
$configPath = Join-Path $source 'app/PrismAI.cfg'
$config = [IO.File]::ReadAllText($configPath)
if (-not $config.Contains('app.mainclass=ai.prism.desktop.MainKt')) { throw 'Unexpected launcher configuration' }
$config = $config.Replace('app.mainclass=ai.prism.desktop.MainKt', 'app.mainclass=SetupLaunchProbe')
$config = $config.Replace('[Application]', '[Application]' + [Environment]::NewLine + 'app.classpath=$APPDIR\setup-probe.jar')
$config += [Environment]::NewLine + 'java-options=-Dprism.setup.probe.output=' + $record + [Environment]::NewLine
[IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))

$script = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'PrismAI.iss'))
$normalFlags = 'nowait postinstall skipifsilent shellexec runasoriginaluser'
if (-not $script.Contains($normalFlags)) { throw 'Unexpected finish-page launch flags' }
# Keep the production launch mechanism; bypass only the interactive checkbox in this test.
$script = $script.Replace($normalFlags, 'nowait shellexec runasoriginaluser')
$script = $script.Replace('SetupIconFile=..\icons\prism.ico', 'SetupIconFile=' + (Join-Path $projectRoot 'desktopApp/icons/prism.ico'))
$scriptPath = Join-Path $testRoot 'probe.iss'
[IO.File]::WriteAllText($scriptPath, $script, [Text.UTF8Encoding]::new($false))
& $compiler "/DAppVersion=$version" "/DAppSourceDir=$source" "/DOutputDirectory=$testRoot" "/DInstallerAppId=ai.prism.autolaunch.$testId" '/DInstallerAppName=Prism AI Launch Test' $scriptPath *> (Join-Path $testRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Probe installer compilation failed' }
$setup = Join-Path $testRoot "PrismAI-$version-windows-x64-Setup.exe"
try {
    $install = Start-Process -FilePath $setup -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CURRENTUSER','/NOICONS','/TASKS=',('/DIR="' + $installed + '"'),('/LOG="' + (Join-Path $testRoot 'install.log') + '"')) -WindowStyle Hidden -Wait -PassThru
    if ($install.ExitCode -ne 0) { throw "Probe installation failed: $($install.ExitCode)" }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path -LiteralPath $record) -and $timer.Elapsed.TotalSeconds -lt 20) { Start-Sleep -Milliseconds 100 }
    if (-not (Test-Path -LiteralPath $record)) { throw "Installed launcher did not run; see $testRoot" }
    $lines = [IO.File]::ReadAllLines($record)
    if ($lines[0] -ne 'STARTED' -or -not $lines[1].Equals($installed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Incorrect launcher working directory' }
    'PASS: installer started the real PrismAI.exe and bundled JVM in the installed directory'
    Start-Sleep -Milliseconds 500
} finally {
    $uninstaller = [IO.Path]::GetFullPath((Join-Path $installed 'unins000.exe'))
    if (-not $uninstaller.StartsWith($testRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test uninstaller path' }
    if (Test-Path -LiteralPath $uninstaller) {
        $remove = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -WindowStyle Hidden -PassThru -Wait
        if ($remove.ExitCode -ne 0) { throw "Test cleanup failed: $($remove.ExitCode)" }
    }
}
