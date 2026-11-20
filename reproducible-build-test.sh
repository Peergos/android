#!/bin/bash
# Builds the release apk of a commit twice, from fresh clones at different paths with a
# different timezone, umask and locale, and fails unless the two unsigned apks are identical.
# Anyone building the same commit, such as F-Droid, has to get the apk we publish.
# The second build first deletes the prebuilt binaries F-Droid deletes, so the jars the build
# fetches or builds in their place must be byte-identical to the checked-in ones.
#
# Usage: ./reproducible-build-test.sh [commit]   (default: HEAD)
# Needs JDK 21 for Gradle (JAVA_HOME) and JDK 25 for the server (PEERGOS_JDK), the Android
# SDK (ANDROID_HOME), ant and python3.
set -e

REPO=$(cd "$(dirname "$0")" && pwd)
COMMIT=$(git -C "$REPO" rev-parse "${1:-HEAD}")
: "${PEERGOS_JDK:?set PEERGOS_JDK to a JDK 25}"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
WORK=$(mktemp -d)
APK=app/build/outputs/apk/release/app-release-unsigned.apk

# Must match the rm: list in fdroiddata's metadata/peergos.android.yml
FDROID_RM=(
    'deps/jpack/lib/*.jar'
    'deps/thumbnailer/lib/*.jar'
    'deps/vue3-template-compiler-java/lib-test/*.jar'
    'web-ui/JPack.jar'
    'web-ui/lib/*.jar'
    'web-ui/packager'
    'web-ui/peergos/ant.lib/*.jar'
    'web-ui/peergos/gwt/gwt-*/*.jar'
    'web-ui/peergos/lib/*.jar'
    'web-ui/peergos/lib-build/*.jar'
    'web-ui/peergos/native-lib/*/*/*'
)

build() {
    local dir=$1 strip=$2
    git clone -q --no-local "$REPO" "$dir"
    git -C "$dir" checkout -q "$COMMIT"
    git -C "$dir" submodule update -q --init --recursive
    if [ -n "$strip" ]; then
        local pattern matches
        for pattern in "${FDROID_RM[@]}"; do
            matches=$(cd "$dir" && compgen -G "$pattern") || {
                echo "F-Droid rm pattern $pattern matches nothing; update FDROID_RM and the recipe"
                return 1; }
            (cd "$dir" && echo "$matches" | xargs -d '\n' rm -rf --)
        done
    fi
    echo "sdk.dir=$ANDROID_HOME" > "$dir/local.properties"
    (cd "$dir" && ./gradlew --no-daemon --no-build-cache --console=plain assembleRelease \
        -Ppeergos.jdk="$PEERGOS_JDK" > "$dir.log" 2>&1) || {
        echo "build in $dir failed:"; tail -40 "$dir.log"; return 1; }
}

# one after the other, as each needs several GiB, and so they are built at different times
(export TZ=UTC LC_ALL=C; umask 022; build "$WORK/a")
(export TZ=Pacific/Chatham LC_ALL=C.UTF-8; umask 077; build "$WORK/second-build/at/a/longer/path" strip)

if cmp -s "$WORK/a/$APK" "$WORK/second-build/at/a/longer/path/$APK"; then
    echo "Reproducible: $(sha256sum "$WORK/a/$APK" | cut -d' ' -f1)"
    rm -rf "$WORK"
    exit 0
fi

echo "The two builds of $COMMIT differ:"
python3 -I - "$WORK/a/$APK" "$WORK/second-build/at/a/longer/path/$APK" <<'EOF'
import hashlib, sys, zipfile

def entries(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: (i.date_time, i.compress_type, i.external_attr, hashlib.sha256(z.read(i)).hexdigest())
                for i in z.infolist()}, [i.filename for i in z.infolist()]

(a, order_a), (b, order_b) = entries(sys.argv[1]), entries(sys.argv[2])
for name in sorted(set(a) | set(b)):
    if name not in b or name not in a:
        print("  only in one:", name)
    elif a[name] != b[name]:
        print("  " + name, [f for f, x, y in zip(["time", "method", "attributes", "content"], a[name], b[name]) if x != y])
if order_a != order_b and set(a) == set(b):
    print("  the entries are in a different order")
EOF
echo "Both builds are kept in $WORK"
exit 1
