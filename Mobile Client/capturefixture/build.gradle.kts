plugins { id("com.android.application") }

android {
    namespace = "cn.weiguang.capturefixture"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.weiguang.capturefixture"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
