$ErrorActionPreference = "Stop"
$url = "https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar"
$expected = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
$destination = Join-Path $PSScriptRoot "gradle\wrapper\gradle-wrapper.jar"
$temporary = "$destination.$([Guid]::NewGuid().ToString('N')).tmp"
try {
    Invoke-WebRequest -Uri $url -OutFile $temporary
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Gradle wrapper checksum mismatch: expected $expected, got $actual"
    }
    # Keep any working wrapper intact until its replacement has been verified.
    if (Test-Path -LiteralPath $destination) {
        [System.IO.File]::Replace($temporary, $destination, $null)
    } else {
        [System.IO.File]::Move($temporary, $destination)
    }
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
}
Write-Host "Installed verified Gradle 9.7.1 wrapper JAR."
