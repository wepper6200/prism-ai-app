param([Parameter(Mandatory)][string]$AppDirectory)
$ErrorActionPreference = 'Stop'
$AppDirectory = (Resolve-Path -LiteralPath $AppDirectory).Path
$runtimeText = [IO.File]::ReadAllText((Join-Path $AppDirectory 'runtime/release'))
$runtimeVersion = [regex]::Match($runtimeText, '(?m)^JAVA_VERSION="(\d+)')
if (-not $runtimeVersion.Success) { throw 'Cannot identify the bundled Java runtime' }
$javaMajor = [int]$runtimeVersion.Groups[1].Value
$maxSupported = $javaMajor + 44
$maxFound = 0
$classes = 0
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in (Get-ChildItem -LiteralPath (Join-Path $AppDirectory 'app') -Filter '*.jar' -File)) {
    $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.EndsWith('.class', [StringComparison]::Ordinal)) { continue }
            if ($entry.FullName -match '^META-INF/versions/(\d+)/' -and [int]$Matches[1] -gt $javaMajor) { continue }
            $stream = $entry.Open()
            try {
                $header = [byte[]]::new(8)
                $read = 0
                while ($read -lt 8) {
                    $count = $stream.Read($header, $read, 8 - $read)
                    if ($count -eq 0) { throw "Truncated class: $($jar.Name)/$($entry.FullName)" }
                    $read += $count
                }
                if ($header[0] -ne 202 -or $header[1] -ne 254 -or $header[2] -ne 186 -or $header[3] -ne 190) { throw 'Invalid class header' }
                $major = $header[6] * 256 + $header[7]
                if ($major -gt $maxSupported) {
                    throw "Incompatible Java bytecode: $($jar.Name)/$($entry.FullName) has class version $major; bundled Java $javaMajor supports up to $maxSupported. Rebuild with the desktop-java target from libs.versions.toml."
                }
                $maxFound = [Math]::Max($maxFound, $major)
                $classes++
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}
if ($classes -eq 0) { throw 'No application classes found' }
"Verified $classes classes: maximum class version $maxFound, bundled Java $javaMajor"
