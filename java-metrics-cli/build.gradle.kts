plugins {
    id("application")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

configurations {
    val testImplementation = configurations.getByName("testImplementation")
    val testRuntimeOnly = configurations.getByName("testRuntimeOnly")

    create("integrationTestImplementation") {
        extendsFrom(testImplementation)
    }
    create("integrationTestRuntimeOnly") {
        extendsFrom(testRuntimeOnly)
    }
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets {
    val main by getting
    create("integrationTest") {
        compileClasspath += main.output
        runtimeClasspath += main.output
        java.srcDir("src/integration-test/java")
        resources.srcDir("src/integration-test/resources")
    }
}

dependencies {
    implementation(project(":java-metrics-lib"))
    implementation("info.picocli:picocli:4.7.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
}

application {
    mainClass.set("org.b333vv.metric.cli.JavaMetricsCliMain")
}

tasks.test {
    useJUnitPlatform()

    // Metric values are serialized as strings produced by Value.toString(), which formats doubles
    // with a DecimalFormat created from the default locale (see DEBT-07). Pinning the locale keeps
    // the JSON contract goldens reproducible on machines with a non-English locale.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")

    // Golden (snapshot) tests of the JSON output contract resolve their fixtures from the source
    // tree, so the module directory is passed explicitly instead of relying on the working
    // directory. See org.b333vv.metric.cli.JsonContractGoldenTest.
    systemProperty("goldenCliProjectDir", layout.projectDirectory.asFile.absolutePath)

    // Regeneration of the golden files is an explicit developer action:
    //   ./gradlew :java-metrics-cli:test -Dgoldens.update=true
    // Gradle does not forward command-line -D properties to test JVMs, so do it here.
    providers.gradleProperty("goldens.update")
            .orElse(providers.systemProperty("goldens.update"))
            .orNull
            ?.let { systemProperty("goldens.update", it) }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Java Metrics CLI distribution smoke tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    dependsOn(tasks.installDist)
    // The shadow jar is the artifact users actually download, and it is built with minimize(), which
    // strips classes it cannot prove are reachable. The JSON contract is reached reflectively (record
    // accessors, mixins, custom serializers), so it can only be proven against the packaged jar.
    dependsOn(tasks.shadowJar)
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
    systemProperty(
            "javaMetricsCliBinary",
            layout.buildDirectory.file("install/java-metrics-cli/bin/java-metrics-cli").get().asFile.absolutePath)
    systemProperty(
            "javaMetricsCliBatBinary",
            layout.buildDirectory.file("install/java-metrics-cli/bin/java-metrics-cli.bat").get().asFile.absolutePath)
    systemProperty(
            "javaMetricsCliShadowJar",
            layout.buildDirectory.file("libs/java-metrics.jar").get().asFile.absolutePath)
}

// `check` is the gate AGENTS.md names, so the distribution proof has to be part of it. Without this
// the integration test only runs when someone remembers to ask for it, which is exactly how a
// minimize() regression reaches users: it passes every unit test and fails at the first analyze.
tasks.check {
    dependsOn(tasks.named("integrationTest"))
}

tasks.shadowJar {
    archiveBaseName.set("java-metrics")
    archiveClassifier.set("")
    minimize()
    mergeServiceFiles()
}
