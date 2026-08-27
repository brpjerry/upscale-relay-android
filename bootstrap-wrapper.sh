#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
url="https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar"
expected="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
destination="$root/gradle/wrapper/gradle-wrapper.jar"
curl --fail --location "$url" --output "$destination"
echo "$expected  $destination" | sha256sum --check --status
echo "Installed verified Gradle 9.7.1 wrapper JAR."
