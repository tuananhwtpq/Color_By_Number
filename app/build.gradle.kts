import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.baseproject"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.baseproject"
        minSdk = 29
        targetSdk = 36
//        versionCode = 100
//        versionName = "1.0.0"

        versionCode = 1
        versionName = "test"

        val dateTime = SimpleDateFormat("dd-MM-yyyy").format(System.currentTimeMillis())
        setProperty("archivesBaseName", "Base_project_($versionCode)_$dateTime")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {

            // Tự động tìm file có đuôi .jks hoặc .keystore đầu tiên trong thư mục app
            val keystoreFile = project.file(".")
                .listFiles()
                ?.firstOrNull { it.name.endsWith(".jks") || it.name.endsWith(".keystore") }
                ?: file("release.jks") // File dự phòng nếu không tìm thấy

            // Tự động nhận diện file jks nằm cùng thư mục
            storeFile = keystoreFile

            // Gọi mật khẩu an toàn từ biến Group của GitLab
            storePassword = System.getenv("COMPANY_KEY_PASSWORD") ?: ""
            keyPassword = System.getenv("COMPANY_KEY_PASSWORD") ?: ""

            keyAlias = "key0"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Áp dụng cấu hình release vừa thiết lập
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin.compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }

    buildFeatures {
        viewBinding = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    //ads library
    implementation(libs.ssquadadslibrary)

    //other library
    implementation(libs.lottie)
    implementation(libs.glide)
    implementation(libs.user.messaging.platform)

    implementation(libs.gson)

    implementation(libs.play.services.ads)

    // Gson for parsing level config
    implementation("com.google.code.gson:gson:2.10.1")
    // Coroutines for background tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")


}