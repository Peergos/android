import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "peergos.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "peergos.android"
        minSdk = 30
        targetSdk = 37
        versionCode = 88
        versionName = "1.42.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        release {
            // R8 renames and drops what it can, which is also what Play measures as
            // "app optimisation". Most of Peergos.jar is a fat server build whose
            // libraries nothing on Android reaches, so this is mainly a large deletion.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    sourceSets {
        getByName("main").java.srcDir("src/openjdk/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/NOTICE*",
                "META-INF/LICENSE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/MANIFEST.MF"
            )
        }
    }
}

// R8 doesn't always rewrite the libraries' baseline profiles the same way, so the profile in
// the apk varied between builds of the same source, which made the apk unreproducible
tasks.configureEach {
    if (name.contains("ArtProfile"))
        enabled = false
}

dependencies {
    val work_version = "2.10.1"
    val lifecycle_version = "2.9.0"

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(files("libs/Peergos.jar"))
    implementation(libs.exifinterface)
    implementation("androidx.lifecycle:lifecycle-process:$lifecycle_version")
    implementation("androidx.work:work-runtime:$work_version")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.yubico.yubikit:android:3.0.1")
    implementation("com.yubico.yubikit:fido:3.0.1")
    implementation("androidx.security:security-crypto:1.1.0") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    // GrantPermissionRule: the calendar provider refuses a process without the runtime grant
    androidTestImplementation(libs.test.rules)
}
// ---------------------------------------------------------------------------
// libs/Peergos.jar is built from the web-ui submodule rather than checked in.
//
//   ./gradlew peergosJar        build it from the pinned web-ui commit
//   ./gradlew updatePeergosJar  fast-forward web-ui to upstream, then rebuild
//
// A normal build depends on peergosJar, so a fresh clone just works. The (slow)
// ant build only reruns when the jar wasn't built from the submodule's current
// HEAD, which the jar records in its manifest as Implementation-Version.
// ---------------------------------------------------------------------------

// The peergos server needs the final java.lang.foreign API, so ant has to run
// on a newer JDK than the one Gradle and AGP support.
val antJdkVersion = 25

val webUiDir = rootProject.file("web-ui")
val webUiJar = File(webUiDir, "server/Peergos.jar")
val webUiJarStamp = File(webUiDir, "server/.built-from")
val preparedJar = file("libs/Peergos.jar")
val prepareScript = rootProject.file("prepare-jar.sh")

fun capture(dir: File, vararg args: String): String {
    val process = ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0)
        throw GradleException("${args.joinToString(" ")} failed in $dir:\n$output")
    return output
}

fun run(dir: File, env: Map<String, String>, vararg args: String) {
    val builder = ProcessBuilder(*args).directory(dir).inheritIO()
    builder.environment().putAll(env)
    val exit = builder.start().waitFor()
    if (exit != 0)
        throw GradleException("${args.joinToString(" ")} failed in $dir with exit code $exit")
}

fun webUiHead(): String = capture(webUiDir, "git", "rev-parse", "HEAD")

// The jar is stale unless it was built from the commit web-ui is parked on. web-ui's
// build.xml stamps that commit into the manifest, but only when .git is a directory,
// which it isn't in a submodule checkout, so record it here instead. Local edits to
// web-ui don't move HEAD - rebuild by deleting the jar.
fun jarIsCurrent(): Boolean =
    webUiJar.isFile && webUiJarStamp.isFile && webUiJarStamp.readText().trim() == webUiHead()

fun antJdkHome(): String {
    val explicit = (findProperty("peergos.jdk") as String?) ?: System.getenv("PEERGOS_JDK")
    if (explicit != null)
        return explicit
    val toolchains = extensions.findByType(JavaToolchainService::class.java)
    val detected = runCatching {
        toolchains?.launcherFor { languageVersion.set(JavaLanguageVersion.of(antJdkVersion)) }
            ?.get()?.metadata?.installationPath?.asFile?.absolutePath
    }.getOrNull()
    return detected ?: throw GradleException(
        "No JDK $antJdkVersion found to build web-ui with. Install one, or point at it with " +
            "-Ppeergos.jdk=/path/to/jdk (or PEERGOS_JDK in the environment)."
    )
}

// `git submodule status` prefixes a submodule that has never been checked out with a
// dash. Anything already checked out is left alone: a plain `git submodule update`
// would reset it to the commit recorded in the index, throwing away an update that
// hasn't been committed yet.
fun uncheckedOutSubmodules(): List<String> =
    capture(rootDir, "git", "submodule", "status", "--recursive", "web-ui", "deps")
        .lines().filter { it.startsWith("-") }

val initWebUi = tasks.register("initWebUi") {
    group = "peergos"
    description = "Check out the web-ui submodule, the peergos submodule nested inside it, and deps"
    doLast {
        if (uncheckedOutSubmodules().isNotEmpty())
            run(rootDir, emptyMap(), "git", "submodule", "update", "--init", "--recursive", "web-ui", "deps")
    }
}

val updateWebUi = tasks.register("updateWebUi") {
    group = "peergos"
    description = "Fast-forward the web-ui submodule to the latest upstream commit"
    dependsOn(initWebUi)
    doLast {
        run(rootDir, emptyMap(), "git", "submodule", "update", "--init", "--remote", "--merge", "web-ui")
        // and move the nested peergos submodule to whatever the new web-ui commit pins
        run(webUiDir, emptyMap(), "git", "submodule", "update", "--init", "--recursive")
        println("web-ui is now at ${webUiHead()}")
    }
}

val buildWebUiJar = tasks.register("buildWebUiJar") {
    group = "peergos"
    description = "Run `ant dist` in web-ui to build the fat Peergos.jar"
    dependsOn(initWebUi, "restoreSubmoduleJars")
    mustRunAfter(updateWebUi)
    // a task with no declared outputs is never up to date, so this needs outputs.file too
    outputs.files(webUiJar, webUiJarStamp)
    outputs.upToDateWhen { jarIsCurrent() }
    doLast {
        val jdk = antJdkHome()
        run(webUiDir, mapOf("JAVA_HOME" to jdk, "PATH" to "$jdk/bin:${System.getenv("PATH")}"),
            "ant", "dist")
        // CompileSubmodule.java ignores the exit code of the inner `ant dist`, so a failed
        // server build silently yields a jar of dependencies and a manifest only.
        val hasServer = ZipFile(webUiJar).use {
            it.getEntry("peergos/server/Main.class") != null
        }
        if (!hasServer)
            throw GradleException("$webUiJar has no peergos.server.Main - the peergos submodule build failed")
        webUiJarStamp.writeText(webUiHead() + "\n")
    }
}

val peergosJar = tasks.register("peergosJar") {
    group = "peergos"
    description = "Build app/libs/Peergos.jar from the web-ui submodule"
    dependsOn(buildWebUiJar)
    inputs.file(prepareScript)
    inputs.files(webUiJar)
    outputs.file(preparedJar)
    doLast {
        preparedJar.parentFile.mkdirs()
        webUiJar.copyTo(preparedJar, overwrite = true)
        run(rootDir, emptyMap(), "sh", prepareScript.absolutePath, preparedJar.absolutePath)
    }
}

tasks.register("updatePeergosJar") {
    group = "peergos"
    description = "Update web-ui to the latest upstream commit and rebuild app/libs/Peergos.jar"
    dependsOn(updateWebUi, peergosJar)
}

tasks.named("preBuild") {
    dependsOn(peergosJar)
}

// F-Droid deletes the jars checked in to web-ui and peergos before it builds. These are the same
// bytes as Maven Central's, so they are put back from there, where F-Droid accepts them from.
val mavenSubmoduleJars = mapOf(
    "peergos/lib/asm-9.2.jar" to "org.ow2.asm:asm:9.2",
    "peergos/lib/asm-analysis-9.2.jar" to "org.ow2.asm:asm-analysis:9.2",
    "peergos/lib/asm-commons-9.2.jar" to "org.ow2.asm:asm-commons:9.2",
    "peergos/lib/asm-tree-9.2.jar" to "org.ow2.asm:asm-tree:9.2",
    "peergos/lib/asm-util-9.2.jar" to "org.ow2.asm:asm-util:9.2",
    "peergos/lib/HikariCP-7.0.2.jar" to "com.zaxxer:HikariCP:7.0.2",
    "peergos/lib/jackson-annotations-2.22.jar" to "com.fasterxml.jackson.core:jackson-annotations:2.22",
    "peergos/lib/jackson-core-2.22.3.jar" to "com.fasterxml.jackson.core:jackson-core:2.22.3",
    "peergos/lib/jackson-databind-2.22.3.jar" to "com.fasterxml.jackson.core:jackson-databind:2.22.3",
    "peergos/lib/jackson-dataformat-cbor-2.22.3.jar" to "com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.22.3",
    "peergos/lib/jakarta.servlet-api-6.0.0.jar" to "jakarta.servlet:jakarta.servlet-api:6.0.0",
    "peergos/lib/java-otp-0.4.0.jar" to "com.eatthepath:java-otp:0.4.0",
    "peergos/lib/jetty-ee10-servlet-12.1.14.jar" to "org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.14",
    "peergos/lib/jetty-http-12.1.14.jar" to "org.eclipse.jetty:jetty-http:12.1.14",
    "peergos/lib/jetty-io-12.1.14.jar" to "org.eclipse.jetty:jetty-io:12.1.14",
    "peergos/lib/jetty-security-12.1.14.jar" to "org.eclipse.jetty:jetty-security:12.1.14",
    "peergos/lib/jetty-server-12.1.14.jar" to "org.eclipse.jetty:jetty-server:12.1.14",
    "peergos/lib/jetty-session-12.1.14.jar" to "org.eclipse.jetty:jetty-session:12.1.14",
    "peergos/lib/jetty-util-12.1.14.jar" to "org.eclipse.jetty:jetty-util:12.1.14",
    "peergos/lib/jffi-1.3.13.jar" to "com.github.jnr:jffi:1.3.13",
    "peergos/lib/jffi-1.3.13-native.jar" to "com.github.jnr:jffi:1.3.13:native",
    "peergos/lib/jline-3.30.17.jar" to "org.jline:jline:3.30.17",
    "peergos/lib/jnr-a64asm-1.0.0.jar" to "com.github.jnr:jnr-a64asm:1.0.0",
    "peergos/lib/jnr-constants-0.10.4.jar" to "com.github.jnr:jnr-constants:0.10.4",
    "peergos/lib/jnr-ffi-2.2.16.jar" to "com.github.jnr:jnr-ffi:2.2.16",
    "peergos/lib/jnr-fuse-0.5.8.jar" to "com.github.serceman:jnr-fuse:0.5.8",
    "peergos/lib/jnr-posix-3.1.19.jar" to "com.github.jnr:jnr-posix:3.1.19",
    "peergos/lib/jnr-x86asm-1.0.2.jar" to "com.github.jnr:jnr-x86asm:1.0.2",
    "peergos/lib/kerby-asn1-2.0.3.jar" to "org.apache.kerby:kerby-asn1:2.0.3",
    "peergos/lib/postgresql-42.7.14.jar" to "org.postgresql:postgresql:42.7.14",
    "peergos/lib/simpleclient-0.15.0.jar" to "io.prometheus:simpleclient:0.15.0",
    "peergos/lib/simpleclient_common-0.15.0.jar" to "io.prometheus:simpleclient_common:0.15.0",
    "peergos/lib/simpleclient_httpserver-0.15.0.jar" to "io.prometheus:simpleclient_httpserver:0.15.0",
    "peergos/lib/slf4j-api-2.0.17.jar" to "org.slf4j:slf4j-api:2.0.17",
    "peergos/lib/slf4j-nop-2.0.17.jar" to "org.slf4j:slf4j-nop:2.0.17",
    "peergos/lib/webauthn4j-core-0.29.6.RELEASE.jar" to "com.webauthn4j:webauthn4j-core:0.29.6.RELEASE",
    "peergos/lib-build/hamcrest-core-1.3.jar" to "org.hamcrest:hamcrest-core:1.3",
    "peergos/lib-build/junit-4.11.jar" to "junit:junit:4.11",
    // Not the same bytes: peergos' copies only add or drop native libraries, which Android
    // gets from the app's own native build, or are a later snapshot of the ant tasks the build uses
    "peergos/lib/sqlite-jdbc-3.46.1.0.jar" to "org.xerial:sqlite-jdbc:3.46.1.0",
    "peergos/ant.lib/ant-contrib.jar" to "ant-contrib:ant-contrib:1.0b3",
    // the nabu commit peergos' jar was built from, rebuilt by JitPack
    "peergos/lib/nabu-v0.9.1-jar-with-dependencies.jar" to "com.github.peergos:nabu:f13dd3d3e0:jar-with-dependencies",
)

tasks.register("restoreSubmoduleJars") {
    group = "peergos"
    description = "Put back from Maven Central any web-ui jars that have been deleted"
    dependsOn(initWebUi)
    doLast {
        val missing = mavenSubmoduleJars.filterKeys { !File(webUiDir, it).isFile }
        for ((path, coordinate) in missing) {
            val parts = coordinate.split(":")
            val notation = mapOf("group" to parts[0], "name" to parts[1], "version" to parts[2]) +
                (if (parts.size > 3) mapOf("classifier" to parts[3]) else emptyMap())
            val dependency = project.dependencies.create(notation) as ExternalModuleDependency
            dependency.isTransitive = false
            val jar = configurations.detachedConfiguration(dependency).singleFile
            jar.copyTo(File(webUiDir, path))
        }

        // The rest aren't on Maven Central, so they are built from their source in deps/, in a
        // copy so the submodules stay clean
        val depsBuild = layout.buildDirectory.dir("deps").get().asFile
        val jdk = antJdkHome()
        val antEnv = mapOf("JAVA_HOME" to jdk, "PATH" to "$jdk/bin:${System.getenv("PATH")}")
        fun antBuild(name: String, target: String): File {
            val dir = File(depsBuild, name)
            dir.deleteRecursively()
            rootProject.file("deps/$name").copyRecursively(dir)
            File(dir, ".git").delete()
            run(dir, antEnv, "ant", "-q", target)
            return dir
        }

        val jpack = File(webUiDir, "JPack.jar")
        if (!jpack.isFile) {
            val compiler = File(antBuild("vue3-template-compiler-java", "dist"), "dist/vue3-template-compiler.jar")
            val jpackSrc = File(depsBuild, "jpack")
            jpackSrc.deleteRecursively()
            rootProject.file("deps/jpack").copyRecursively(jpackSrc)
            File(jpackSrc, "lib").listFiles()!!.forEach { it.delete() }
            compiler.copyTo(File(jpackSrc, "lib/vue3-template-compiler.jar"))
            run(jpackSrc, antEnv, "ant", "-q", "dist")
            File(jpackSrc, "dist/JPack.jar").copyTo(jpack)
        }

        // peergos only compiles against it; Android never loads the desktop thumbnailer
        val thumbnailer = File(webUiDir, "peergos/lib/thumbnailer.jar")
        if (!thumbnailer.isFile)
            File(antBuild("thumbnailer", "jar"), "thumbnailer.jar").copyTo(thumbnailer)

        val watchTask = File(webUiDir, "lib/watch-task-1.0.jar")
        if (!watchTask.isFile) {
            val antJar = configurations.detachedConfiguration(
                project.dependencies.create("org.apache.ant:ant:1.10.15") as ExternalModuleDependency
            ).apply { isTransitive = false }.singleFile
            val classes = File(depsBuild, "watch-task/classes").apply { deleteRecursively(); mkdirs() }
            val compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-cp", antJar.absolutePath, "--release", "11", "-d", classes.absolutePath,
                rootProject.file("deps/watch-task/WatchTask.java").absolutePath)
            if (compiled != 0)
                throw GradleException("Couldn't compile the watch task stub")
            ant.withGroovyBuilder { "jar"("destfile" to watchTask, "basedir" to classes) }
        }
    }
}
