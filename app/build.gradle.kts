import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from a properties file kept outside the repo (storeFile, storePassword,
// keyAlias, keyPassword). Without it, `assembleRelease` still works but the APK is unsigned.
val signingProps = Properties().apply {
    val path = System.getenv("BUTTON_HOOKS_SIGNING")
        ?: "${System.getProperty("user.home")}/.config/button-hooks/keystore.properties"
    file(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val appVersion = "0.3.3"

android {
    namespace = "com.barrettcoats.buttonhooks"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barrettcoats.buttonhooks"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = appVersion
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

base {
    archivesName.set("button-hooks-$appVersion")
}
