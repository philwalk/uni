#!/usr/bin/env bash
set -euo pipefail

# Read version from build.sbt — no hardcoding required
VERSION=$(grep -E 'ThisBuild\s*/\s*version\s*:=\s*"[^"]+"' build.sbt | grep -oE '"[^"]+"' | tr -d '"')
TAG="v$VERSION"

echo "==> Release: $TAG"

# 1. Version parsed successfully
[ -n "$VERSION" ] || { echo "ERROR: could not parse version from build.sbt"; exit 1; }

# Publish coordinates, likewise read from build.sbt, for the Central check below.
ORG=$(grep -E 'ThisBuild\s*/\s*organization\s*:=\s*"[^"]+"' build.sbt | grep -oE '"[^"]+"' | tr -d '"')
PROJECT=$(grep -E 'lazy val projectName\s*=\s*"[^"]+"' build.sbt | grep -oE '"[^"]+"' | tr -d '"')
SCALA_MAJOR=$(grep -E 'lazy val scala3\s*=\s*"[^"]+"' build.sbt | grep -oE '"[^"]+"' | tr -d '"' | cut -d. -f1)
ARTIFACT="${PROJECT}_${SCALA_MAJOR}"
CENTRAL_POM="https://repo1.maven.org/maven2/${ORG//./\/}/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.pom"

# "Published" has to mean the artifacts are public and immutable, because that is
# the one state a re-release cannot recover from. Two signals, because neither is
# sufficient alone: Central is authoritative but lags the workflow by up to ~30
# minutes, and the workflow's own verdict is immediate but only covers publishes
# that went through CI.
# Takes the Release workflow's conclusion for this tag as $1.
already_published() {
  [ "$1" = "success" ] && return 0
  command -v curl >/dev/null 2>&1 && curl -sfI --max-time 20 "$CENTRAL_POM" >/dev/null 2>&1
}

# 2. Work out what a pre-existing remote tag actually means.
#
#    A first attempt can push the tag and still leave the release unfinished —
#    the CI test job fails, or the script dies before `gh release create`. Both
#    leave a tag naming a version that was never published to anyone, so simply
#    refusing to proceed would force a version bump to work around a CI failure
#    that left no trace in any registry. Instead, ask how far the attempt got.
MODE=fresh
TAG_REFS=$(git ls-remote --tags origin "refs/tags/$TAG" || true)
REMOTE_TAG_REF=$(printf '%s' "$TAG_REFS" | head -1 | cut -f1)     # ref value; a tag object if annotated
REMOTE_TAG_COMMIT=$(printf '%s' "$TAG_REFS" | tail -1 | cut -f1)  # the commit it resolves to

if [ -n "$REMOTE_TAG_REF" ]; then
  RUN=$(gh run list --workflow=release.yml --branch "$TAG" --limit 1 \
          --json status,conclusion -q '.[0] | "\(.status) \(.conclusion)"' 2>/dev/null || true)
  RUN_STATUS=${RUN%% *}
  RUN_CONCLUSION=${RUN##* }

  # Creating a missing GitHub release is purely additive, so it is safe whatever
  # the publish did — and it is the only repair for a run that died between the
  # tag push and `gh release create`. Handle it before any refusal below.
  if ! gh release view "$TAG" &>/dev/null; then
    echo "==> Tag $TAG already on remote but GitHub release missing — resuming from gh release create..."
    RELEASE_NOTES=$(awk "/^## v$VERSION/{found=1; next} found && /^## v/{exit} found{print}" CHANGELOG.md)
    gh release create "$TAG" \
      --title "uni $VERSION" \
      --notes "$RELEASE_NOTES"
    echo "==> Done."
    exit 0
  fi

  # The tag and the release both exist, so the incomplete step is the publish.
  if already_published "$RUN_CONCLUSION"; then
    echo "ERROR: release $TAG already fully published — bump the version first"
    exit 1
  fi

  if [ "$RUN_STATUS" = "in_progress" ] || [ "$RUN_STATUS" = "queued" ]; then
    echo "ERROR: the Release workflow for $TAG is still running — wait for it to finish"
    echo "       gh run watch \$(gh run list --workflow=release.yml --branch $TAG --limit 1 --json databaseId -q '.[0].databaseId')"
    exit 1
  fi

  # Re-pushing a tag at the same commit is a no-op and would not re-trigger
  # release.yml, so there has to be something new to move the tag onto.
  if [ "$REMOTE_TAG_COMMIT" = "$(git rev-parse HEAD)" ] && git diff --cached --quiet; then
    echo "ERROR: $TAG did not publish, and nothing new is committed or staged since."
    echo "       Fix the failure and commit, then re-run — the tag will move to it."
    exit 1
  fi

  MODE=resume
  echo "==> $TAG is on the remote but never published (run: ${RUN:-none});"
  echo "    new work exists — the tag will be moved forward to re-trigger the publish."
fi

# 3. Must be on main and not behind origin
[ "$(git branch --show-current)" = "main" ] || { echo "ERROR: not on main branch"; exit 1; }
git fetch --quiet origin main
git status --porcelain --branch | grep -q "behind" && { echo "ERROR: branch is behind origin/main"; exit 1; }

# 4. No unstaged content changes (mode-only diffs are OK)
git diff --stat | grep -v "| 0" | grep -q . && { echo "ERROR: unstaged content changes present"; exit 1; }

# 5. CHANGELOG.md has an entry for this version
grep -q "## v$VERSION" CHANGELOG.md || { echo "ERROR: CHANGELOG.md missing '## v$VERSION' entry"; exit 1; }

# 5b. The Rust crate versions in lockstep with the library — both publish from
#     this run, and the crate's number says which uni release it mirrors. Checked
#     here, before any test or tag, so a forgotten Cargo.toml edit fails fast.
CRATE_VERSION=$(grep -E '^version\s*=\s*"[^"]+"' rust/Cargo.toml | head -1 | grep -oE '"[^"]+"' | tr -d '"')
[ "$CRATE_VERSION" = "$VERSION" ] || {
  echo "ERROR: rust/Cargo.toml version ($CRATE_VERSION) != build.sbt version ($VERSION)"
  echo "       They publish in lockstep — update rust/Cargo.toml (and Cargo.lock via a build)."
  exit 1
}

# 6. Clean build and all tests must pass
echo "==> Running clean test..."
sbt clean test

# 6b. The Rust crate publishes from this same commit (step 12), so it gates the
#     release too — the same lint and test commands rust.yml runs, one
#     definition, in the Makefile.
echo "==> Running Rust lint and tests..."
(cd rust && make lint && make test)

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
if [ "$MODE" = "resume" ]; then
  # Moving the tag is what re-triggers release.yml. The lease pins the remote to
  # the ref inspected in check #2, so a tag pushed by someone else in the interim
  # aborts the push rather than being overwritten.
  git push --force-with-lease="refs/tags/$TAG:$REMOTE_TAG_REF" origin "$TAG"
else
  git push origin "$TAG"
fi

# 10. Extract this version's changelog section and create or refresh the GitHub release
RELEASE_NOTES=$(awk "/^## v$VERSION/{found=1; next} found && /^## v/{exit} found{print}" CHANGELOG.md)
if [ "$MODE" = "resume" ]; then
  echo "==> Updating GitHub release $TAG..."
  gh release edit "$TAG" --title "uni $VERSION" --notes "$RELEASE_NOTES"
else
  echo "==> Creating GitHub release $TAG..."
  gh release create "$TAG" --title "uni $VERSION" --notes "$RELEASE_NOTES"
fi

echo "==> Done: https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/releases/tag/$TAG"

# 11. Sonatype publish is triggered automatically by .github/workflows/release.yml
#     when the tag push (step 9) is received by GitHub — no manual sbt step needed.

# 12. Publish the Rust crate ($CRATE_VERSION == $VERSION, asserted in check #5b),
#     idempotently, in the same spirit as check #2: a version already on
#     crates.io is immutable, so it is skipped rather than failed — which is what
#     lets a re-run after a mid-script failure pick up where it died. Publishing
#     runs locally with the cargo login token, unlike Sonatype which goes
#     through CI.
CRATE=$(grep -E '^name\s*=\s*"[^"]+"' rust/Cargo.toml | head -1 | grep -oE '"[^"]+"' | tr -d '"')
# crates.io's API policy 403s requests without a descriptive User-Agent — and a
# 403 here would misread "already published" as "not yet", sending a re-run into
# a cargo publish that dies on the immutable-version error instead of skipping.
if curl -sf --max-time 20 -A "uni-release-script (github.com/philwalk/uni)" \
     "https://crates.io/api/v1/crates/$CRATE/$CRATE_VERSION" >/dev/null 2>&1; then
  echo "==> crates.io already has $CRATE $CRATE_VERSION — skipping cargo publish"
else
  echo "==> Publishing $CRATE $CRATE_VERSION to crates.io..."
  (cd rust && cargo publish)
  echo "==> Published: https://crates.io/crates/$CRATE/$CRATE_VERSION"
fi
