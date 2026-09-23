#!/usr/bin/env bash
# Merge the latest Komikku into Hondana's main branch.
#
#   scripts/sync-komikku.sh            # merge upstream/master
#   scripts/sync-komikku.sh v1.15.0    # merge a specific Komikku tag
#
# Afterwards, fix any conflicts (look for HONDANA markers), commit, and push.
# The push triggers CI, which publishes the new APK.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if ! git remote get-url upstream >/dev/null 2>&1; then
    git remote add upstream https://github.com/komikku-app/komikku
    git remote set-url --push upstream DISABLED
fi

target="${1:-upstream/master}"
git fetch upstream master --tags

if [ -n "$(git status --porcelain)" ]; then
    echo "Commit or stash your changes first." >&2
    exit 1
fi

behind=$(git rev-list --count "HEAD..$target")
if [ "$behind" = "0" ]; then
    echo "Already up to date with $target."
    exit 0
fi
echo "Merging $behind Komikku commits from $target..."

# Komikku's workflows need its signing and Firebase secrets; Hondana has its own.
drop_upstream_workflows() {
    for f in .github/workflows/*.yml; do
        [ "$(basename "$f")" = "build.yml" ] && continue
        git rm -q --ignore-unmatch "$f"
    done
}

if git merge --no-edit "$target"; then
    drop_upstream_workflows
    if ! git diff --cached --quiet; then
        git commit -q -m "Drop Komikku's CI workflows again after merging $target"
    fi
    echo
    echo "Merged cleanly. Review with 'git log --oneline -20', then 'git push'."
else
    drop_upstream_workflows
    echo
    echo "Conflicts to resolve (Hondana changes are between HONDANA markers):"
    git diff --name-only --diff-filter=U
    echo
    echo "Fix them, 'git add' the files, 'git commit', then 'git push'."
    exit 1
fi
