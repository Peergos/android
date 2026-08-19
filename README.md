# Peergos for Android

## Peergos.jar

`app/libs/Peergos.jar` is not checked in. It is built from the `web-ui`
submodule, which in turn builds the `peergos` submodule nested inside it, and
then stripped of the entries Android can't use (see `prepare-jar.sh`).

A normal build produces it automatically, so a fresh clone only needs:

    ./gradlew assembleDebug

To drive it explicitly:

    ./gradlew peergosJar        # build it from the commit web-ui is pinned to
    ./gradlew updatePeergosJar  # fast-forward web-ui to upstream first, then rebuild
    ./gradlew initWebUi         # just check out the submodules
    ./gradlew updateWebUi       # just fast-forward web-ui to upstream

The jar records the web-ui commit it came from in its manifest, so the slow ant
build is skipped whenever the existing jar already matches the submodule's HEAD.
After `updatePeergosJar`, commit the new `web-ui` submodule pointer.

### Requirements

`ant`, and a JDK 25 or newer for the server build (it needs the final
`java.lang.foreign` API) - separate from the JDK 21 Gradle and AGP run on.
Gradle finds it automatically if it is in a standard location; otherwise point
at it with `-Ppeergos.jdk=/path/to/jdk` or `PEERGOS_JDK` in the environment.
