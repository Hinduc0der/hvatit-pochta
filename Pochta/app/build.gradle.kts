import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ключ подписи — вне git: почта/signing/keystore.properties (+ .jks). Нет файла — релиз подписывается отладочным ключом
val signProps = Properties().apply {
    val f = rootProject.file("../signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hndcdr.pochta"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hndcdr.pochta"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.3"
    }
    signingConfigs {
        create("upload") {
            if (signProps.isNotEmpty()) {
                storeFile = rootProject.file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signProps.isNotEmpty()) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
