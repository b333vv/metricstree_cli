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
}

tasks.register<Test>("integrationTest") {
    description = "Runs Java Metrics CLI distribution smoke tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    dependsOn(tasks.installDist)
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
    systemProperty(
            "javaMetricsCliBinary",
            layout.buildDirectory.file("install/java-metrics-cli/bin/java-metrics-cli").get().asFile.absolutePath)
    systemProperty(
            "javaMetricsCliBatBinary",
            layout.buildDirectory.file("install/java-metrics-cli/bin/java-metrics-cli.bat").get().asFile.absolutePath)
}

tasks.shadowJar {
    archiveBaseName.set("java-metrics")
    archiveClassifier.set("")
    minimize()
    mergeServiceFiles()
}
