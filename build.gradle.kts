plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
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
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
}
