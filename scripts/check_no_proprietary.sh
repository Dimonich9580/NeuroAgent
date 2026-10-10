#!/usr/bin/env bash
# F-Droid audit helper: fails if proprietary / tracking libraries appear in the build or sources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BANNED='com\.google\.(android\.gms|firebase|android\.play)|firebase|crashlytics|play-services|com\.android\.billingclient|appsflyer|amplitude|mixpanel|sentry|analytics'
fail=0

echo "== Scanning Gradle build files"
if grep -RIEn "$BANNED" "$ROOT"/*.kts "$ROOT"/app/*.kts "$ROOT"/gradle/libs.versions.toml; then
  echo "FAIL: proprietary/tracking dependency reference in build files"; fail=1
fi

echo "== Scanning sources and manifest"
if grep -RIEn --include='*.kt' --include='*.java' --include='*.xml' --include='*.cpp' --include='*.h' \
    -e "$BANNED" "$ROOT/app/src" ; then
  echo "FAIL: banned SDK usage in sources"; fail=1
fi

echo "== Scanning committed binaries (.so/.jar/.aar must not be vendored)"
# gradle-wrapper.jar is the official Gradle wrapper, not an app dependency.
if find "$ROOT" \
    \( -path "$ROOT/third_party" -o -path "$ROOT/.git" -o -path "$ROOT/gradle/wrapper" -o -path "*/build" -o -path "*/.gradle" \) -prune -o \
    \( -name '*.so' -o -name '*.aar' -o -name '*.jar' \) -print | grep -q .; then
  find "$ROOT" \
    \( -path "$ROOT/third_party" -o -path "$ROOT/.git" -o -path "$ROOT/gradle/wrapper" -o -path "*/build" -o -path "*/.gradle" \) -prune -o \
    \( -name '*.so' -o -name '*.aar' -o -name '*.jar' \) -print
  echo "FAIL: prebuilt binaries found in repository"; fail=1
fi

echo "== Runtime dependency check (Gradle, if available)"
if [ -x "$ROOT/gradlew" ] && [ -n "${SKIP_GRADLE_AUDIT:-}" ] ; then
  echo "skipped (SKIP_GRADLE_AUDIT set)"
elif [ -x "$ROOT/gradlew" ]; then
  "$ROOT/gradlew" -q :app:dependencies --configuration releaseRuntimeClasspath > /tmp/neuro_deps.txt
  if grep -Eiq "$BANNED" /tmp/neuro_deps.txt; then
    grep -Ei "$BANNED" /tmp/neuro_deps.txt; echo "FAIL: banned runtime dependency"; fail=1
  else
    echo "runtime classpath clean"
  fi
fi

[ $fail -eq 0 ] && echo "AUDIT PASSED" || { echo "AUDIT FAILED"; exit 1; }
