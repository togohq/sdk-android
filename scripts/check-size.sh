#!/bin/sh
# A ceiling on what this SDK adds to a customer's app.
#
# WHAT IS MEASURED: how much the example app's minified release APK grows when
# it adds the SDK. examples/app is built twice — the `withTogo` flavor, which
# depends on the library and calls it, and the `baseline` flavor, the same app
# without it — both minified by R8 with the platform's default optimizing rules
# and no rules from the SDK, as a customer's release build is. The SDK is
# charged the difference in the APKs' dex files (`classes*.dex`), uncompressed:
# its code and constants after R8 has removed what the app never calls. The app
# is Kotlin, so the Kotlin standard library is in both builds and the SDK is not
# charged for what any Kotlin app already ships. Uncompressed, because the
# download size depends on the store's compression and splitting. The SDK has
# no resources, so the dex files are all it adds.
#
# THE CEILING: 95 KB. It started at 200 KB, an estimate; the first real
# measurement, on 2026-11-23 with AGP 9.5.1 and Kotlin 2.4.21, was 77,608 bytes
# (75 KB), and the gate is set about 25% above it. The number includes the parts
# of the Kotlin standard library the SDK uses and this tiny app does not, which
# a real app mostly already ships. Raise it deliberately, in the same commit as
# the reason — never quietly. One dependency would trip it on its own.
#
# REFUSES rather than skips without `unzip` or an APK: this is the command that
# exists to run the gate, and a skip would read as a pass to anything checking
# the exit code. Inside the package, so the gate goes wherever the package goes.
#
# It also checks itself: the comparison it just passed must fail at a ceiling one
# byte below the measurement. A gate whose failing branch is broken passes
# everything.
#
# Usage: sh scripts/check-size.sh <withTogo.apk> <baseline.apk>
# `./gradlew check` builds both APKs and runs it (the `sizeCheck` task).
set -eu

CEILING_BYTES=$((95 * 1024))

fail() {
  echo "check-size: $1" >&2
  exit 1
}

[ $# -eq 2 ] || fail "usage: sh scripts/check-size.sh <withTogo.apk> <baseline.apk>"
command -v unzip >/dev/null 2>&1 || fail "unzip is not installed, and the gate cannot read an APK without it"
for apk in "$1" "$2"; do
  [ -f "$apk" ] || fail "no APK at $apk — build it with ./gradlew :examples:app:assembleWithTogoRelease :examples:app:assembleBaselineRelease"
done

# The uncompressed bytes of every classes*.dex at the APK's root.
dex_bytes() {
  unzip -l "$1" | awk '$4 ~ /^classes[0-9]*\.dex$/ { total += $1; found = 1 } END { if (!found) exit 1; print total }' ||
    fail "$1 holds no classes.dex"
}

# within <bytes> <ceiling>: exit 0 at or under the ceiling, 1 over it.
within() {
  [ "$1" -le "$2" ]
}

with_togo=$(dex_bytes "$1")
baseline=$(dex_bytes "$2")
added=$((with_togo - baseline))
[ "$added" -gt 0 ] || fail "the SDK adds $added bytes, so the two builds are not what this gate thinks they are"

if ! within "$added" "$CEILING_BYTES"; then
  fail "the SDK adds $added bytes ($((added / 1024)) KB) of dex to a minified app, over the $((CEILING_BYTES / 1024)) KB ceiling. Raise it deliberately, with the reason, or make the SDK smaller."
fi

# The failing branch, checked on the number just measured.
if within "$added" $((added - 1)); then
  fail "the size comparison passed $added bytes against a ceiling of $((added - 1)): the gate cannot fail"
fi

echo "check-size: the SDK adds $added bytes ($((added / 1024)) KB) of dex to a minified app; ceiling $((CEILING_BYTES / 1024)) KB"
