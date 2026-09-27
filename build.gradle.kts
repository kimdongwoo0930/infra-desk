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
    implementation(libs.oci.core)
    implementation(libs.oci.httpclient)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.java.keyring)
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
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
