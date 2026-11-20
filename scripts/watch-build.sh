#!/usr/bin/env bash
set -euo pipefail

repo="reyhanaarch64/phone-to-mcp-kotlin"
branch="$(git -C "$(dirname "$0")/.." branch --show-current)"
run_id="${1:-}"

if [[ -z "$run_id" ]]; then
    run_id="$(gh run list --repo "$repo" --workflow android.yml --branch "$branch" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
fi

if [[ ! "$run_id" =~ ^[0-9]+$ ]]; then
    echo "no matching GitHub Actions run found for branch $branch" >&2
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
