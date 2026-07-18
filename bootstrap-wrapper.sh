#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
url="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
expected="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
destination="$root/gradle/wrapper/gradle-wrapper.jar"
curl --fail --location "$url" --output "$destination"
echo "$expected  $destination" | sha256sum --check --status
echo "Installed verified Gradle 8.13 wrapper JAR."
