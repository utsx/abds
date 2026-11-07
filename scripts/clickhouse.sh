#!/bin/sh
set -eu
exec docker compose --env-file .env.local exec -T clickhouse sh -c '
    database="$CLICKHOUSE_DATABASE"
    if [ "${1:-}" = "--maintenance" ]; then
        database=default
        shift
    fi
    exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$database" "$@"
' clickhouse-client "$@"
