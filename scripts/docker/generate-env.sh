#!/usr/bin/env bash
# Gives docker-compose.yml's secrets random values in .env (or the file named as $1): each of
# WLXJURY_COMPOSE_DB_PASSWORD, WLXJURY_COMPOSE_DB_ROOT_PASSWORD, PLAY_HTTP_SECRET_KEY and
# WLXJURY_SETUP_TOKEN that is missing or
# empty (a CR alone, from a Windows editor, counts as empty) gets 64 random hex characters. A
# value already there is kept, so it is safe to rerun (and the database passwords must stay the
# ones the volume was created with). The values are never printed or passed as arguments: read
# the setup token from the file. The file ends up mode 600, also if it existed.
set -euo pipefail
# every file this script creates, the temporary ones included, is the owner's only
umask 077

file="${1:-$(cd "$(dirname "$0")/../.." && pwd)/.env}"
names=(WLXJURY_COMPOSE_DB_PASSWORD WLXJURY_COMPOSE_DB_ROOT_PASSWORD PLAY_HTTP_SECRET_KEY WLXJURY_SETUP_TOKEN)

random_hex() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 32
  else od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; echo
  fi
}

[ -e "$file" ] || : > "$file"

tmp="$(mktemp "${file}.XXXXXX")"
trap 'rm -f "$tmp" "$tmp.new"' EXIT
cat "$file" > "$tmp"

added=()
for name in "${names[@]}"; do
  # set: a value that starts with something other than a control character (a CR)
  if grep -Eq "^${name}=[^[:cntrl:]]" "$tmp"; then continue; fi
  if grep -Eq "^${name}="$'\r'"?$" "$tmp"; then
    # fill the empty line in place (the template's), keeping the file's order and comments; the
    # value goes through the environment, not awk's command line
    N="$name" V="$(random_hex)" awk '
      { line = $0; sub(/\r$/, "", line) }
      line == ENVIRON["N"] "=" { print ENVIRON["N"] "=" ENVIRON["V"]; next }
      { print }' "$tmp" > "$tmp.new"
    cat "$tmp.new" > "$tmp" && rm -f "$tmp.new"
  else
    # a file without a trailing newline would join the new line to its last one
    if [ -s "$tmp" ] && [ "$(tail -c 1 "$tmp")" != "" ]; then echo >> "$tmp"; fi
    printf '%s=%s\n' "$name" "$(random_hex)" >> "$tmp"
  fi
  added+=("$name")
done

cat "$tmp" > "$file"
chmod 600 "$file"
if [ ${#added[@]} -eq 0 ]; then echo "$file: all secrets already set"
else echo "$file: generated ${added[*]}"
fi
