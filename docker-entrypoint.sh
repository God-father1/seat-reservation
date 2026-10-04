#!/bin/sh
set -e

if [ -z "$DB_URL" ]; then
    TARGET_URL="${DATABASE_URL:-${POSTGRES_URL:-$DATABASE_PUBLIC_URL}}"
    if [ -n "$TARGET_URL" ]; then
        # Parse postgresql://user:password@host:port/database
        # Strip scheme
        WITHOUT_SCHEME=$(echo "$TARGET_URL" | sed -e 's|^postgres://||' -e 's|^postgresql://||')
        # Extract user:password (everything before @)
        USERINFO=$(echo "$WITHOUT_SCHEME" | sed 's|@.*||')
        # Extract host:port/database (everything after @)
        HOSTPART=$(echo "$WITHOUT_SCHEME" | sed 's|^[^@]*@||')
        # Split user and password
        PARSED_USER=$(echo "$USERINFO" | cut -d: -f1)
        PARSED_PASS=$(echo "$USERINFO" | cut -d: -f2-)
        # Build clean JDBC URL (no credentials in URL)
        export DB_URL="jdbc:postgresql://${HOSTPART}"
        export DB_USER="$PARSED_USER"
        export DB_PASSWORD="$PARSED_PASS"
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
