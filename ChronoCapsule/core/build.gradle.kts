plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
val prepareOcrAssets = tasks.register<Sync>("prepareOcrAssets") {
    from("../app/src/main/assets") {
        include("models/ch_PP-OCRv6*", "models/ppocr_keys_v6.txt")
    }
    into(layout.buildDirectory.dir("generated/ocrAssets"))
}

android {
    namespace = "com.chronocapsule.core"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("ocr", "struct", "contract", "dedup", "pipeline").map { "../app/src/main/java/com/chronocapsule/$it" })
            assets.srcDir(layout.buildDirectory.dir("generated/ocrAssets"))
        }
        getByName("test") {
            java.setSrcDirs(listOf("../app/src/test/java/com/chronocapsule/dedup", "src/test/java"))
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareOcrAssets) }
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
