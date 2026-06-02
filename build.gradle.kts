plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.github.paco-gillet"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }

    // WinRT access uses JNA, which is provided by the IntelliJ Platform at runtime; the imports
    // (com.sun.jna.*) resolve against the platform dependency, so no extra dependency is needed.
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            // Empty untilBuild = no upper bound, so the plugin keeps working on future IDE builds.
            untilBuild = provider { null }
        }

        changeNotes = """
            <b>1.1.0</b>
            <ul>
              <li>Seek by clicking or dragging the progress bar, with a live time preview while dragging.</li>
              <li>The progress now advances smoothly for all sources (including browsers), instead of
                  only updating on play/pause or track changes.</li>
            </ul>
            <b>1.0.0</b>
            <ul>
              <li>Control the active Windows media session from a tool window — cover art, track
                  title/artist, a progress bar and play / pause / next / previous controls, powered by
                  the Windows System Media Transport Controls (WinRT) API.</li>
            </ul>
        """.trimIndent()
    }

    // Optional plugin signing — recommended by JetBrains. Keys are read from the environment (or
    // gradle.properties) and are never committed; if they are absent, `signPlugin` is simply not
    // used and `buildPlugin` still works. See README "Publishing".
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN").map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // `./gradlew publishPlugin` uploads the (signed) ZIP to the Marketplace using PUBLISH_TOKEN.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("publishToken"))
        channels = listOf("default") // stable channel
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
