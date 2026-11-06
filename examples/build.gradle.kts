// Runnable examples and webhook receivers for popular frameworks. They are not part of the
// published library; CI compiles them and runs the Kotlin API tests.
plugins {
  java
  alias(libs.plugins.kotlin.jvm)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

kotlin {
  jvmToolchain(17)
  compilerOptions {
    allWarningsAsErrors = true
    // Kotlin reads the library's JSpecify annotations: nullable types where the API allows null.
    freeCompilerArgs.add("-Xjspecify-annotations=strict")
  }
}

dependencies {
  implementation(project(":"))
  implementation(libs.spring.boot.starter.web)
  implementation(libs.javalin)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  runtimeOnly(libs.slf4j.simple)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(kotlin("test-junit5"))
  testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
}

tasks.test {
  useJUnitPlatform()
  systemProperty("didit.repoRoot", rootDir.absolutePath)
}

fun example(name: String, mainClass: String, text: String) =
  tasks.register<JavaExec>(name) {
    description = text
    group = "examples"
    classpath = sourceSets.main.get().runtimeClasspath
    this.mainClass = mainClass
  }

example(
  "quickstartDemo",
  "me.didit.examples.QuickstartDemo",
  "Runs the quickstart against a local mock of the Didit API.",
)

example("quickstart", "me.didit.examples.Quickstart", "Runs the quickstart against DIDIT_BASE_URL.")
