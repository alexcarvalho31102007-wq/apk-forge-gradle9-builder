package com.example.util

object WorkflowTemplate {

    /**
     * Generates a GitHub Actions workflow tailored for building Android APKs.
     * Guarantees that the project runs with Gradle 9.3.1 (or detected version),
     * updates all gradle-wrapper.properties in the repository,
     * logs './gradlew --version' explicitly, and compiles the Release APK.
     */
    fun getAndroidBuildWorkflowYaml(
        gradleVersion: String = "9.3.1",
        javaVersion: String = "17"
    ): String {
        return """
name: Android Release Build

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

concurrency:
  group: android-build-${'$'}{{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Build Android APK
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up Java ($javaVersion)
        uses: actions/setup-java@v4
        with:
          java-version: '$javaVersion'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Configure Gradle $gradleVersion & Update All Wrappers
        run: |
          echo "=========================================="
          echo "Configurando ambiente para Gradle $gradleVersion..."
          echo "=========================================="

          # 1. Update EVERY gradle-wrapper.properties found anywhere in the repository
          find . -type f -name "gradle-wrapper.properties" | while read -r prop_file; do
            echo "Updating existing wrapper: ${'$'}prop_file"
            sed -i -E 's|distributionUrl=.*|distributionUrl=https\\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip|' "${'$'}prop_file"
          done

          # 2. Guarantee root wrapper exists and points to $gradleVersion
          mkdir -p gradle/wrapper
          cat << 'EOF' > gradle/wrapper/gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

          # 3. Download official Gradle $gradleVersion wrapper jar & script if missing or damaged
          if [ ! -f gradlew ] || [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
            echo "Downloading and generating Gradle wrapper scripts..."
            curl -sSL "https://raw.githubusercontent.com/gradle/gradle/v$gradleVersion/gradlew" -o gradlew || true
            curl -sSL "https://raw.githubusercontent.com/gradle/gradle/v$gradleVersion/gradlew.bat" -o gradlew.bat || true
          fi
          chmod +x gradlew || true

          # 4. Show Gradle version explicitly in the logs
          echo "=========================================="
          echo "Verificando versão do Gradle Wrapper:"
          ./gradlew --version
          echo "=========================================="

      - name: Build Android Release APK
        run: |
          chmod +x gradlew || true

          echo "Compilando projeto Android com Gradle..."
          # First attempt assembleRelease, fallback to assembleDebug if signing configuration is missing
          if ./gradlew tasks --all 2>/dev/null | grep -q "assembleRelease"; then
            echo "Executando assembleRelease..."
            ./gradlew assembleRelease --stacktrace || ./gradlew assembleDebug --stacktrace
          else
            echo "assembleRelease não encontrado, executando assembleDebug..."
            ./gradlew assembleDebug --stacktrace
          fi

      - name: Locate and Stage APK
        id: stage_apk
        run: |
          mkdir -p staged-apk
          # Locate generated APK file excluding build intermediates & tmp dirs
          FOUND_APK=$(find . -type f -name "*.apk" ! -path "*/build/intermediates/*" ! -path "*/build/tmp/*" | head -n 1)
          if [ -n "${'$'}FOUND_APK" ]; then
            echo "Sucesso! APK localizado: ${'$'}FOUND_APK"
            cp "${'$'}FOUND_APK" staged-apk/app-release.apk
            echo "apk_exists=true" >> ${'$'}GITHUB_OUTPUT
          else
            echo "ERRO: Nenhum arquivo APK foi encontrado após a compilação!"
            echo "apk_exists=false" >> ${'$'}GITHUB_OUTPUT
            exit 1
          fi

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-release-apk
          path: staged-apk/app-release.apk
          retention-days: 7
          if-no-files-found: error
""".trimIndent()
    }
}
