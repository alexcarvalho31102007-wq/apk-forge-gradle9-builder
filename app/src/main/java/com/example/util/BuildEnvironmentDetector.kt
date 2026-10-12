package com.example.util

data class ProjectBuildRequirements(
    val agpVersion: String?,
    val recommendedGradleVersion: String,
    val recommendedJavaVersion: String,
    val isAgp9OrAbove: Boolean
)

object BuildEnvironmentDetector {

    /**
     * Inspects project files to detect AGP version, compileSdk, and returns
     * compatible Gradle version and Java version.
     * Does NOT alter the user's source code or dependencies.
     */
    fun detectRequirements(files: Map<String, ByteArray>): ProjectBuildRequirements {
        var detectedAgp: String? = null

        // 1. Check libs.versions.toml
        val tomlFile = files.entries.firstOrNull { it.key.endsWith("libs.versions.toml") }
        if (tomlFile != null) {
            val content = String(tomlFile.value, Charsets.UTF_8)
            val agpMatch = Regex("""agp\s*=\s*["']([^"']+)["']""").find(content)
            if (agpMatch != null) {
                detectedAgp = agpMatch.groupValues[1]
            }
        }

        // 2. Check build.gradle or build.gradle.kts
        if (detectedAgp == null) {
            for ((path, bytes) in files) {
                if (path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")) {
                    val content = String(bytes, Charsets.UTF_8)
                    val pluginMatch = Regex("""com\.android\.(?:application|library)[^'"]*version\s*["']([^"']+)["']""").find(content)
                        ?: Regex("""id\s*\(?\s*['"]com\.android\.(?:application|library)['"]\s*\)?\s*version\s*['"]([^'"]+)['"]""").find(content)
                        ?: Regex("""com\.android\.tools\.build:gradle:([^'"]+)""").find(content)
                    if (pluginMatch != null) {
                        detectedAgp = pluginMatch.groupValues[1]
                        break
                    }
                }
            }
        }

        val agpMajor = detectedAgp?.substringBefore('.')?.toIntOrNull() ?: 0
        val isAgp9OrAbove = agpMajor >= 9 || (detectedAgp != null && detectedAgp.startsWith("9."))

        // Determine recommended Gradle & Java version strictly respecting compatibility
        val (gradleVer, javaVer) = when {
            isAgp9OrAbove -> Pair("9.3.1", "17")
            agpMajor == 8 -> {
                val minor = detectedAgp?.substringAfter('.')?.substringBefore('.')?.toIntOrNull() ?: 0
                if (minor >= 7) Pair("8.11.1", "17") else Pair("8.9", "17")
            }
            agpMajor == 7 -> Pair("7.6.4", "11")
            else -> {
                // Check if existing wrapper declared a version
                val existingWrapper = files.entries.firstOrNull { it.key.endsWith("gradle-wrapper.properties") }
                if (existingWrapper != null) {
                    val content = String(existingWrapper.value, Charsets.UTF_8)
                    val wrapperMatch = Regex("""gradle-([0-9.]+)-""").find(content)
                    val ver = wrapperMatch?.groupValues?.get(1)
                    if (ver != null) Pair(ver, "17") else Pair("9.3.1", "17")
                } else {
                    Pair("9.3.1", "17")
                }
            }
        }

        return ProjectBuildRequirements(
            agpVersion = detectedAgp,
            recommendedGradleVersion = gradleVer,
            recommendedJavaVersion = javaVer,
            isAgp9OrAbove = isAgp9OrAbove
        )
    }
}
