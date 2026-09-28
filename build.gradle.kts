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
    runtimeOnly(libs.slf4j.jdk14) // library warnings (OCI SDK, MINA) into our java.util.logging file

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Build metadata shown in the app (설정 → 앱 정보) and used by the update check.
// CI passes -PbuildNumber=<run number> and -Pcommit=<sha>; local builds are "dev" with git's HEAD.
val buildNumber = providers.gradleProperty("buildNumber").orElse("dev")
val commitId = providers.gradleProperty("commit").orElse(
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
)
val generateBuildInfo by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/build-info/com/infradesk/app/build-info.properties")
    property("version", project.version.toString())
    property("build", buildNumber)
    property("commit", commitId)
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/build-info")) }
tasks.processResources { dependsOn(generateBuildInfo) }

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

// JVM options shared by `run`, `runDemo`, installDist scripts and the packaged app.
// The live heap is ~20MB, so a small serial-GC heap that grows on demand and shrinks back,
// plus C1-only JIT, keeps the resident size far below the JVM defaults (see DEVLOG "메모리").
val appJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "-XX:+UseSerialGC", "-Xms16m", "-Xmx256m",
    "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
    "-XX:TieredStopAtLevel=1",
)

application {
    applicationDefaultJvmArgs = appJvmArgs
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
    jvmArgs(appJvmArgs)
}

// Runs the app with fake data: no settings file, keychain, or network access.
tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Runs InfraDesk in demo mode with fake servers"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.infradesk.app.InfraDeskApp"
    args("--demo")
    jvmArgs(appJvmArgs)
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
        // Windows shows the description as the process name in Task Manager and jpackage writes
        // non-ASCII text there as "???", so it stays plain ASCII.
        "--vendor", "InfraDesk", "--description", "InfraDesk",
        "--input", libDir.absolutePath, "--main-jar", "infra-desk-${project.version}.jar",
        "--main-class", "com.infradesk.app.InfraDeskApp",
        "--add-modules", runtimeModules.joinToString(","),
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress zip-6",
        "--dest", out.absolutePath,
    )
    appJvmArgs.forEach { args += listOf("--java-options", it) }
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

// macOS: the .app zipped with ditto (keeps symlinks, exec bits and the signature). The app
// downloads this to update itself; the dmg is for first installs.
tasks.register<Exec>("macUpdateZip") {
    group = "distribution"
    description = "Zips the macOS app into build/dist/InfraDesk-<version>-macOS.zip for in-app updates"
    dependsOn("selfTestAppImage")
    mustRunAfter("dmg") // dmg clears build/dist first
    onlyIf { isMac }
    val out = distDir.get().file("InfraDesk-${project.version}-macOS.zip").asFile
    doFirst { out.parentFile.mkdirs(); out.delete() }
    commandLine("ditto", "-c", "-k", "--keepParent", "${appImageDir.get().asFile}/InfraDesk.app", out.absolutePath)
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

// ---- Third-party notices ----
// Lists every runtime library with its license, read from each artifact's POM (following parent
// POMs, where many projects declare their license). Bundled into the app (설정 → 앱 정보 →
// 오픈소스 라이선스). The libraries' own NOTICE files ship unchanged inside their jars.
val generateNotices by tasks.registering {
    val out = layout.buildDirectory.file("generated/notices/com/infradesk/app/THIRD-PARTY-NOTICES.txt")
    outputs.file(out)
    outputs.file(layout.buildDirectory.file("generated/notices/com/infradesk/app/LICENSE.txt"))
    inputs.files(configurations.runtimeClasspath)
    inputs.file(rootProject.file("LICENSE"))
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        fun pomFile(group: String, name: String, version: String): File? = try {
            val cfg = configurations.detachedConfiguration(dependencies.create("$group:$name:$version@pom"))
            cfg.isTransitive = false
            cfg.resolve().firstOrNull()
        } catch (e: Exception) { null }
        fun text(el: org.w3c.dom.Element, tag: String): String? {
            val nodes = el.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n.parentNode == el) return n.textContent.trim()
            }
            return null
        }
        fun licenses(group: String, name: String, version: String, depth: Int = 0): List<String> {
            if (depth > 6) return emptyList()
            val pom = pomFile(group, name, version) ?: return emptyList()
            val doc = factory.newDocumentBuilder().parse(pom).documentElement
            val found = mutableListOf<String>()
            val licenseNodes = doc.getElementsByTagName("license")
            for (i in 0 until licenseNodes.length) {
                val el = licenseNodes.item(i) as org.w3c.dom.Element
                val n = text(el, "name") ?: continue
                val url = text(el, "url")
                found += if (url.isNullOrBlank()) n else "$n <$url>"
            }
            if (found.isNotEmpty()) return found
            val parents = doc.getElementsByTagName("parent")
            if (parents.length == 0) return emptyList()
            val p = parents.item(0) as org.w3c.dom.Element
            return licenses(text(p, "groupId") ?: return emptyList(), text(p, "artifactId") ?: return emptyList(),
                text(p, "version") ?: return emptyList(), depth + 1)
        }
        val modules = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .distinctBy { "${it.group}:${it.name}" }
            .sortedWith(compareBy({ it.group }, { it.name }))
        val sb = StringBuilder()
        sb.appendLine("InfraDesk includes the following third-party software.")
        sb.appendLine("Each library is distributed unmodified under its own license; source code is available")
        sb.appendLine("from Maven Central (https://central.sonatype.com) or the project sites listed there.")
        sb.appendLine("License texts and NOTICE files are included inside each library's jar (META-INF).")
        sb.appendLine()
        for (m in modules) {
            val l = licenses(m.group, m.name, m.version)
            sb.appendLine("${m.group}:${m.name}:${m.version}")
            sb.appendLine("    " + (if (l.isEmpty()) "License: see the project's jar (META-INF)" else l.joinToString("\n    ")))
        }
        sb.appendLine()
        sb.appendLine("Notes:")
        sb.appendLine("- JediTerm (org.jetbrains.jediterm) is LGPL-3.0 (the project also offers Apache-2.0). It is used")
        sb.appendLine("  unmodified as separate jars in the app folder (macOS: InfraDesk.app/Contents/app), which")
        sb.appendLine("  users may replace with their own build.")
        sb.appendLine("- Libraries offered under several licenses (JNA, Javassist, Jersey, HK2, ...) are used under")
        sb.appendLine("  their permissive option (Apache-2.0 / EPL-2.0).")
        sb.appendLine("- The bundled Java runtime is Eclipse Temurin (OpenJDK), GPL-2.0 with the Classpath Exception.")
        out.get().asFile.apply { parentFile.mkdirs(); writeText(sb.toString()) }
        rootProject.file("LICENSE").copyTo(out.get().asFile.resolveSibling("LICENSE.txt"), overwrite = true)
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/notices")) }
tasks.processResources { dependsOn(generateNotices) }
