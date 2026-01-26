/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.build

import com.datadog.build.utils.Version

object ProjectConfig {
    object Android {
        const val MIN_SDK = 23
        const val COMPILE_SDK = 36
        const val BUILD_TOOLS_VERSION = "36.0.0"
    }

    const val GROUP_ID = "cloud.flashcat"

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private val isTagBuild: Boolean =
        !env("CI_COMMIT_TAG").isNullOrBlank() ||
            env("GITHUB_REF_TYPE") == "tag"

    private val isPublishBranch: Boolean =
        env("CI_COMMIT_BRANCH") == "publish" ||
            env("GITHUB_REF") == "refs/heads/publish" ||
            (env("GITHUB_REF_TYPE") == "branch" && env("GITHUB_REF_NAME") == "publish")

    private val versionType: Version.Type = when {
        isTagBuild -> Version.Type.Release
        isPublishBranch -> Version.Type.Snapshot
        else -> Version.Type.Dev
    }

    val VERSION = Version(0, 3, 0, versionType)
}
