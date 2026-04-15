plugins {
    id("java-library")
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:24.0.1")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.10")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("benchmark") {
    group = "performance"
    description = "Run performance benchmark"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.b333vv.metric.library.javaparser.PerformanceRunner")
}