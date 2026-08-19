#!/bin/sh
# Strip the entries of the web-ui fat jar that Android can't use: classes that
# duplicate what the platform or AGP already provides, desktop native libraries,
# and the server test suite.
#
# Usage: ./prepare-jar.sh [path-to-jar]   (default: app/libs/Peergos.jar)
set -e

JAR="${1:-app/libs/Peergos.jar}"
if [ ! -f "$JAR" ]; then
    echo "prepare-jar.sh: no such jar: $JAR" >&2
    exit 1
fi

# zip -d exits 12 ("nothing to do") when none of the patterns match, which just
# means a dependency that used to ship these entries has gone away.
zip -d "$JAR" \
    'com/google/errorprone/*' \
    'com/google/common/util/*' \
    'peergos/server/tests/*' \
    'kotlin/*' \
    'kotlinx/*' \
    'org/intellij/*' \
    'org/jetbrains/*' \
    'DebugProbesKt.bin' \
    'META-INF/kotlinx*' \
    'META-INF/versions/9*' \
    'org/slf4j/*' \
    'org/sqlite/native/Mac/*' \
    'org/sqlite/native/Windows/*' \
    'org/sqlite/native/OpenBSD/*' \
    'org/sqlite/native/Linux/*' \
    'org/sqlite/native/FreeBSD/*' \
    'org/sqlite/native/Linux-Musl/*' \
    'org/jline/nativ/*' \
    'jni/*' \
    'META-INF/native/*' \
    'native/*' \
    || [ $? -eq 12 ]
