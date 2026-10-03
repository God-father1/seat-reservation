#!/bin/sh
set -e
if [ -s "app.aot" ]; then
    export JAVA_OPTS="$JAVA_OPTS -XX:AOTCache=app.aot"
fi
exec java $JAVA_OPTS -jar seat-reserve-service-0.0.1-SNAPSHOT.jar "$@"
