#!/bin/sh
#
# Creates this build's release in Sentry and associates the commits that went into it.
#
# Sentry creates a release on its own when the first event names it, but without commits. Associating them
# is what turns "this issue started in 1.2" into "this issue started in *this commit*".
#
# Usage: scripts/sentry-release.sh <android|ios>
#
# The release name must match what SentryCrashReporter sends — `app.tanuki.<platform>@<version>`, with the
# version from gradle/libs.versions.toml (`tanuki = "…"`) — or events and commits land on two different
# releases.
#
# Called automatically for release builds: by iosApp/scripts/upload-dsyms.sh (Release configuration) and by
# the `sentryRelease` task after `:androidApp:assembleRelease` (only when SENTRY_AUTH_TOKEN is set).
#
# Credentials, as in upload-dsyms.sh: SENTRY_AUTH_TOKEN, or ~/.sentryclirc from `sentry-cli login`.
#
# Commits: `--auto` needs the repository connected to Sentry (Settings → Integrations) and a matching git
# remote. Without that it falls back to `--local`, which reads the commits straight from this checkout.
#
# This script NEVER fails the build, for the same reason upload-dsyms.sh doesn't: contributors and forks
# build without any Sentry access, and release bookkeeping must not stand between them and a working build.

set -u

PLATFORM="${1:-}"
case "$PLATFORM" in
    android | ios) ;;
    *)
        echo "warning: [tanuki] usage: sentry-release.sh <android|ios> — skipping Sentry release."
        exit 0
        ;;
esac

# A build phase gets Xcode's PATH, which on Apple Silicon lacks Homebrew's prefix.
if [ "$(uname -m)" = arm64 ]; then
    export PATH="/opt/homebrew/bin:$PATH"
fi

SENTRY_ORG="${SENTRY_ORG:-tanuki-jc}"
SENTRY_PROJECT="${SENTRY_PROJECT:-tanuki}"
ROOT=$(cd "$(dirname "$0")/.." && pwd)

if [ -z "${SENTRY_AUTH_TOKEN:-}" ] && [ ! -f "$HOME/.sentryclirc" ]; then
    echo "warning: [tanuki] no Sentry credentials (SENTRY_AUTH_TOKEN unset, no ~/.sentryclirc) — skipping \
Sentry release."
    exit 0
fi

if ! command -v sentry-cli >/dev/null 2>&1; then
    echo "warning: [tanuki] sentry-cli not found — skipping Sentry release. Install it with \
'brew install getsentry/tools/sentry-cli'."
    exit 0
fi

VERSION=$(sed -n 's/^tanuki[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' "$ROOT/gradle/libs.versions.toml" | head -n 1)
if [ -z "$VERSION" ]; then
    echo "warning: [tanuki] no \`tanuki\` version in gradle/libs.versions.toml — skipping Sentry release."
    exit 0
fi

RELEASE="app.tanuki.$PLATFORM@$VERSION"
echo "note: [tanuki] creating Sentry release ${RELEASE}…"

cli() {
    sentry-cli releases --org "$SENTRY_ORG" --project "$SENTRY_PROJECT" "$@"
}

cli new "$RELEASE" || {
    echo "warning: [tanuki] could not create Sentry release $RELEASE."
    exit 0
}

# sentry-cli reads git from the working directory.
cd "$ROOT" || exit 0
cli set-commits "$RELEASE" --auto --ignore-missing 2>/dev/null \
    || cli set-commits "$RELEASE" --local --ignore-missing \
    || echo "warning: [tanuki] could not associate commits with $RELEASE."

cli finalize "$RELEASE" || echo "warning: [tanuki] could not finalize $RELEASE."
exit 0
