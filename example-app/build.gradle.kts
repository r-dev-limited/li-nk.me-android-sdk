plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nz.co.rdev.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.link.example"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Allow overriding the base URL via env or Gradle property
        val envBase = System.getenv("LINKME_BASE_URL")
        val propBase = project.findProperty("LINKME_BASE_URL") as String?
        val linkmeBaseUrl = (envBase?.takeIf { it.isNotBlank() }
            ?: propBase?.takeIf { it.isNotBlank() }
            ?: "http://10.0.2.2:8080")

        // App Links host placeholder for manifest (defaults to li-nk.me)
        val envHost = System.getenv("LINKME_APP_LINKS_HOST")
        val propHost = project.findProperty("LINKME_APP_LINKS_HOST") as String?
        val appLinksHost = (envHost?.takeIf { it.isNotBlank() }
            ?: propHost?.takeIf { it.isNotBlank() }
            ?: "li-nk.me")

        buildConfigField("String", "LINKME_BASE_URL", "\"$linkmeBaseUrl\"")
        buildConfigField("String", "LINKME_APP_LINKS_HOST", "\"$appLinksHost\"")
        manifestPlaceholders["LINKME_BASE_URL"] = linkmeBaseUrl
        manifestPlaceholders["LINKME_APP_LINKS_HOST"] = appLinksHost
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("minifiedRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // Avoid duplicate license/metadata files causing packageDebug failures
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(project(":linkmekit"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
