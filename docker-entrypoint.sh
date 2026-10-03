#!/bin/sh
set -e
if [ -f "app.aot" ]; then
    export JAVA_OPTS="$JAVA_OPTS -XX:AOTCache=app.aot"
fi
exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher "$@"
