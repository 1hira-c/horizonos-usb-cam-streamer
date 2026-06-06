/*
 * Copyright (c) 2026 1hira
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.jetbrains.kotlin.plugin.compose)
}

android {
  namespace = "jp.hitohira.usbcamstreamer"
  compileSdk = 34
  ndkVersion = "30.0.14904198"

  defaultConfig {
    applicationId = "jp.hitohira.usbcamstreamer"
    minSdk = 34
    //noinspection OldTargetApi,ExpiredTargetSdkVersion
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += "arm64-v8a"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures { compose = true }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation(libs.meta.spatial.sdk.uiset)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}
