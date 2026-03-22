#!/usr/bin/env bash
set -euo pipefail
VERSION="0.11.2"
TAG="v$VERSION"

# 1. version in build.sbt
grep -q "\"$VERSION\"" build.sbt || { echo "version $VERSION not in build.sbt"; exit 1; }

# 2. tag doesn't exist on REMOTE (fatal - cannot overwrite a published tag)
git ls-remote --tags origin "refs/tags/$TAG" | grep -q . && { echo "remote tag $TAG already exists"; exit 1; }

# 3. on main, up to date with remote
[ "$(git branch --show-current)" = "main" ] || { echo "not on main"; exit 1; }
git fetch --quiet origin main
git status --porcelain --branch | grep -q "behind" && { echo "branch is behind origin"; exit 1; }

# 4. no unstaged content changes (mode-only diffs are OK)
git diff --stat | grep -v "| 0" | grep -q . && { echo "unstaged content changes present"; exit 1; }

# 5. tests
sbt test

# 6. changelog mentions the version
grep -q "$VERSION" CHANGELOG.md || { echo "CHANGELOG.md missing $VERSION entry"; exit 1; }

# commit (no-op if nothing staged)
git diff --cached --quiet && echo "nothing staged, skipping commit" || git commit -m "Release $TAG"

# tag: -f overwrites local tag safely (remote is protected by check #2)
git tag -f "$TAG"

echo "############### $TAG ################" 1>&2
#git push origin main
#git push origin "$TAG"
