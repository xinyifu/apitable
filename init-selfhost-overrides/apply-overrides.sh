#!/bin/sh
set -eu

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

TABLE_PREFIX="${DATABASE_TABLE_PREFIX:-apitable_}"
WAIT_ATTEMPTS="${MYSQL_WAIT_ATTEMPTS:-60}"
WAIT_INTERVAL_SECONDS="${MYSQL_WAIT_INTERVAL_SECONDS:-2}"

case "${TABLE_PREFIX}" in
  *[!A-Za-z0-9_]*)
    echo "Invalid DATABASE_TABLE_PREFIX: ${TABLE_PREFIX}" >&2
    exit 1
    ;;
esac

echo "Waiting for MySQL at ${DB_HOST}:${DB_PORT}/${DB_NAME}..."
attempt=1
while ! mysql \
  --protocol=tcp \
  -h"${DB_HOST}" \
  -P"${DB_PORT}" \
  -u"${DB_USERNAME}" \
  -p"${DB_PASSWORD}" \
  --default-character-set=utf8mb4 \
  "${DB_NAME}" \
  -e "SELECT 1" >/dev/null 2>&1; do
  if [ "${attempt}" -ge "${WAIT_ATTEMPTS}" ]; then
    echo "MySQL is not ready after ${WAIT_ATTEMPTS} attempts" >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep "${WAIT_INTERVAL_SECONDS}"
done

tmp_file="$(mktemp)"
trap 'rm -f "${tmp_file}"' EXIT

for sql_file in /overrides/sql/*.sql; do
  echo "Loading override SQL: ${sql_file}"
  sed "s/__TABLE_PREFIX__/${TABLE_PREFIX}/g" "${sql_file}" >> "${tmp_file}"
  printf '\n' >> "${tmp_file}"
done

echo "Applying self-host overrides..."
mysql \
  --protocol=tcp \
  -h"${DB_HOST}" \
  -P"${DB_PORT}" \
  -u"${DB_USERNAME}" \
  -p"${DB_PASSWORD}" \
  --default-character-set=utf8mb4 \
  "${DB_NAME}" < "${tmp_file}"

echo "Self-host overrides applied."
