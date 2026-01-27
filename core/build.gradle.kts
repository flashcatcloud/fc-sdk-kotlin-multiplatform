import com.datadog.build.ProjectConfig
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import java.nio.file.Paths
import kotlin.io.path.pathString

/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.androidLibrary)
    id("datadog-build-config")
    alias(libs.plugins.dependencyLicense)
    id("api-surface")
    id("transitive-dependencies")

    // publishing
    `maven-publish`
    signing
}

val generateLibConfigTask = tasks.register("generateLibConfig", Sync::class) {
    from(
        resources.text.fromString(
            """
        |package com.datadog.kmp.internal
        |
        |internal object LibraryConfig {
        |  const val SDK_VERSION = "${ProjectConfig.VERSION.name}"
        |}
        |
            """.trimMargin()
        )
    ) {
        rename { "LibraryConfig.kt" }
        into(Paths.get("com", "datadog", "kmp", "internal").pathString)
    }

    val generatedDirectory = layout.buildDirectory.dir(Paths.get("generated", "datadog").pathString)
    into(generatedDirectory)
}

kotlin {

    cocoapods {
        // need to build with XCode 15
        ios.deploymentTarget = "12.0"
        tvos.deploymentTarget = "12.0"
        noPodspec()

        framework {
            baseName = "DatadogKMPCore"
        }

        pod("FlashcatCore") {
            // Use linkOnly and configure a custom cinterop instead
            // because the module name (DatadogCore) differs from the pod name (FlashcatCore)
            linkOnly = true
            version = libs.versions.datadog.ios.get()
        }
        // TODO RUM-11618 FlashcatInternal cannot be used
//        pod("FlashcatInternal") {
//            extraOpts += listOf(
//                "-compiler-option",
//                "-fmodules"
//            )
//            version = libs.versions.datadog.ios.get()
//        }
        pod("FlashcatCrashReporting") {
            // Use linkOnly and configure a custom cinterop instead
            // because the module name (DatadogCrashReporting) differs from the pod name (FlashcatCrashReporting)
            linkOnly = true
            version = libs.versions.datadog.ios.get()
        }
    }

    targets.all {
        if (this is KotlinNativeTarget && konanTarget.family.isAppleFamily) {
            val sdkName = when (konanTarget.family) {
                Family.IOS -> if (konanTarget.name.contains("simulator", ignoreCase = true)) "iphonesimulator" else "iphoneos"
                Family.TVOS -> if (konanTarget.name.contains("simulator", ignoreCase = true)) "appletvsimulator" else "appletvos"
                else -> "iphoneos"
            }
            val podsDir = layout.buildDirectory.dir("cocoapods/synthetic/ios")
            val frameworkSearchPath = podsDir.get().dir("build/Debug-$sdkName").asFile.absolutePath

            compilations.getByName("main") {
                cinterops.create("DDBinaryImages")
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
            implementation(libs.datadog.android.core)
            // Android SDK will bring it
            compileOnly(libs.okHttp)
        }
        androidUnitTest.dependencies {
            implementation(libs.bundles.jUnit5)
            implementation(libs.bundles.jvmTestTools)
            compileOnly(libs.okHttp)
        }
        commonMain.dependencies {
            // put your multiplatform dependencies here
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        appleTest.dependencies {
            implementation(projects.tools.unit)
        }
        commonMain {
            kotlin.srcDir(generateLibConfigTask.map { it.destinationDir })
        }
    }
}

android {
    namespace = "com.datadog.kmp"
}

datadogBuildConfig {
    pomDescription = "The Core module of Datadog monitoring library for Kotlin Multiplatform."
}

// Ensure cinterop tasks depend on Pod build tasks
tasks.matching { it.name.startsWith("cinteropDatadogCore") || it.name.startsWith("cinteropDatadogCrashReporting") }.configureEach {
    dependsOn("podBuildFlashcatCoreIos", "podBuildFlashcatCrashReportingIos")
}
