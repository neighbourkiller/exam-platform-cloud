#!/usr/bin/env sh
set -eu

APP_JAR=/app/runtime/app.jar
ADDON_JAR=/app/runtime/timeout-observer-addon.jar

if [ "${TIMEOUT_OBSERVATION_ENABLED:-false}" = "true" ]; then
  if [ ! -f "$ADDON_JAR" ]; then
    echo "timeout-observation enabled but the isolated observer add-on is missing" >&2
    exit 78
  fi
  exec java \
    -Dloader.path="$ADDON_JAR" \
    -cp "$APP_JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher
fi

exec java -jar "$APP_JAR"
