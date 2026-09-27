plugins {
    application
}

group = "com.infradesk"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.flatlaf)
    implementation(libs.flatlaf.extras)

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

// Dev tool: renders the main window to build/snapshots/main.png without showing it.
tasks.register<JavaExec>("snapshot") {
    group = "application"
    description = "Renders the main window off-screen to build/snapshots/main.png"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.infradesk.ui.UiSnapshot"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
