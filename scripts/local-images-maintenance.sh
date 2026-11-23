#!/usr/bin/env bash
# local-images-maintenance.sh - report and prune the local image cache
#
# The image cache (wlxjury.thumbs.local-path, default /var/www/jury-images) holds the
# Commons thumbnails the app downloads. It only grows; this script shows how big it is and
# deletes old files, never the app's .cache-sizes marker or its .tmp-* files. Run it as the
# service user (or root): it deletes regular files only and never follows symlinks below the
# cache folder (the folder itself may be a symlink).
#
# Usage:
#   local-images-maintenance.sh report [--csv] [--path PATH]
#   local-images-maintenance.sh prune (--days N | --months N) [--dry-run] [--quiet] [--path PATH]
#   local-images-maintenance.sh help
#
# Subcommands:
#   report   Count and total size of cached files per modification month (YYYY-MM).
#   prune    Delete files whose modification time is older than the threshold, then remove
#            the directories that became empty. Run with --dry-run first.
#
# Options:
#   -p, --path PATH      Cache directory (default: $WLXJURY_THUMBS_LOCAL_PATH, else
#                        /var/www/jury-images). Must be an existing directory, not "/".
#   -d, --days N         prune: files older than N days (N >= 1)
#   -m, --months N       prune: files older than N calendar months (N >= 1); not with --days
#   -n, --dry-run        prune: list what would be deleted, delete nothing
#   -q, --quiet          prune: no per-file lines, only the summary
#       --csv            report: month,files,bytes lines and a TOTAL line
#   -h, --help           This text
#
# Exit status: 0 on success; 1 when a file could not be deleted or the scan hit an error (for
# example an unreadable folder): the figures shown are then partial; 2 on a usage error.
#
# Needs GNU find and date (Debian, Ubuntu, RHEL). File names with spaces, tabs or line
# breaks are handled.
#
# Examples:
#   local-images-maintenance.sh report
#   local-images-maintenance.sh prune --months 6 --dry-run
#   local-images-maintenance.sh prune --days 180 --quiet

set -euo pipefail

CACHE_PATH="${WLXJURY_THUMBS_LOCAL_PATH:-/var/www/jury-images}"
DRY_RUN=false
QUIET=false
CSV=false
DAYS=""
MONTHS=""
# The app's bookkeeping file in the cache root (LocalImageCacheService.SizesMarker); .tmp-* files
# are its writes in progress. Neither is a cached image: never counted or deleted.
APP_FILES_MARKER=".cache-sizes"

usage() {
  sed -n '2,/^$/{/^#/s/^# \{0,1\}//p}' "$0"
}

die() {
  echo "Error: $*" >&2
  echo "Run '$0 help' for usage." >&2
  exit 2
}

human_size() {
  awk -v b="${1:-0}" 'BEGIN {
    s = "BKMGT"; p = 0
    while (b >= 1024 && p < 4) { b /= 1024; p++ }
    printf "%.1f%s\n", b, substr(s, p + 1, 1)
  }'
}

# An option's value: refuse a missing one (set -u would give an unreadable error).
need_value() {
  [[ $# -ge 2 && -n "$2" ]] || die "option $1 needs a value"
}

[[ $# -gt 0 ]] || { usage; exit 2; }
SUBCMD="$1"
shift
case "$SUBCMD" in
  report | prune) ;;
  -h | --help | help) usage; exit 0 ;;
  *) die "unknown subcommand: $SUBCMD" ;;
esac

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p | --path) need_value "$@"; CACHE_PATH="$2"; shift 2 ;;
    -d | --days) need_value "$@"; DAYS="$2"; shift 2 ;;
    -m | --months) need_value "$@"; MONTHS="$2"; shift 2 ;;
    -n | --dry-run) DRY_RUN=true; shift ;;
    -q | --quiet) QUIET=true; shift ;;
    --csv) CSV=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ -n "$CACHE_PATH" && "$CACHE_PATH" != "/" ]] || die "refusing to use '$CACHE_PATH' as the cache path"
[[ -d "$CACHE_PATH" ]] || die "cache path is not a directory: $CACHE_PATH"
# The cache folder may itself be a symlink (deployment.md allows it): find does not follow a
# symlink given as its start point, so it would see an empty cache. Resolve it once; files and
# folders below it are still never followed.
CACHE_PATH=$(readlink -f -- "$CACHE_PATH")
[[ -n "$CACHE_PATH" && "$CACHE_PATH" != "/" && -d "$CACHE_PATH" ]] \
  || die "refusing to use the cache path (it resolves to '$CACHE_PATH')"
find "$CACHE_PATH" -maxdepth 0 -printf '' 2>/dev/null || die "GNU find is required (find -printf)"

# The scan's records go to a file, so that find's exit status is known: find keeps going after
# an error (an unreadable folder) and exits 1, which must never end as a quiet success.
SCAN=$(mktemp) || { echo "Error: cannot create a temporary file" >&2; exit 1; }
trap 'rm -f -- "$SCAN"' EXIT
scan_failed=false
scan() {
  find "$CACHE_PATH" -type f ! -name "$APP_FILES_MARKER" ! -name '.tmp-*' -printf "$1" >"$SCAN" || scan_failed=true
}
# After the results are shown: say so, and fail, if the scan was incomplete.
scan_check() {
  if $scan_failed; then
    echo "Error: the scan of $CACHE_PATH hit errors (see above); the figures are partial." >&2
    exit 1
  fi
}

if [[ "$SUBCMD" == "report" ]]; then
  [[ -z "$DAYS$MONTHS" ]] || die "--days and --months belong to prune"
  scan '%TY-%Tm %s\n'
  report_tsv=$(
    awk '{ files[$1]++; bytes[$1] += $2 }
             END { for (m in files) printf "%s\t%d\t%d\n", m, files[m], bytes[m] }' "$SCAN" \
      | sort
  )
  total_files=0
  total_bytes=0
  while IFS=$'\t' read -r month m_files m_bytes; do
    [[ -n "$month" ]] || continue
    total_files=$((total_files + m_files))
    total_bytes=$((total_bytes + m_bytes))
  done <<<"$report_tsv"

  if $CSV; then
    echo "month,files,bytes"
    while IFS=$'\t' read -r month m_files m_bytes; do
      [[ -n "$month" ]] || continue
      echo "$month,$m_files,$m_bytes"
    done <<<"$report_tsv"
    echo "TOTAL,$total_files,$total_bytes"
    scan_check
    exit 0
  fi

  echo "Local images: size by modification month"
  echo "Path: $CACHE_PATH"
  printf '%-10s  %10s  %12s\n' "Month" "Files" "Size"
  while IFS=$'\t' read -r month m_files m_bytes; do
    [[ -n "$month" ]] || continue
    printf '%-10s  %10d  %12s\n' "$month" "$m_files" "$(human_size "$m_bytes")"
  done <<<"$report_tsv"
  printf '%-10s  %10d  %12s\n' "TOTAL" "$total_files" "$(human_size "$total_bytes")"
  scan_check
  exit 0
fi

# prune
[[ "$CSV" == false ]] || die "--csv belongs to report"
if [[ -n "$DAYS" && -n "$MONTHS" ]]; then
  die "--days and --months are mutually exclusive"
elif [[ -n "$DAYS" ]]; then
  [[ "$DAYS" =~ ^[0-9]+$ && "$DAYS" -ge 1 ]] || die "--days must be a positive integer (got: '$DAYS')"
  CUTOFF_EPOCH=$(date -d "-$DAYS days" +%s)
  CUTOFF_DESC="$DAYS day(s)"
elif [[ -n "$MONTHS" ]]; then
  [[ "$MONTHS" =~ ^[0-9]+$ && "$MONTHS" -ge 1 ]] || die "--months must be a positive integer (got: '$MONTHS')"
  CUTOFF_EPOCH=$(date -d "-$MONTHS months" +%s)
  CUTOFF_DESC="$MONTHS month(s)"
else
  die "prune needs --days N or --months N"
fi

mode="live"
$DRY_RUN && mode="dry run, nothing is deleted"
echo "Local images: prune ($mode)"
echo "Path: $CACHE_PATH"
echo "Older than $CUTOFF_DESC (modified before $(date -d "@$CUTOFF_EPOCH" '+%Y-%m-%d %H:%M:%S'))"

count=0
bytes=0
failed=0
# NUL-terminated records "mtime<TAB>size<TAB>path": the path is last, so any file name works.
scan '%T@\t%s\t%p\0'
while IFS= read -r -d '' record; do
  mtime=${record%%$'\t'*}
  rest=${record#*$'\t'}
  size=${rest%%$'\t'*}
  file=${rest#*$'\t'}
  (("${mtime%.*}" < CUTOFF_EPOCH)) || continue

  if $DRY_RUN; then
    $QUIET || printf 'would delete: %q (%s)\n' "$file" "$(human_size "$size")"
    count=$((count + 1))
    bytes=$((bytes + size))
  elif rm -f -- "$file"; then
    $QUIET || printf 'deleted: %q (%s)\n' "$file" "$(human_size "$size")"
    count=$((count + 1))
    bytes=$((bytes + size))
  else
    printf 'failed to delete: %q\n' "$file" >&2
    failed=$((failed + 1))
  fi
done <"$SCAN"

if ! $DRY_RUN; then
  find "$CACHE_PATH" -mindepth 1 -type d -empty -delete || scan_failed=true
fi

if $DRY_RUN; then
  echo "Would delete $count file(s), freeing $(human_size "$bytes") ($bytes bytes)"
else
  echo "Deleted $count file(s), freed $(human_size "$bytes") ($bytes bytes)"
  [[ "$failed" -eq 0 ]] || echo "Could not delete $failed file(s)" >&2
fi
scan_check
[[ "$failed" -eq 0 ]]
