import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Call IntelliJ's interface default methods instead of copying them into our classes;
        // the copies show up as deprecated and experimental API use in verifyPlugin.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Build and run against your installed Android Studio if `localStudioPath` is set
        // (see gradle.properties); otherwise download the version below.
        val localStudio = providers.gradleProperty("localStudioPath").orNull
        if (localStudio != null) {
            local(localStudio)
        } else {
            androidStudio(providers.gradleProperty("androidStudioVersion").get())
        }
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Unpack downloaded IDEs into .intellijPlatform/ides/ instead of Gradle's transforms cache. CI's setup-gradle
    // saves and restores that cache, and a restored Android Studio came back without its core plugin.
    caching {
        ides {
            enabled = true
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.AndroidStudio, providers.gradleProperty("androidStudioVersion").get())
        }
    }
}

ktlint {
    version = "1.8.0"
}
