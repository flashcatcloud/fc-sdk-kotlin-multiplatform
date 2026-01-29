/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

import dev.mokkery.MockMode
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.androidLibrary)
    id("datadog-build-config")
    alias(libs.plugins.dependencyLicense)
    id("api-surface")
    id("transitive-dependencies")
    alias(libs.plugins.mokkery)

    // publishing
    `maven-publish`
    signing
}

kotlin {

    cocoapods {
        // need to build with XCode 15
        ios.deploymentTarget = "12.0"
        noPodspec()

        framework {
            baseName = "DatadogKMPWebView"
        }
        // Use linkOnly and configure custom cinterop instead
        pod("FlashcatWebViewTracking") {
            // TODO RUM-5208 by some reason ootb bindings for FlashcatWebViewTracking are not generated correctly, so
            //  we go with a custom header (see custom cinterop below)
            linkOnly = true
            version = libs.versions.datadog.ios.get()
        }
        pod("FlashcatCore") {
            linkOnly = true
            version = libs.versions.datadog.ios.get()
        }
        pod("FlashcatCrashReporting") {
            linkOnly = true
            version = libs.versions.datadog.ios.get()
        }
    }

    targets.all {
        if (this is KotlinNativeTarget && konanTarget.family == Family.IOS) {
            val isSimulator = konanTarget.name.contains("simulator", ignoreCase = true) ||
                konanTarget.name.contains("x64", ignoreCase = true)
            val sdkName = if (isSimulator) "iphonesimulator" else "iphoneos"
            val podsDir = layout.buildDirectory.dir("cocoapods/synthetic/ios")
            val frameworkSearchPath = podsDir.get().dir("build/Debug-$sdkName").asFile.absolutePath

            compilations.getByName("main") {
                cinterops.create("DatadogWebView") {
                    includeDirs("$projectDir/src/nativeInterop/cinterop/DatadogWebViewTracking")
                }
                cinterops.create("DatadogCore") {
                    extraOpts += listOf(
                        "-compiler-option", "-fmodules",
                        "-compiler-option", "-F$frameworkSearchPath/FlashcatCore",
                        "-compiler-option", "-F$frameworkSearchPath/FlashcatInternal"
                    )
                }
                cinterops.create("DatadogCrashReporting") {
                    extraOpts += listOf(
                        "-compiler-option", "-fmodules",
                        "-compiler-option", "-F$frameworkSearchPath/FlashcatCrashReporting",
                        "-compiler-option", "-F$frameworkSearchPath/FlashcatCore",
                        "-compiler-option", "-F$frameworkSearchPath/FlashcatInternal"
                    )
                }
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.datadog.android.webview)
        }
        commonMain.dependencies {
            api(projects.core)
        }
        iosTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(projects.tools.unit)
        }
    }
}

android {
    namespace = "com.datadog.kmp.webview"
}

mokkery {
    defaultMockMode = MockMode.autofill
    ignoreFinalMembers = true
}

datadogBuildConfig {
    pomDescription = "The WebView tracking feature to use with the Datadog monitoring library for Kotlin Multiplatform."
}

// Ensure cinterop tasks depend on Pod build tasks
tasks.matching { it.name.startsWith("cinteropDatadogCore") || it.name.startsWith("cinteropDatadogCrashReporting") }.configureEach {
    when {
        name.contains("DatadogCore") && (name.contains("IosSimulator", ignoreCase = true) || name.contains("IosX64", ignoreCase = true)) ->
            dependsOn("podBuildFlashcatCoreIosSimulator")
        name.contains("DatadogCore") && name.contains("IosArm64", ignoreCase = true) ->
            dependsOn("podBuildFlashcatCoreIos")

        name.contains("DatadogCrashReporting") && (name.contains("IosSimulator", ignoreCase = true) || name.contains("IosX64", ignoreCase = true)) ->
            dependsOn("podBuildFlashcatCrashReportingIosSimulator")
        name.contains("DatadogCrashReporting") && name.contains("IosArm64", ignoreCase = true) ->
            dependsOn("podBuildFlashcatCrashReportingIos")

        else -> dependsOn("podBuildFlashcatCoreIos", "podBuildFlashcatCrashReportingIos")
    }
}
