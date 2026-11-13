#!/bin/bash
# Runs the CodeRabbit loop in watch mode (scripts/review/README.md, "Running the loop"), appending
# its output to DIR/loop.log. Started by `loop_task.py launch` (the scheduled task runs the copy
# installed in DIR/bin), or by hand; a second copy exits at once (the loop holds DIR/loop.lock),
# and after `--stop` it does not start.
#
# Environment: REVIEW_DIR (default: $WLX_REVIEW_DIR, else %LOCALAPPDATA%/wlxjury-review), GH
# (default: the maintainer's gh.exe), PYTHON (default: python). Extra loop arguments, e.g.
# `--first 218`, go in DIR/loop.args (one line), so they change without registering the task again.
set -u
here=$(cd "$(dirname "$0")" && pwd)
default_dir=${WLX_REVIEW_DIR:-${LOCALAPPDATA:-$HOME/.local/state}/wlxjury-review}
if command -v cygpath > /dev/null; then default_dir=$(cygpath -u "$default_dir"); fi
dir=${REVIEW_DIR:-$default_dir}
version=$(head -n 1 "$here/VERSION" 2> /dev/null || echo "a checkout")
export GH=${GH:-/c/Users/ilya/tools/gh/bin/gh.exe}
export PYTHONIOENCODING=utf-8
py=${PYTHON:-python}
mkdir -p "$dir"
args=()
if [ -f "$dir/loop.args" ]; then
  read -r -a args < "$dir/loop.args" || true
fi
{
  echo "--- start $(date -u '+%Y-%m-%d %H:%M:%S')Z bash pid $$, code in $here ($version), args: ${args[*]:-none}"
  "$py" -I "$here/coderabbit_loop.py" --dir "$dir" --post "${args[@]}"
  echo "--- exit $? $(date -u '+%Y-%m-%d %H:%M:%S')Z"
} >> "$dir/loop.log" 2>&1
