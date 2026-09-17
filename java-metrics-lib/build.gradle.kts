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

// -D properties are not inherited by forked JVMs, so every knob the benchmark needs is forwarded
// explicitly. `benchmark.sourceRoot` selects the corpus; `metricstree.parallelism` overrides the
// worker count, which is what makes the TASK-205 scaling table reproducible.
val forwardedProperties = listOf("benchmark.sourceRoot", "metricstree.parallelism")

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx4g")

    // The benchmark corpus is optional: without -Dbenchmark.sourceRoot=... PerformanceBenchmarkTest
    // skips cleanly.
    forwardedProperties.forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

tasks.register<JavaExec>("benchmark") {
    group = "performance"
    description = "Run performance benchmark"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.b333vv.metric.library.javaparser.PerformanceRunner")

    // Same heap as the test task, so the recorded peak heap is comparable between the two.
    maxHeapSize = "4g"

    forwardedProperties.forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}