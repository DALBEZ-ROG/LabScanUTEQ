import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ec.edu.uteq.labscan"

    // compileSdk 37: nivel que exigen AGP 9.x, el Compose BOM vigente y androidx actual.
    // targetSdk se mantiene en 35 segun CLAUDE.md. Ver docs/DECISIONES.md D-002.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ec.edu.uteq.labscan"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        /**
         * Firma de DEMOSTRACION, no de produccion.
         *
         * El almacen viaja dentro del repositorio y su contrasena esta escrita aqui a
         * proposito: sirve para que cualquiera que clone el proyecto pueda generar un APK
         * release instalable sin pedir nada a nadie, que es justo lo que exige la entrega
         * academica. No protege nada y no debe protegerlo: si esta app se publicara alguna
         * vez en Google Play habria que generar un almacen nuevo, guardarlo fuera del
         * control de versiones y pasarlo por variables de entorno.
         */
        create("demo") {
            storeFile = rootProject.file("keystore/labscan-demo.jks")
            storePassword = "labscan2026"
            keyAlias = "labscan"
            keyPassword = "labscan2026"
        }
    }

    buildTypes {
        debug {
            // Backend RAG local. Contrato en docs/CONTRATO_API.md.
            // 10.0.2.2 es el host del PC visto desde el emulador.
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000/\"")
            // F5 enciende el MockRagServer con esta bandera.
            buildConfigField("boolean", "USE_MOCK_API", "true")
        }
        release {
            // Ofuscacion y reduccion. Las reglas de proguard-rules.pro preservan lo que
            // TensorFlow Lite y kotlinx.serialization buscan por reflexion; sin ellas, el
            // modelo no carga y el JSON del backend llega vacio, y las dos cosas fallan
            // solo en el APK firmado.
            optimization {
                enable = true
                keepRules {
                    ignoreFrom("**")
                }
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("demo")

            buildConfigField("String", "BASE_URL", "\"https://labscan-uteq.invalid/\"")
            buildConfigField("boolean", "USE_MOCK_API", "false")
        }
    }

    // El .tflite debe quedar sin comprimir en el APK para poder mapearlo en memoria
    // con MappedByteBuffer. Si se comprime, el Interpreter no puede cargarlo.
    androidResources {
        noCompress += listOf("tflite")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- AndroidX base ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- CameraX: se usa desde F1 (preview) y F2 (ImageAnalysis) ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // --- Inferencia: se usa desde F3, solo dentro del paquete detection/ ---
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)

    // --- Red y serializacion: se usan desde F5, solo dentro de data/remote/ ---
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // --- Pruebas ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
