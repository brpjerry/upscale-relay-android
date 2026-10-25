#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
url="https://raw.githubusercontent.com/gradle/gradle/v9.8.2/gradle/wrapper/gradle-wrapper.jar"
expected="3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98"
destination="$root/gradle/wrapper/gradle-wrapper.jar"
temporary=$(mktemp "${destination}.tmp.XXXXXX")
trap 'rm -f "$temporary"' 0
trap 'exit 1' HUP INT TERM
curl --fail --location "$url" --output "$temporary"
echo "$expected  $temporary" | sha256sum --check --status
chmod 644 "$temporary"
mv -f "$temporary" "$destination"
echo "Installed verified Gradle 9.8.2 wrapper JAR."
