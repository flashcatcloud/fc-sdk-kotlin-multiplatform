/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.build.plugin.config

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.datadog.build.ProjectConfig
import com.datadog.build.utils.taskConfig
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.konan.target.Family
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithTests
import org.jetbrains.kotlin.gradle.targets.native.KotlinNativeSimulatorTestRun
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class DatadogProjectConfigurationPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create<DatadogBuildConfigExtension>("datadogBuildConfig")
        target.pluginManager.withPlugin("com.android.application") {
            target.logger.info("Found Android Application Plugin in project ${target.path}, applying configuration")
            target.applyKotlinConfig(extension)
            target.applyApplicationAndroidConfig(extension)
        }

        target.pluginManager.withPlugin("com.android.library") {
            target.logger.info("Found Android Library Plugin in project ${target.path}, applying configuration")
            target.applyKotlinConfig(extension)
            target.applyLibraryAndroidConfig(extension)
        }

        target.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            target.logger.info("Found Kotlin Multiplatform Plugin in project ${target.path}, applying configuration")
            target.applyKotlinMultiplatformConfig(extension)
        }

        target.pluginManager.withPlugin("org.gradle.maven-publish") {
            target.logger.info("Found Maven Publish Plugin in project ${target.path}, applying configuration")
            target.applyPublishingConfig(extension)
        }
    }
}

// region Kotlin

private fun Project.applyKotlinConfig(configExtension: DatadogBuildConfigExtension) {
    kotlinExtension.apply {
        sourceSets.all {
            languageSettings {
                languageVersion = configExtension.kotlinVersionOrDefault.version
                apiVersion = configExtension.kotlinVersionOrDefault.version
            }
        }
    }
    taskConfig<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(configExtension.jvmTargetOrDefault)
            // there are few warnings coming from the fact that the JetBrains Compose Compiler is the new one, but AGP
            // is still using old values for the configuration
            // w: intrinsicRemember is deprecated. Use
            // plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=IntrinsicRemember instead
            // w: nonSkippingGroupOptimization is deprecated. Use
            // plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=OptimizeNonSkippingGroups instead
            // w: experimentalStrongSkipping is deprecated. Use
            // plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=StrongSkipping instead
            allWarningsAsErrors.set(project.name != "androidApp")
        }
    }
    taskConfig<Test> {
        useJUnitPlatform()
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
private fun Project.applyKotlinMultiplatformConfig(configExtension: DatadogBuildConfigExtension) {
    val projectToApply = this
    extensions.getByType<KotlinMultiplatformExtension>()
        .apply {
            if (!projectToApply.displayName.contains("tools")) {
                androidTarget {
                    compilerOptions {
                        jvmTarget.set(configExtension.jvmTargetOrDefault)
                    }
                }
            } else {
                jvm()
            }
            // TODO RUM-4231 Add watchOS target
            iosX64()
            iosArm64()
            iosSimulatorArm64()

            // at this point DatadogBuildConfigExtension is not yet read, so just use project names instead of
            // doing `afterEvaluate` tricks
            if (projectToApply.path !in setOf(":features:session-replay", ":features:webview")) {
                tvosX64()
                tvosArm64()
                tvosSimulatorArm64()
            }

            sourceSets.all {
                if (name.startsWith("apple") || name.startsWith("ios") || name.startsWith("tvos")) {
                    languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
                }
            }

            targets.all {
                if (this is KotlinNativeTargetWithTests<*>) {
                    testRuns.all {
                        if (this is KotlinNativeSimulatorTestRun) {
                            // Need to find a way to be more precise, to specify OS runtime version. Should be
                            // aligned with what is in CI file.
                            deviceId = when (konanTarget.family) {
                                Family.IOS -> {
                                    "iPhone 15 Pro Max"
                                }

                                Family.TVOS -> {
                                    "Apple TV"
                                }

                                else -> throw IllegalArgumentException(
                                    "Unknown family for the device ID selection: ${konanTarget.family}"
                                )
                            }
                        }
                    }
                }
                if (this is HasConfigurableKotlinCompilerOptions<*>) {
                    compilerOptions {
                        if (this is KotlinJvmCompilerOptions) {
                            jvmTarget.set(configExtension.jvmTargetOrDefault)
                        }
                        // https://kotlinlang.org/docs/components-stability.html#current-stability-of-kotlin-components
                        // https://youtrack.jetbrains.com/issue/KT-61573
                        // expect/actual classes are in beta since 1.7.20 (and they still are as of 1.9.24), but we
                        // are going to use them anyway
                        freeCompilerArgs.add("-Xexpect-actual-classes")
                        apiVersion.set(configExtension.kotlinVersionOrDefault)
                        languageVersion.set(configExtension.kotlinVersionOrDefault)
                        allWarningsAsErrors.set(true)
                    }
                }
            }
            afterEvaluate {
                // is not taken into account in KMP by some reason if without afterEvaluate
                sourceSets.all {
                    languageSettings {
                        languageVersion = configExtension.kotlinVersionOrDefault.version
                        apiVersion = configExtension.kotlinVersionOrDefault.version
                    }
                }

                applySwiftCompatibilityLinkingWorkaround(this@apply)
                projectToApply.applyLocalPodSourceConfig(this@apply)
                projectToApply.applyCinteropCocoapodsDependencies(this@apply)
            }
        }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
private fun applySwiftCompatibilityLinkingWorkaround(kmpExtension: KotlinMultiplatformExtension) {
    (kmpExtension as ExtensionAware).extensions
        .findByType<CocoapodsExtension>()
        ?.pods
        ?.matching { it.name == "FlashcatCrashReporting" }
        ?.all {
            kmpExtension
                .targets
                .withType<KotlinNativeTarget>()
                .matching { it.konanTarget.family.isAppleFamily }
                .all {
                    val swiftCompatibilityArgs = if (System.getenv("DEVELOPER_DIR") != null) {
                        // case env injected from XCode
                        "-L${System.getenv("DEVELOPER_DIR")}/Toolchains/XcodeDefault.xctoolchain" +
                            "/usr/lib/swift/${System.getenv("PLATFORM_NAME")}"
                    } else {
                        "-U __swift_FORCE_LOAD_\$_swiftCompatibility50 " +
                            "-U __swift_FORCE_LOAD_\$_swiftCompatibility51 " +
                            "-U __swift_FORCE_LOAD_\$_swiftCompatibility56 " +
                            "-U __swift_FORCE_LOAD_\$_swiftCompatibilityConcurrency " +
                            "-U __swift_FORCE_LOAD_\$_swiftCompatibilityDynamicReplacements"
                    }
                    compilerOptions {
                        freeCompilerArgs.addAll(
                            listOf(
                                "-linker-options",
                                // TODO RUM-6047 Kotlin Compiler cannot locate these during the linking
                                //  done via pods integration
                                swiftCompatibilityArgs
                            )
                        )
                    }
                }
        }
}

private fun Project.applyLocalPodSourceConfig(kmpExtension: KotlinMultiplatformExtension) {
    // 1. Try to read from local.properties
    var localPath: String? = null
    var gitUrl: String? = null
    var gitTag: String? = null
    var gitBranch: String? = null
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val properties = java.util.Properties()
        localPropertiesFile.inputStream().use { properties.load(it) }
        localPath = properties.getProperty("FC_IOS_SDK_LOCAL_PATH")
        gitUrl = properties.getProperty("FC_IOS_SDK_GIT_URL")
        gitTag = properties.getProperty("FC_IOS_SDK_GIT_TAG")
        gitBranch = properties.getProperty("FC_IOS_SDK_GIT_BRANCH")
    }

    // 2. Fallback to gradle properties (gradle.properties or env variables)
    if (localPath.isNullOrBlank()) {
        localPath = findProperty("FC_IOS_SDK_LOCAL_PATH")?.toString()
    }
    if (gitUrl.isNullOrBlank()) {
        gitUrl = findProperty("FC_IOS_SDK_GIT_URL")?.toString()
    }
    if (gitTag.isNullOrBlank()) {
        gitTag = findProperty("FC_IOS_SDK_GIT_TAG")?.toString()
    }
    if (gitBranch.isNullOrBlank()) {
        gitBranch = findProperty("FC_IOS_SDK_GIT_BRANCH")?.toString()
    }

    val podsExtension = (kmpExtension as ExtensionAware).extensions.findByType<CocoapodsExtension>() ?: return

    if (!localPath.isNullOrBlank()) {
        logger.info("Using local Flashcat iOS SDK from: $localPath")
        podsExtension.pods.all {
            this.version = null
            this.source = this.path(file(localPath!!))
        }
    } else if (!gitUrl.isNullOrBlank()) {
        val logMsg = when {
            !gitTag.isNullOrBlank() -> "tag: $gitTag"
            !gitBranch.isNullOrBlank() -> "branch: $gitBranch"
            else -> "default"
        }
        logger.info("Using Flashcat iOS SDK from Git: $gitUrl ($logMsg)")
        podsExtension.pods.all {
            this.version = null
            this.source = when {
                !gitTag.isNullOrBlank() -> this.git(gitUrl!!) { tag = gitTag }
                !gitBranch.isNullOrBlank() -> this.git(gitUrl!!) { branch = gitBranch }
                else -> this.git(gitUrl!!)
            }
        }
    }
}

private fun Project.applyCinteropCocoapodsDependencies(kmpExtension: KotlinMultiplatformExtension) {
    val cocoapodsExtension = (kmpExtension as ExtensionAware).extensions
        .findByType<CocoapodsExtension>() ?: return

    kmpExtension.targets.withType<KotlinNativeTarget>().matching { it.konanTarget.family.isAppleFamily }.all {
        val target = this
        val isSimulator = target.konanTarget.name.contains("simulator", ignoreCase = true) ||
            target.konanTarget.name.contains("x64", ignoreCase = true)
        val sdkName = when (target.konanTarget.family) {
            Family.IOS -> if (isSimulator) "iphonesimulator" else "iphoneos"
            Family.TVOS -> if (isSimulator) "appletvsimulator" else "appletvos"
            else -> "iphoneos"
        }
        val podsBaseDir = if (target.konanTarget.family == Family.TVOS) {
            "cocoapods/synthetic/tvos"
        } else {
            "cocoapods/synthetic/ios"
        }
        val frameworkSearchPath = layout.buildDirectory.dir(podsBaseDir).get().dir("build/Debug-$sdkName").asFile.absolutePath

        target.compilations.getByName("main").cinterops.all {
            val cinterop = this
            if (cinterop.name.startsWith("Datadog") && cinterop.name != "DatadogWebView") {
                cocoapodsExtension.pods.all {
                    val podName = this.name
                    cinterop.extraOpts += listOf(
                        "-compiler-option", "-fmodules",
                        "-compiler-option", "-F$frameworkSearchPath/$podName",
                        "-compiler-option", "-F$frameworkSearchPath/FlashcatInternal"
                    )
                }
            }
        }
    }

    // Auto-link tasks dependencies
    tasks.matching {
        it.name.startsWith("cinterop")
    }.configureEach {
        val cinteropTask = this
        val isIosSimulator = cinteropTask.name.contains("IosSimulator", ignoreCase = true) || cinteropTask.name.contains("IosX64", ignoreCase = true)
        val isIosArm64 = cinteropTask.name.contains("IosArm64", ignoreCase = true)
        val isTvosSimulator = cinteropTask.name.contains("TvosSimulator", ignoreCase = true) || cinteropTask.name.contains("TvosX64", ignoreCase = true)
        val isTvosArm64 = cinteropTask.name.contains("TvosArm64", ignoreCase = true)

        cocoapodsExtension.pods.all {
            val podName = this.name
            val dependTask = when {
                isIosSimulator -> "podBuild${podName}IosSimulator"
                isIosArm64 -> "podBuild${podName}Ios"
                isTvosSimulator -> "podBuild${podName}TvosSimulator"
                isTvosArm64 -> "podBuild${podName}Tvos"
                else -> "podBuild${podName}Ios"
            }
            cinteropTask.dependsOn(dependTask)
        }
    }
}

// endregion

// region Android

private fun Project.applyApplicationAndroidConfig(configExtension: DatadogBuildConfigExtension) {
    extensions.getByType<BaseAppModuleExtension>()
        .apply {
            val javaVersion = configExtension.jvmTargetOrDefault.toJavaVersion()
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            compileSdk = ProjectConfig.Android.COMPILE_SDK
            buildToolsVersion = ProjectConfig.Android.BUILD_TOOLS_VERSION

            defaultConfig {
                minSdk = ProjectConfig.Android.MIN_SDK
                targetSdk = ProjectConfig.Android.COMPILE_SDK
                versionCode = ProjectConfig.VERSION.code
                versionName = ProjectConfig.VERSION.name
            }

            sourceSets.all {
                java.srcDir("src/$name/kotlin")
            }

            @Suppress("UnstableApiUsage")
            testOptions {
                unitTests.isReturnDefaultValues = true
            }

            lintConfigure()
            packagingConfigure()
        }
}

private fun Project.applyLibraryAndroidConfig(configExtension: DatadogBuildConfigExtension) {
    extensions.getByType<LibraryExtension>()
        .apply {
            val javaVersion = configExtension.jvmTargetOrDefault.toJavaVersion()
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            compileSdk = ProjectConfig.Android.COMPILE_SDK
            buildToolsVersion = ProjectConfig.Android.BUILD_TOOLS_VERSION

            defaultConfig {
                minSdk = ProjectConfig.Android.MIN_SDK
            }

            sourceSets.all {
                java.srcDir("src/$name/kotlin")
            }

            @Suppress("UnstableApiUsage")
            testOptions {
                unitTests.isReturnDefaultValues = true
            }

            lintConfigure()
            packagingConfigure()
        }
}

private fun CommonExtension<*, *, *, *, *, *>.lintConfigure() {
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = false
        checkGeneratedSources = true
        ignoreTestSources = true
        // GradleDependency check: A newer version of com.foo.bar than x.x.x is available: y.y.y
        disable += "GradleDependency"
        // AndroidGradlePluginVersion: A newer version of com.android.tools.build:gradle than x.x.x is available: y.y.y
        disable += "AndroidGradlePluginVersion"
    }
}

private fun CommonExtension<*, *, *, *, *, *>.packagingConfigure() {
    packaging {
        resources {
            excludes += listOf(
                "META-INF/jvm.kotlin_module",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

// endregion

// region Publishing

private fun Project.applyPublishingConfig(buildConfigExtension: DatadogBuildConfigExtension) {
    val projectName = name

    // Configure Android target to publish release variant
    extensions.getByType<KotlinMultiplatformExtension>()
        .targets
        .withType<KotlinAndroidTarget> {
            publishLibraryVariants("release")
        }

    // Apply Vanniktech plugin
    pluginManager.apply("com.vanniktech.maven.publish.base")

    // Configure Vanniktech Maven Publish
    configure<MavenPublishBaseExtension> {
        // KotlinMultiplatform automatically handles sources and javadoc for all targets
        configure(
            KotlinMultiplatform(
                javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
                sourcesJar = true,
                androidVariantsToPublish = listOf("release")
            )
        )

        // Coordinates - artifactId will be set in afterEvaluate
        coordinates(
            groupId = ProjectConfig.GROUP_ID,
            artifactId = "fc-sdk-kotlin-multiplatform-$projectName",
            version = ProjectConfig.VERSION.name
        )

        // POM configuration
        pom {
            name.set(projectName)
            description.set(
                buildConfigExtension.pomDescription.map {
                    it.ifEmpty {
                        throw IllegalStateException("Published projects should have a description")
                    }
                }
            )
            inceptionYear.set("2025")
            url.set("https://github.com/flashcatcloud/fc-sdk-kotlin-multiplatform")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            organization {
                name.set("FlashCat, Inc.")
                url.set("https://flashcat.cloud")
            }

            developers {
                developer {
                    id.set("flashcat")
                    name.set("FlashCat")
                    email.set("support@flashcat.cloud")
                    organization.set("FlashCat, Inc.")
                    organizationUrl.set("https://flashcat.cloud")
                }
            }

            scm {
                url.set("https://github.com/flashcatcloud/fc-sdk-kotlin-multiplatform")
                connection.set("scm:git:git@github.com:flashcatcloud/fc-sdk-kotlin-multiplatform.git")
                developerConnection.set("scm:git:git@github.com:flashcatcloud/fc-sdk-kotlin-multiplatform.git")
            }
        }

        // Publish to Maven Central (Vanniktech 0.33.0+ supports Central Portal snapshots)
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    }

    // Manual signing configuration
    // This allows using base64-encoded GPG keys
    val signingExtension = extensions.findByType(SigningExtension::class)
    if (signingExtension == null) {
        logger.error("Missing signing extension for $projectName")
        return
    }

    signingExtension.apply {
        // Signing is required unless explicitly skipped
        isRequired = !hasProperty("dd-skip-signing")

        val privateKey = System.getenv("GPG_PRIVATE_KEY")
        val password = System.getenv("GPG_PASSWORD")

        if (privateKey != null && password != null) {
            // Decode base64 if needed
            val decodedKey = try {
                String(java.util.Base64.getDecoder().decode(privateKey))
            } catch (e: Exception) {
                privateKey // Already decoded / plain text
            }
            useInMemoryPgpKeys(decodedKey, password)
        }
    }

    afterEvaluate {
        val publishingExtension = extensions.findByType<PublishingExtension>()
        if (publishingExtension == null) {
            logger.error("Missing publishing extension for $projectName")
            return@afterEvaluate
        }

        // Sign all publications (required by Maven Central)
        publishingExtension.publications.forEach { publication ->
            signingExtension.sign(publication)
        }
    }
}

// endregion

private fun JvmTarget.toJavaVersion(): JavaVersion {
    // list only LTS releases
    return when (this) {
        JvmTarget.JVM_1_8 -> JavaVersion.VERSION_1_8
        JvmTarget.JVM_11 -> JavaVersion.VERSION_11
        JvmTarget.JVM_17 -> JavaVersion.VERSION_17
        JvmTarget.JVM_21 -> JavaVersion.VERSION_21
        else -> throw IllegalArgumentException("Unknown JvmTarget=${this.name}")
    }
}
