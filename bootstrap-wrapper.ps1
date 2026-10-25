$ErrorActionPreference = "Stop"
$url = "https://raw.githubusercontent.com/gradle/gradle/v9.8.2/gradle/wrapper/gradle-wrapper.jar"
$expected = "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98"
$destination = Join-Path $PSScriptRoot "gradle\wrapper\gradle-wrapper.jar"
$temporary = "$destination.$([Guid]::NewGuid().ToString('N')).tmp"
try {
    Invoke-WebRequest -Uri $url -OutFile $temporary
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Gradle wrapper checksum mismatch: expected $expected, got $actual"
    }
    # Keep any working wrapper intact until its replacement has been verified.
    # [NullString] because a plain $null reaches .NET as "", which Replace
    # rejects as a backup path.
    if (Test-Path -LiteralPath $destination) {
        [System.IO.File]::Replace($temporary, $destination, [NullString]::Value)
    } else {
        [System.IO.File]::Move($temporary, $destination)
    }
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
}
Write-Host "Installed verified Gradle 9.8.2 wrapper JAR."
