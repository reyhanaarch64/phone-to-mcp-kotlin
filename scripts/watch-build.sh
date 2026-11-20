#!/usr/bin/env bash
set -euo pipefail

repo="reyhanaarch64/phone-to-mcp-kotlin"
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
branch="$(git -C "$project_dir" branch --show-current)"
head_sha="$(git -C "$project_dir" rev-parse HEAD)"
run_id="${1:-}"

if [[ -z "$run_id" ]]; then
    for attempt in {1..24}; do
        run_id="$(gh run list --repo "$repo" --workflow android.yml --branch "$branch" --limit 10 --json databaseId,headSha --jq "map(select(.headSha == \"$head_sha\")) | first | .databaseId // empty")"
        [[ -n "$run_id" ]] && break
        sleep 5
    done
fi

if [[ ! "$run_id" =~ ^[0-9]+$ ]]; then
    echo "no GitHub Actions run found for commit $head_sha on branch $branch" >&2
    exit 1
fi

gh run watch "$run_id" --repo "$repo" --exit-status

destination="/root/apk"
download_dir="$destination/.mcp-debug-download"
mkdir -p "$destination" "$download_dir"
rm -f "$download_dir/mcp-debug.apk"
gh run download "$run_id" --repo "$repo" --name phone-to-mcp-debug --dir "$download_dir"
test -s "$download_dir/mcp-debug.apk"
install -m 0644 "$download_dir/mcp-debug.apk" "$destination/mcp-debug.apk"
rm -f "$download_dir/mcp-debug.apk"
rmdir "$download_dir"
printf 'saved %s (%s bytes)\n' "$destination/mcp-debug.apk" "$(stat -c %s "$destination/mcp-debug.apk")"
