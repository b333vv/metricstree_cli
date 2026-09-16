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
    jvmArgs("-Xmx4g")

    // The benchmark corpus is optional: without -Dbenchmark.sourceRoot=... PerformanceBenchmarkTest
    // skips cleanly. Gradle does not forward command-line -D properties to test JVMs, so do it here.
    providers.systemProperty("benchmark.sourceRoot").orNull?.let {
        systemProperty("benchmark.sourceRoot", it)
    }
}

tasks.register<JavaExec>("benchmark") {
    group = "performance"
    description = "Run performance benchmark"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.b333vv.metric.library.javaparser.PerformanceRunner")

    // Same heap as the test task, so the recorded peak heap is comparable between the two.
    maxHeapSize = "4g"

    // See the test task above: -D properties are not inherited by the forked JVM.
    providers.systemProperty("benchmark.sourceRoot").orNull?.let {
        systemProperty("benchmark.sourceRoot", it)
    }
}