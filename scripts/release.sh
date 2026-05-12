#!/usr/bin/env bash
#
# Cut a release: assembleDebug, deploy mkdocs to gh-pages, and create a
# GitHub Release with the APK attached. Tag, title, and notes are pulled
# from CHANGELOG.md so the source of truth stays in one place.
#
# Usage:
#   ./scripts/release.sh v1.2.3
#
# Requirements:
#   - JDK 17 + Android SDK (Gradle build)
#   - Python 3 + docs/requirements.txt installed in .venv-docs (mkdocs deploy)
#   - gh CLI logged in with `repo` scope (release create + push)
#
# Notes:
#   - Debug-signed APK. Suitable for sideloading; not for the Play Store.
#   - Assumes the current branch is the one you want to release from.
#   - Bails out hard on any failure (`set -euo pipefail`).

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <tag>   e.g. $0 v1.2.3" >&2
    exit 2
fi

TAG="$1"

# Tag prefix must be v<semver>. Trying to release without a v keeps tripping
# people up (gh release create still works, but tooling downstream usually
# expects v-prefixed tags).
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo "error: tag '$TAG' should look like v1.2.3" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "error: working tree is dirty; commit or stash first" >&2
    git status --short >&2
    exit 2
fi

# Extract the section for this tag from CHANGELOG.md. Looks for the
# "## [<version>]" header and reads up to the next "## [" header. If
# absent, fall back to a one-liner so the release still goes out.
VERSION="${TAG#v}"
NOTES="$(awk -v ver="$VERSION" '
    BEGIN { capture = 0 }
    /^## \[/ {
        if (capture) exit
        if ($0 ~ "\\[" ver "\\]") { capture = 1; next }
    }
    capture { print }
' CHANGELOG.md)"

if [[ -z "$NOTES" ]]; then
    echo "warning: no CHANGELOG.md section for $VERSION; using placeholder notes" >&2
    NOTES="Release $TAG"
fi

echo "==> assembleDebug"
./gradlew :app:assembleDebug --no-daemon --console=plain

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "error: expected APK at $APK; build must have failed silently" >&2
    exit 1
fi

echo "==> mkdocs gh-deploy"
if [[ ! -x ".venv-docs/bin/mkdocs" ]]; then
    echo "error: .venv-docs is missing; create it with: python3 -m venv .venv-docs && .venv-docs/bin/pip install -r docs/requirements.txt" >&2
    exit 1
fi
.venv-docs/bin/mkdocs gh-deploy --force --remote-branch gh-pages

echo "==> tagging $TAG"
git tag -a "$TAG" -m "LocalLLM $TAG"
git push origin "$TAG"

echo "==> creating GitHub release"
gh release create "$TAG" \
    --target "$(git branch --show-current)" \
    --title "LocalLLM $TAG" \
    --notes "$NOTES" \
    "$APK"

echo
echo "Done. Release: https://github.com/mlnomadpy/localllm/releases/tag/$TAG"
echo "Docs:    http://www.tahabouhsine.com/localllm/   (refresh in ~1 min)"
