plugins {
    id("com.android.application")
}

// Port of debug-db's server. Change it with -PdebugDbPort=8081 when another debug app on the
// device already holds 8080.
val debugDbPort = providers.gradleProperty("debugDbPort").getOrElse("8080")

android {
    namespace = "io.github.daranna.sqlitebrowser.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.daranna.sqlitebrowser.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        debug {
            resValue("string", "PORT_NUMBER", debugDbPort)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Starts an HTTP server (port: debugDbPort above) in debug builds. The plugin talks to it.
    debugImplementation("com.github.amitshekhariitbhu.Android-Debug-Database:debug-db:1.0.7")
}
