#!/usr/bin/env bash
# Gives docker-compose.yml's secrets random values in .env (or the file named as $1): each of
# WLXJURY_COMPOSE_DB_PASSWORD, PLAY_HTTP_SECRET_KEY and WLXJURY_SETUP_TOKEN that is missing or
# empty gets 64 random hex characters. A value already there is kept, so it is safe to rerun
# (and the database password must stay the one the volume was created with). The values are
# never printed: read the setup token from the file. Creates the file mode 600 if missing.
set -euo pipefail

file="${1:-$(cd "$(dirname "$0")/../.." && pwd)/.env}"
names=(WLXJURY_COMPOSE_DB_PASSWORD PLAY_HTTP_SECRET_KEY WLXJURY_SETUP_TOKEN)

random_hex() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 32
  else od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; echo
  fi
}

if [ ! -e "$file" ]; then (umask 077 && : > "$file"); fi

tmp="$(mktemp "${file}.XXXXXX")"
trap 'rm -f "$tmp" "$tmp.new"' EXIT
cp -p "$file" "$tmp"

added=()
for name in "${names[@]}"; do
  if grep -Eq "^${name}=.+" "$tmp"; then continue; fi
  value="$(random_hex)"
  if grep -Eq "^${name}=$" "$tmp"; then
    # fill the empty line in place (the template's), keeping the file's order and comments
    awk -v n="$name" -v v="$value" '$0 == n "=" { print n "=" v; next } { print }' "$tmp" > "$tmp.new"
    cat "$tmp.new" > "$tmp" && rm -f "$tmp.new"
  else
    # a file without a trailing newline would join the new line to its last one
    if [ -s "$tmp" ] && [ "$(tail -c 1 "$tmp")" != "" ]; then echo >> "$tmp"; fi
    printf '%s=%s\n' "$name" "$value" >> "$tmp"
  fi
  added+=("$name")
done

cat "$tmp" > "$file"
if [ ${#added[@]} -eq 0 ]; then echo "$file: all secrets already set"
else echo "$file: generated ${added[*]}"
fi
