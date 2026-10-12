package com.example.model

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED
}

data class BuildStep(
    val id: String,
    val title: String,
    val description: String = "",
    val status: StepStatus = StepStatus.PENDING,
    val errorMessage: String? = null
)

data class BuildHistoryItem(
    val id: String,
    val projectName: String,
    val repoName: String,
    val runId: Long?,
    val timestamp: Long,
    val status: StepStatus,
    val artifactDownloadUrl: String? = null,
    val artifactName: String? = null,
    val htmlUrl: String? = null
)

data class ProjectInfo(
    val name: String,
    val sanitizedRepoName: String,
    val zipSizeFormatted: String,
    val fileCount: Int,
    val hasGradleWrapper: Boolean,
    val hasSettingsGradle: Boolean,
    val hasBuildGradle: Boolean
)
