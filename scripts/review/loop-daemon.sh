#!/bin/bash
# Runs the CodeRabbit loop in watch mode (scripts/review/README.md, "Running the loop"), appending
# its output to DIR/loop.log. Started by `loop_task.py launch` (the scheduled task, or by hand);
# a second copy exits at once (the loop holds DIR/loop.lock), and after `--stop` it does not start.
#
# Environment: REVIEW_DIR (default: <this checkout>/target/review), GH (default: the maintainer's
# gh.exe), PYTHON (default: python). Extra loop arguments, e.g. `--first 218`, go in DIR/loop.args
# (one line), so they change without registering the task again.
set -u
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
dir=${REVIEW_DIR:-$repo/target/review}
export GH=${GH:-/c/Users/ilya/tools/gh/bin/gh.exe}
export PYTHONIOENCODING=utf-8
py=${PYTHON:-python}
mkdir -p "$dir"
args=()
if [ -f "$dir/loop.args" ]; then
  read -r -a args < "$dir/loop.args" || true
fi
{
  echo "--- start $(date -u '+%Y-%m-%d %H:%M:%S')Z bash pid $$, $repo, args: ${args[*]:-none}"
  "$py" -I "$here/coderabbit_loop.py" --dir "$dir" --post "${args[@]}"
  echo "--- exit $? $(date -u '+%Y-%m-%d %H:%M:%S')Z"
} >> "$dir/loop.log" 2>&1
