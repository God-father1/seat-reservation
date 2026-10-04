#!/bin/sh
set -e

if [ -z "$DB_URL" ]; then
    TARGET_URL="${DATABASE_URL:-${POSTGRES_URL:-$DATABASE_PUBLIC_URL}}"
    if [ -n "$TARGET_URL" ]; then
        # Convert postgres:// or postgresql:// to jdbc:postgresql://
        JDBC_URL=$(echo "$TARGET_URL" | sed -e 's|^postgres://|jdbc:postgresql://|' -e 's|^postgresql://|jdbc:postgresql://|')
        export DB_URL="$JDBC_URL"
    elif [ -n "$PGHOST" ] || [ -n "$POSTGRES_HOST" ]; then
        HOST="${PGHOST:-$POSTGRES_HOST}"
        PORT="${PGPORT:-${POSTGRES_PORT:-5432}}"
        DB="${PGDATABASE:-${POSTGRES_DB:-seats}}"
        export DB_URL="jdbc:postgresql://${HOST}:${PORT}/${DB}"
        export DB_USER="${PGUSER:-${POSTGRES_USER:-postgres}}"
        export DB_PASSWORD="${PGPASSWORD:-${POSTGRES_PASSWORD:-postgres}}"
    fi
fi

if [ -s "app.aot" ]; then
    export JAVA_OPTS="$JAVA_OPTS -XX:AOTCache=app.aot"
fi
exec java $JAVA_OPTS -jar seat-reserve-service-0.0.1-SNAPSHOT.jar "$@"
