The native library of sqlite-jdbc 3.46.1.0, which peergos uses, built from source by
`../CMakeLists.txt` the way sqlite-jdbc's Makefile builds it:

    sqlite3.c, sqlite3.h, sqlite3ext.h   https://www.sqlite.org/2024/sqlite-amalgamation-3460100.zip
                                         sha3-256 af6aae8d3eccc608857c63cf56efbadc70da48b5c719446b353ed88dded1e288
    NativeDB.c, extension-functions.c    https://github.com/xerial/sqlite-jdbc  tag 3.46.1.0  (73e64f0655e1f0bdefc4a6a0dc1710cc9e2782ce)
                                         src/main/java/org/sqlite/core/NativeDB.c, src/main/ext/extension-functions.c
    NativeDB.h                           javac -h of src/main/java/org/sqlite/core/NativeDB.java at that tag

All are unmodified; the edits sqlite-jdbc makes to sqlite3.c and sqlite3ext.h are applied by CMake.
SQLite is public domain; sqlite-jdbc is Apache 2.0, and extension-functions.c is public domain.
