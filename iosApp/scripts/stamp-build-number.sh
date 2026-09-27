#!/bin/sh
#
# Stamps the build number (CFBundleVersion — Sentry's `dist`) into the built app's Info.plist.
#
# The build number is what separates two builds of the same version: without it, a crash from a fixed
# build and one from the broken build before it look identical. The rule matches Android's `versionCode`
# (androidApp/build.gradle.kts), so both platforms count the same way:
#
#   TANUKI_BUILD_NUMBER   if set — CI passes its own.
#   otherwise             the commit count, `git rev-list --count HEAD`, which only ever grows on main.
#   otherwise             untouched: CURRENT_PROJECT_VERSION from Config.xcconfig (a source tarball).
#
# An xcconfig can't run git, which is why this is a script. It edits the *processed* Info.plist in the
# build folder, never iosApp/Info.plist, so it leaves nothing to commit.
#
# Wired up as the "Stamp Build Number" Run Script phase, after Resources (the processed Info.plist exists by
# then) and before signing. Like the other scripts here it never fails the build.

set -u

PLIST="${TARGET_BUILD_DIR:-}/${INFOPLIST_PATH:-}"
if [ ! -f "$PLIST" ]; then
    echo "warning: [tanuki] no processed Info.plist at '$PLIST' — build number left as is."
    exit 0
fi

BUILD_NUMBER="${TANUKI_BUILD_NUMBER:-}"
if [ -z "$BUILD_NUMBER" ]; then
    BUILD_NUMBER=$(git -C "${SRCROOT:-.}" rev-list --count HEAD 2>/dev/null || true)
fi

case "$BUILD_NUMBER" in
    '' | *[!0-9]*)
        echo "note: [tanuki] no build number (no TANUKI_BUILD_NUMBER, no git) — keeping CURRENT_PROJECT_VERSION."
        exit 0
        ;;
esac

/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$PLIST" \
    || echo "warning: [tanuki] could not stamp the build number into $PLIST."
echo "note: [tanuki] CFBundleVersion = $BUILD_NUMBER"
exit 0
