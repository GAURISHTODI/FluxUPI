#!/usr/bin/env bash
#
# Seed a running FluxUPI database with 25 users, credit lines from the three
# mock lenders, and 1,200+ mixed transactions (spends, reversals, repayments).
#
# This is a thin wrapper around the `seed` Spring profile — the actual work is
# in com.fluxupi.seed.SeedRunner, which drives the same public services the
# REST API uses and prints a ledger-reconciliation report at the end.
#
# Usage:
#   docker compose up -d postgres      # or point at any Postgres via env vars
#   ./scripts/seed_transactions.sh
#
# Honours the standard Spring datasource env vars if set:
#   SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD

set -euo pipefail
cd "$(dirname "$0")/.."

echo "Seeding FluxUPI (profile=seed)…"
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=seed

echo
echo "Seed run finished. Look for the 'Seed complete' line above:"
echo "  balanced=true means SUM(debit) == SUM(credit) across every ledger entry."
