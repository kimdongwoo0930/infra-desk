plugins {
    application
}

group = "com.infradesk"
// Release builds pass -PappVersion from the git tag (v1.2.3 → 1.2.3).
version = providers.gradleProperty("appVersion").getOrElse("1.0.0")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // JediTerm is published only to JetBrains' repository.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
        content { includeGroup("org.jetbrains.jediterm") }
    }
}

dependencies {
    implementation(libs.flatlaf)
    implementation(libs.flatlaf.extras)
    implementation(libs.oci.core)
    implementation(libs.oci.monitoring)
    implementation(libs.oci.httpclient)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.java.keyring)
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    runtimeOnly(libs.eddsa) // lets MINA SSHD read ed25519 keys
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.xchart)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "com.infradesk.app.InfraDeskApp"
    applicationName = "InfraDesk"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-this-escape"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Dev tool: renders screens with demo data to build/snapshots/*.png without showing them.
tasks.register<JavaExec>("snapshot") {
    group = "application"
    description = "Renders screens with demo data off-screen to build/snapshots/*.png"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.infradesk.ui.UiSnapshot"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dinfradesk.noTray=true")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Runs the app with fake data: no settings file, keychain, or network access.
tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Runs InfraDesk in demo mode with fake servers"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.infradesk.app.InfraDeskApp"
    args("--demo")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Dev tool: draws the app icon into src/packaging and runtime resources, then builds the .icns.
tasks.register<JavaExec>("generateIcons") {
    group = "distribution"
    description = "Draws the app icon and writes .iconset/.icns/.ico and runtime PNGs"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.infradesk.tools.IconGenerator"
    args(projectDir.absolutePath)
    systemProperty("java.awt.headless", "true")
    doLast {
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            providers.exec {
                commandLine("iconutil", "-c", "icns", "src/packaging/macos/InfraDesk.iconset",
                    "-o", "src/packaging/macos/InfraDesk.icns")
            }.result.get()
        }
    }
}

// ---- Packaging (jpackage) ----
// Runtime modules: jdeps over the app and all libraries, plus modules jdeps can't see
// (locale data for Korean formats, EC crypto for SSH/TLS, charsets, accessibility).
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.instrument", "java.logging", "java.management", "java.naming",
    "java.net.http", "java.prefs", "java.rmi", "java.security.jgss", "java.sql", "java.xml",
    "jdk.accessibility", "jdk.attach", "jdk.charsets", "jdk.crypto.ec", "jdk.jdi", "jdk.localedata",
    "jdk.net", "jdk.security.auth", "jdk.unsupported", "jdk.unsupported.desktop", "jdk.zipfs",
)
val isMac = System.getProperty("os.name").lowercase().contains("mac")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val appImageDir = layout.buildDirectory.dir("jpackage")
val distDir = layout.buildDirectory.dir("dist")

// App bundle with a trimmed Java runtime: build/jpackage/InfraDesk.app (macOS) or InfraDesk/ (Windows).
tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Builds the InfraDesk app image with a bundled Java runtime"
    dependsOn("installDist")
    val libDir = layout.buildDirectory.dir("install/InfraDesk/lib").get().asFile
    val out = appImageDir.get().asFile
    doFirst { delete(out) }
    val args = mutableListOf(
        "jpackage", "--type", "app-image",
        "--name", "InfraDesk", "--app-version", project.version.toString(),
        "--vendor", "InfraDesk", "--description", "여러 클라우드 계정의 서버를 한 곳에서 관리",
        "--input", libDir.absolutePath, "--main-jar", "infra-desk-${project.version}.jar",
        "--main-class", "com.infradesk.app.InfraDeskApp",
        "--add-modules", runtimeModules.joinToString(","),
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress zip-6",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--dest", out.absolutePath,
    )
    if (isMac) {
        args += listOf("--icon", "src/packaging/macos/InfraDesk.icns",
            "--mac-package-identifier", "com.infradesk.app", "--mac-package-name", "InfraDesk",
            "--mac-app-category", "public.app-category.developer-tools")
    } else if (isWindows) {
        args += listOf("--icon", "src/packaging/windows/InfraDesk.ico")
    }
    commandLine(args)
}

// Runs the packaged launcher with --self-test: every library loads inside the trimmed runtime.
tasks.register<Exec>("selfTestAppImage") {
    group = "distribution"
    description = "Runs the packaged app's --self-test (no windows, no settings, no network)"
    dependsOn("appImage")
    val out = appImageDir.get().asFile
    val launcher = when {
        isMac -> "${out}/InfraDesk.app/Contents/MacOS/InfraDesk"
        isWindows -> "${out}\\InfraDesk\\InfraDesk.exe"
        else -> "${out}/InfraDesk/bin/InfraDesk"
    }
    commandLine(launcher, "--self-test")
    environment("JAVA_TOOL_OPTIONS", "-Djava.awt.headless=true")
}

// macOS disk image: build/dist/InfraDesk-<version>.dmg
tasks.register<Exec>("dmg") {
    group = "distribution"
    description = "Builds build/dist/InfraDesk-<version>.dmg (macOS)"
    dependsOn("selfTestAppImage")
    onlyIf { isMac }
    val out = distDir.get().asFile
    doFirst { delete(out); out.mkdirs() }
    commandLine(
        "jpackage", "--type", "dmg",
        "--app-image", "${appImageDir.get().asFile}/InfraDesk.app",
        "--name", "InfraDesk", "--app-version", project.version.toString(),
        "--mac-package-identifier", "com.infradesk.app",
        "--dest", out.absolutePath,
    )
}

// Windows: zip of the app image (InfraDesk.exe + runtime); no installer toolchain needed.
tasks.register<Zip>("windowsZip") {
    group = "distribution"
    description = "Zips the Windows app image into build/dist/InfraDesk-<version>-windows.zip"
    dependsOn("selfTestAppImage")
    onlyIf { isWindows }
    from(appImageDir.map { it.dir("InfraDesk") })
    into("InfraDesk")
    archiveFileName = "InfraDesk-${project.version}-windows.zip"
    destinationDirectory = distDir
}
