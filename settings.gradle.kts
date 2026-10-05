rootProject.name = "MetricsTree"

include("java-metrics-lib")
include("java-metrics-cli")

// Enable Gradle's JDK toolchain provisioning so a missing JDK is downloaded instead of
// failing the build. This is required on GitHub-hosted Windows, where the runner ships no
// JDK in Gradle's default detection paths (even though setup-java installs one into a cache
// Gradle does not look at). When a required toolchain is absent, Gradle pulls it from the
// public Temurin repository over the network.
System.setProperty("org.gradle.java.installations.auto-download", "true")

