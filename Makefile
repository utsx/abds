export CLICKHOUSE_UID = $(shell id -u)
export CLICKHOUSE_GID = $(shell id -g)
COMPOSE = docker compose --env-file .env.local
DATABASE = $(shell sed -n 's/^CLICKHOUSE_DATABASE=//p' .env.local)
TABLES = dim_realty dim_gauges dim_tariffs dim_users fact_gauge_bindings fact_meter_readings fact_invoice

.PHONY: run stop gen load setup teardown

run:
	mkdir -p data volume
	$(COMPOSE) up -d --build --wait

stop:
	$(COMPOSE) down

gen:
	mkdir -p data
	$(COMPOSE) run --rm --no-deps app --generate

load:
	@set -e; for table in $(TABLES); do \
		./scripts/clickhouse.sh --query "TRUNCATE TABLE IF EXISTS \`$$table\`"; \
		./scripts/clickhouse.sh --query "INSERT INTO \`$$table\` FORMAT CSV" < "data/$$table.csv"; \
	done

setup:
	./scripts/clickhouse.sh --maintenance --param_db="$(DATABASE)" --query 'CREATE DATABASE IF NOT EXISTS {db:Identifier}'
	./scripts/clickhouse.sh --multiquery < sql/schema.sql
	$(MAKE) gen
	$(MAKE) load

teardown:
	./scripts/clickhouse.sh --maintenance --param_db="$(DATABASE)" --query 'DROP DATABASE IF EXISTS {db:Identifier} SYNC'
