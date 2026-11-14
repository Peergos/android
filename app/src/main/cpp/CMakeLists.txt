
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("scrypt")

# The debug info names the paths the build ran in, and the build id is a hash of it, so
# map them to fixed names or no one else can build the same library
add_compile_options(
        -ffile-prefix-map=${CMAKE_SOURCE_DIR}=src
        -ffile-prefix-map=${CMAKE_BINARY_DIR}=build
        -ffile-prefix-map=${ANDROID_NDK}=ndk)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
        scrypt/crypto_scrypt_smix.c
        scrypt/warnp.c
        scrypt/insecure_memzero.c
        scrypt/sha256.c
        scrypt/sha256_arm.c
        scrypt/crypto_scrypt.c
)

include_directories(scrypt)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log)

add_library(tweetnacl SHARED
    tweetnacl/jni/peergos_server_crypto_JniTweetNacl.c
)

target_include_directories(tweetnacl PRIVATE
    tweetnacl/native
    tweetnacl/jni
)

target_link_libraries(tweetnacl log)

# sqlite-jdbc's native library, built as its Makefile does: sqlite3.c with its extension functions
# appended and registered on open, and the JDBC_EXTENSIONS compile option added.
set(SQLITE_JDBC ${CMAKE_CURRENT_SOURCE_DIR}/sqlite-jdbc)
set(SQLITE_JDBC_OUT ${CMAKE_CURRENT_BINARY_DIR}/sqlite-jdbc)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        ${SQLITE_JDBC}/sqlite3.c ${SQLITE_JDBC}/sqlite3ext.h ${SQLITE_JDBC}/extension-functions.c)
file(READ ${SQLITE_JDBC}/sqlite3.c SQLITE_C)
string(REPLACE "\nopendb_out:"
        "\n  if(!db->mallocFailed && rc==SQLITE_OK){ int RegisterExtensionFunctions(sqlite3 *); rc = RegisterExtensionFunctions(db); }\nopendb_out:"
        SQLITE_C "${SQLITE_C}")
string(REPLACE "static const char * const sqlite3azCompileOpt[] = {\n"
        "static const char * const sqlite3azCompileOpt[] = {\n\n/* This has been automatically added by sqlite-jdbc */\n  \"JDBC_EXTENSIONS\",\n"
        SQLITE_C "${SQLITE_C}")
file(READ ${SQLITE_JDBC}/extension-functions.c SQLITE_EXT_C)
file(WRITE ${SQLITE_JDBC_OUT}/sqlite3.c "${SQLITE_C}${SQLITE_EXT_C}")
file(READ ${SQLITE_JDBC}/sqlite3ext.h SQLITE_EXT_H)
string(REPLACE "sqlite3_api;" "sqlite3_api = 0;" SQLITE_EXT_H "${SQLITE_EXT_H}")
file(WRITE ${SQLITE_JDBC_OUT}/sqlite3ext.h "${SQLITE_EXT_H}")

add_library(sqlitejdbc SHARED
        ${SQLITE_JDBC_OUT}/sqlite3.c
        ${SQLITE_JDBC}/NativeDB.c
)

target_include_directories(sqlitejdbc PRIVATE ${SQLITE_JDBC_OUT} ${SQLITE_JDBC})

target_compile_options(sqlitejdbc PRIVATE -Os -fvisibility=hidden)

target_compile_definitions(sqlitejdbc PRIVATE
        SQLITE_ENABLE_LOAD_EXTENSION=1
        SQLITE_HAVE_ISNAN
        HAVE_USLEEP=1
        SQLITE_ENABLE_COLUMN_METADATA
        SQLITE_CORE
        SQLITE_ENABLE_FTS3
        SQLITE_ENABLE_FTS3_PARENTHESIS
        SQLITE_ENABLE_FTS5
        SQLITE_ENABLE_RTREE
        SQLITE_ENABLE_STAT4
        SQLITE_ENABLE_DBSTAT_VTAB
        SQLITE_ENABLE_MATH_FUNCTIONS
        SQLITE_THREADSAFE=1
        SQLITE_DEFAULT_MEMSTATUS=0
        SQLITE_DEFAULT_FILE_PERMISSIONS=0666
        SQLITE_MAX_VARIABLE_NUMBER=250000
        SQLITE_MAX_MMAP_SIZE=1099511627776
        SQLITE_MAX_LENGTH=2147483647
        SQLITE_MAX_COLUMN=32767
        SQLITE_MAX_SQL_LENGTH=1073741824
        SQLITE_MAX_FUNCTION_ARG=127
        SQLITE_MAX_ATTACHED=125
        SQLITE_MAX_PAGE_COUNT=4294967294
        SQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS)

target_link_libraries(sqlitejdbc android dl log m)
