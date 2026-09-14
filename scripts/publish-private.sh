#!/usr/bin/env bash
# Run on your own machine after `gh auth login`.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
command -v gh >/dev/null 2>&1 || { echo 'Install GitHub CLI from https://cli.github.com/ first.' >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo 'Run gh auth login first, then run this script again.' >&2; exit 1; }
cd -- "$project_dir"
if git_root="$(git rev-parse --show-toplevel 2>/dev/null)"; then
    [[ "$git_root" == "$project_dir" ]] || { echo 'Move this project outside the surrounding Git repository first.' >&2; exit 1; }
else
    git init -b main
fi
if git remote get-url origin >/dev/null 2>&1; then
    echo 'This project already has an origin remote. Review it before publishing; nothing was changed.' >&2
    exit 1
fi
github_owner="$(gh api user --jq '.login')"
repo_full_name="$github_owner/vaultdown-android"
if gh repo view "$repo_full_name" >/dev/null 2>&1; then
    echo "Repository $repo_full_name already exists. Nothing was pushed; choose another name or review that repository." >&2
    exit 1
fi
git add .
if ! git rev-parse --verify HEAD >/dev/null 2>&1 || ! git diff --cached --quiet; then
    git commit -m 'Build Vaultdown Android Markdown editor with GitHub sync'
fi
gh repo create "$repo_full_name" --private --source "$project_dir" --remote origin --push \
    --description 'Native Android Markdown notebook with a folder tree and automatic GitHub sync'
gh repo view "$repo_full_name" --json isPrivate,url --jq 'if .isPrivate then .url else error("Unexpected repository visibility; inspect immediately") end'
