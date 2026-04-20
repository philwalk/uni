#!/usr/bin/env bash
set -euo pipefail

# Read version from build.sbt — no hardcoding required
VERSION=$(grep -E 'ThisBuild\s*/\s*version\s*:=\s*"[^"]+"' build.sbt | grep -oE '"[^"]+"' | tr -d '"')
TAG="v$VERSION"

echo "==> Release: $TAG"

# 1. Version parsed successfully
[ -n "$VERSION" ] || { echo "ERROR: could not parse version from build.sbt"; exit 1; }

# 2. Remote tag must not already exist — unless the GitHub release is also missing,
#    which means a previous run pushed the tag but crashed before gh release create.
#    In that case skip straight to the release step.
if git ls-remote --tags origin "refs/tags/$TAG" | grep -q .; then
  if gh release view "$TAG" &>/dev/null; then
    echo "ERROR: release $TAG already fully published — bump the version first"
    exit 1
  else
    echo "==> Tag $TAG already on remote but GitHub release missing — resuming from gh release create..."
    RELEASE_NOTES=$(awk "/^## v$VERSION/{found=1; next} found && /^## v/{exit} found{print}" CHANGELOG.md)
    gh release create "$TAG" \
      --title "uni $VERSION" \
      --notes "$RELEASE_NOTES"
    echo "==> Done."
    exit 0
  fi
fi

# 3. Must be on main and not behind origin
[ "$(git branch --show-current)" = "main" ] || { echo "ERROR: not on main branch"; exit 1; }
git fetch --quiet origin main
git status --porcelain --branch | grep -q "behind" && { echo "ERROR: branch is behind origin/main"; exit 1; }

# 4. No unstaged content changes (mode-only diffs are OK)
git diff --stat | grep -v "| 0" | grep -q . && { echo "ERROR: unstaged content changes present"; exit 1; }

# 5. CHANGELOG.md has an entry for this version
grep -q "## v$VERSION" CHANGELOG.md || { echo "ERROR: CHANGELOG.md missing '## v$VERSION' entry"; exit 1; }

# 6. Clean build and all tests must pass
echo "==> Running clean test..."
sbt clean test

# 7. Commit if anything is staged
if git diff --cached --quiet; then
  echo "==> Nothing staged — skipping commit"
else
  git commit -m "Release $TAG"
fi

# 8. Tag locally (-f overwrites any local tag; remote is protected by check #2)
git tag -f "$TAG"

# 9. Push main and tag
echo "==> Pushing main and $TAG..."
git push origin main
git push origin "$TAG"

# 10. Extract this version's changelog section and create GitHub release
echo "==> Creating GitHub release $TAG..."
RELEASE_NOTES=$(awk "/^## v$VERSION/{found=1; next} found && /^## v/{exit} found{print}" CHANGELOG.md)
gh release create "$TAG" \
  --title "uni $VERSION" \
  --notes "$RELEASE_NOTES"

echo "==> Done: https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/releases/tag/$TAG"

# 11. Sonatype publish is triggered automatically by .github/workflows/release.yml
#     when the tag push (step 9) is received by GitHub — no manual sbt step needed.
