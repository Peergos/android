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
        versionCode = 62
        versionName = "1.31.0"

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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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

dependencies {
    val work_version = "2.10.1"
    val lifecycle_version = "2.9.0"

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(files("libs/Peergos.jar"))
    implementation(files("libs/http-2.2.1.jar"))
    implementation(files("libs/sun-common-server.jar"))
    implementation(libs.exifinterface)
    implementation("androidx.lifecycle:lifecycle-process:$lifecycle_version")
    implementation("androidx.work:work-runtime:$work_version")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.yubico.yubikit:android:3.0.1")
    implementation("com.yubico.yubikit:fido:3.0.1")
    implementation("androidx.security:security-crypto:1.1.0") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
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
    capture(rootDir, "git", "submodule", "status", "--recursive", "web-ui")
        .lines().filter { it.startsWith("-") }

val initWebUi = tasks.register("initWebUi") {
    group = "peergos"
    description = "Check out the web-ui submodule and the peergos submodule nested inside it"
    doLast {
        if (uncheckedOutSubmodules().isNotEmpty())
            run(rootDir, emptyMap(), "git", "submodule", "update", "--init", "--recursive", "web-ui")
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
    dependsOn(initWebUi)
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
