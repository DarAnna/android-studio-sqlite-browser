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
        bundledPlugin("org.jetbrains.android")
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
    // Marketplace secrets never live in the repo. Locally: the key and certificate in ~/.config/jetbrains-marketplace,
    // the password and token in ~/.gradle/gradle.properties. CI: environment variables (see CONTRIBUTING.md).
    signing {
        val keyDir = File(System.getProperty("user.home"), ".config/jetbrains-marketplace")
        val chainFile = keyDir.resolve("chain.crt")
        val keyFile = keyDir.resolve("private.pem")
        if (chainFile.isFile && keyFile.isFile) {
            certificateChainFile = chainFile
            privateKeyFile = keyFile
        } else {
            certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
            privateKey = providers.environmentVariable("PRIVATE_KEY")
        }
        password =
            providers
                .gradleProperty("marketplaceKeyPassword")
                .orElse(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }
    publishing {
        token = providers.gradleProperty("marketplaceToken").orElse(providers.environmentVariable("PUBLISH_TOKEN"))
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
