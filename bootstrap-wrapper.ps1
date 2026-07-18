$ErrorActionPreference = "Stop"
$url = "https://raw.githubusercontent.com/gradle/gradle/v9.6.1/gradle/wrapper/gradle-wrapper.jar"
$expected = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
$destination = Join-Path $PSScriptRoot "gradle\wrapper\gradle-wrapper.jar"
Invoke-WebRequest -Uri $url -OutFile $destination
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
if ($actual -ne $expected) {
    Remove-Item -LiteralPath $destination -Force
    throw "Gradle wrapper checksum mismatch: expected $expected, got $actual"
}
Write-Host "Installed verified Gradle 9.6.1 wrapper JAR."
