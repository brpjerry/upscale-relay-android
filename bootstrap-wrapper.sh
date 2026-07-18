#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
url="https://raw.githubusercontent.com/gradle/gradle/v9.6.1/gradle/wrapper/gradle-wrapper.jar"
expected="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
destination="$root/gradle/wrapper/gradle-wrapper.jar"
curl --fail --location "$url" --output "$destination"
echo "$expected  $destination" | sha256sum --check --status
echo "Installed verified Gradle 9.6.1 wrapper JAR."
