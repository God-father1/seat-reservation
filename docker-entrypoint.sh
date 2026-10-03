#!/bin/sh
set -e

if [ -n "$DATABASE_URL" ]; then
    # Convert postgres:// or postgresql:// to jdbc:postgresql://
    JDBC_URL=$(echo "$DATABASE_URL" | sed -e 's|^postgres://|jdbc:postgresql://|' -e 's|^postgresql://|jdbc:postgresql://|')
    export DB_URL="$JDBC_URL"
fi

if [ -s "app.aot" ]; then
    export JAVA_OPTS="$JAVA_OPTS -XX:AOTCache=app.aot"
fi
exec java $JAVA_OPTS -jar seat-reserve-service-0.0.1-SNAPSHOT.jar "$@"
