plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.btmessenger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.btmessenger.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // enableEdgeToEdge / SystemBarStyle (раньше шла транзитивно через appcompat, теперь указана явно)
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Тесты протокола на компьютере (без телефона). В android.jar org.json — заглушка,
    // поэтому для тестов подключаем настоящую реализацию.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
