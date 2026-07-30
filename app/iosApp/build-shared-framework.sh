#!/bin/sh
set -e
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for candidate in \
    "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$(/usr/libexec/java_home 2>/dev/null)"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "error: no Java runtime found. Set JAVA_HOME or install a JDK." >&2
  exit 1
fi
if [ -n "$SRCROOT" ]; then
  REPO_ROOT="$(cd "$SRCROOT/../.." && pwd)"
else
  REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fi
cd "$REPO_ROOT"
echo "Building shared XCFramework with JAVA_HOME=$JAVA_HOME"
exec ./gradlew --console=plain :app:shared:assembleSharedReleaseXCFramework