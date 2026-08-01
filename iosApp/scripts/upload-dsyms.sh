#!/bin/sh
#
# Uploads iOS debug symbols (dSYMs) to Sentry so crash reports arrive symbolicated.
#
# Without this, an iOS crash is a list of hexadecimal addresses. Kotlin/Native frames are the ones that
# suffer most — they are exactly the frames worth reading, and exactly the reason this project chose
# Sentry over Crashlytics. The upload has to happen at build time because the dSYM is produced by the
# build and is not recoverable afterwards.
#
# Wire it up as an Xcode Run Script build phase on the iosApp target, after "Compile Kotlin Framework":
#
#     "$SRCROOT/scripts/upload-dsyms.sh"
#
# Uncheck "Based on dependency analysis" so it runs every build, and leave Input/Output Files empty.
#
# Configuration:
#   SENTRY_AUTH_TOKEN   required to upload. A secret — never commit it. Provide it as a CI secret, or
#                       locally via ~/.zshrc or an Xcode scheme environment variable.
#   SENTRY_ORG          defaults below; not secret.
#   SENTRY_PROJECT      defaults below; not secret.
#
# This script deliberately NEVER fails the build. A missing token, a missing sentry-cli, or a network
# problem prints a warning and exits 0. Rationale: contributors and forks build this project without any
# Sentry access at all, and a symbol upload is a diagnostics nicety — it must not be the thing standing
# between someone and a working build of a FOSS reader.

set -eu

SENTRY_ORG="${SENTRY_ORG:-tanuki-jc}"
SENTRY_PROJECT="${SENTRY_PROJECT:-tanuki}"

# Debug builds are run from Xcode against a local symbol file that is already on the machine; uploading
# them would burn quota to symbolicate crashes nobody is triaging.
if [ "${CONFIGURATION:-Debug}" = "Debug" ]; then
    echo "note: [tanuki] Debug configuration — skipping Sentry dSYM upload."
    exit 0
fi

if [ -z "${SENTRY_AUTH_TOKEN:-}" ]; then
    echo "warning: [tanuki] SENTRY_AUTH_TOKEN not set — skipping dSYM upload. iOS crashes from this \
build will NOT be symbolicated."
    exit 0
fi

if ! command -v sentry-cli >/dev/null 2>&1; then
    echo "warning: [tanuki] sentry-cli not found — skipping dSYM upload. Install it with \
'brew install getsentry/tools/sentry-cli'."
    exit 0
fi

echo "note: [tanuki] uploading dSYMs to $SENTRY_ORG/$SENTRY_PROJECT…"

# DWARF_DSYM_FOLDER_PATH is where Xcode has just written this build's dSYMs. Uploading that rather than
# a search of DerivedData keeps it to the symbols for the binary actually being built.
#
# --include-sources is deliberately NOT passed: it would upload source context to Sentry, and this
# project's stance is that nothing leaves the device (or the build machine) that does not have to.
sentry-cli debug-files upload \
    --org "$SENTRY_ORG" \
    --project "$SENTRY_PROJECT" \
    "${DWARF_DSYM_FOLDER_PATH:-.}" \
    || echo "warning: [tanuki] dSYM upload failed — build continues, crashes will be unsymbolicated."

exit 0