$ErrorActionPreference = "Stop"
$url = "https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar"
$expected = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
$destination = Join-Path $PSScriptRoot "gradle\wrapper\gradle-wrapper.jar"
Invoke-WebRequest -Uri $url -OutFile $destination
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
if ($actual -ne $expected) {
    Remove-Item -LiteralPath $destination -Force
    throw "Gradle wrapper checksum mismatch: expected $expected, got $actual"
}
Write-Host "Installed verified Gradle 9.7.1 wrapper JAR."
