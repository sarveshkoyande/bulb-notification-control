#!/usr/bin/env sh
#
# Gradle startup script for UN*X
#

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DIR=`dirname "$0"`
APP_HOME=`cd "$DIR" && pwd`

# Find gradle in standard location or use system gradle
if [ -x "$APP_HOME/gradle-8.1/bin/gradle" ]; then
    GRADLE_BIN="$APP_HOME/gradle-8.1/bin/gradle"
else
    GRADLE_BIN=`which gradle`
fi

if [ -z "$GRADLE_BIN" ]; then
    echo "Error: gradle not found in PATH or standard location"
    exit 1
fi

exec "$GRADLE_BIN" "$@"
