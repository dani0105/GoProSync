plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "website.danielrojas.goprosync"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "website.danielrojas.goprosync"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("com.juul.kable:core:0.28.0")
    implementation ("com.alphacephei:vosk-android:0.3.47@aar")
    implementation (project(":models"))
    implementation(libs.androidx.appcompat)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidthings)
    testImplementation(libs.junit)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("io.ktor:ktor-client-core:2.2.3")
    implementation("io.ktor:ktor-client-cio:2.2.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    implementation ("org.jetbrains.kotlin:kotlin-reflect:1.7.20")
    implementation("io.coil-kt:coil-compose:2.2.2")

}
