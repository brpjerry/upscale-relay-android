param(
    [string]$Destination = "$PSScriptRoot\..\player-mpv\src\main\jniLibs\arm64-v8a"
)

$ErrorActionPreference = "Stop"
$url = "https://github.com/mpv-android/mpv-android/releases/download/2026-04-25/app-default-arm64-v8a-release.apk"
$expectedSha256 = "4400bcba6be9cec1128e24d1eba153d8727384926b0639fa7fe44d4e36b04f81"
$work = Join-Path $env:TEMP "relay-mpv-android-3018d47"
$apk = Join-Path $work "mpv-android.apk"

New-Item -ItemType Directory -Force -Path $work, $Destination | Out-Null
Invoke-WebRequest -Uri $url -OutFile $apk
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
if ($actual -ne $expectedSha256) {
    throw "mpv-android checksum mismatch: expected $expectedSha256, got $actual"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $entries = $archive.Entries | Where-Object {
        $_.FullName.StartsWith("lib/arm64-v8a/") -and $_.Name.EndsWith(".so")
    }
    foreach ($entry in $entries) {
        $target = Join-Path $Destination $entry.Name
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
    }
} finally {
    $archive.Dispose()
}

if (-not (Test-Path (Join-Path $Destination "libmpv.so")) -or
    -not (Test-Path (Join-Path $Destination "libplayer.so"))) {
    throw "the pinned APK did not contain libmpv.so and libplayer.so"
}
Write-Host "Installed pinned arm64 mpv libraries in $Destination"

