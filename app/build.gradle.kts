plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gr.aircat.android"
    compileSdk = 35

    defaultConfig {
        val parallelWifi = providers.gradleProperty("wifiParallel").orNull == "true"
        applicationId = if (parallelWifi) "gr.aircat.androidradaranalyzer.wifi23"
                        else "gr.aircat.androidradaranalyzer"
        manifestPlaceholders["appLabel"] = if (parallelWifi) "AIRCAT500 WiFi 2.3" else "AIRCAT500 Mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
