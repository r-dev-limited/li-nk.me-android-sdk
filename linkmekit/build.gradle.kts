import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "me.li-nk"
version = "0.2.7"

android {
    namespace = "me.link.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")
    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "linkmekit"
                version = project.version.toString()
                pom {
                    name.set("LinkMeKit")
                    description.set("Android SDK for LinkMe")
                    url.set("https://github.com/r-dev-limited/li-nk.me-android-sdk")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("linkme")
                            name.set("LinkMe")
                            email.set("support@li-nk.me")
                        }
                    }
                    scm {
                        url.set("https://github.com/r-dev-limited/li-nk.me-android-sdk")
                        connection.set("scm:git:https://github.com/r-dev-limited/li-nk.me-android-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/r-dev-limited/li-nk.me-android-sdk.git")
                    }
                }
            }
        }
    }
}
