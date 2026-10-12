package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.BuildHistoryItem
import com.example.model.BuildStep
import com.example.model.ProjectInfo
import com.example.model.StepStatus
import com.example.service.GitHubBuildService
import com.example.util.ApkDownloader
import com.example.util.BuildEnvironmentDetector
import com.example.util.SecureStorage
import com.example.util.WorkflowTemplate
import com.example.util.ZipParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BuildUiState(
    val githubToken: String = "",
    val isAuthenticated: Boolean = false,
    val githubUsername: String = "",
    val selectedZipUri: Uri? = null,
    val projectInfo: ProjectInfo? = null,
    val isBuilding: Boolean = false,
    val steps: List<BuildStep> = defaultSteps(),
    val currentRepoName: String = "",
    val workflowRunUrl: String? = null,
    val artifactDownloadUrl: String? = null,
    val downloadedApkFile: File? = null,
    val isDownloadingApk: Boolean = false,
    val globalError: String? = null,
    val history: List<BuildHistoryItem> = emptyList(),
    val isExistingRepoUpdate: Boolean = false,
    val existingRepoOptions: List<String> = emptyList(),
    val selectedExistingRepo: String = ""
)

fun defaultSteps(): List<BuildStep> = listOf(
    BuildStep(
        id = "prep",
        title = "Preparando projeto",
        description = "Analisando estrutura e arquivos do arquivo ZIP..."
    ),
    BuildStep(
        id = "upload",
        title = "Enviando arquivos para o GitHub",
        description = "Criando repositório e sincronizando código-fonte..."
    ),
    BuildStep(
        id = "workflow",
        title = "Configurando ambiente de compilação",
        description = "Configurando pipeline automatizado do GitHub Actions..."
    ),
    BuildStep(
        id = "compile",
        title = "Compilando código-fonte",
        description = "Executando Gradle e montando artefatos na nuvem..."
    ),
    BuildStep(
        id = "generate_apk",
        title = "Gerando APK Release",
        description = "Empacotando e validando arquivo .apk final..."
    ),
    BuildStep(
        id = "ready_download",
        title = "Disponibilizando APK para download",
        description = "Preparando pacote pronto para instalação..."
    )
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val secureStorage = SecureStorage(application)
    private val _uiState = MutableStateFlow(BuildUiState())
    val uiState: StateFlow<BuildUiState> = _uiState.asStateFlow()

    private var parsedFiles: Map<String, ByteArray>? = null
    private var buildJob: Job? = null

    init {
        loadSavedData()
    }

    private fun loadSavedData() {
        val savedToken = secureStorage.getGitHubToken()
        val historyList = loadHistory()
        _uiState.update {
            it.copy(
                githubToken = savedToken,
                history = historyList
            )
        }
        if (savedToken.isNotBlank()) {
            verifyToken(savedToken)
        }
    }

    fun onTokenChanged(token: String) {
        _uiState.update { it.copy(githubToken = token, globalError = null) }
        secureStorage.saveGitHubToken(token)
        if (token.length >= 20) {
            verifyToken(token)
        }
    }

    fun verifyToken(token: String = _uiState.value.githubToken) {
        if (token.isBlank()) return
        viewModelScope.launch {
            try {
                val service = GitHubBuildService(token)
                val username = service.getAuthenticatedUser()
                _uiState.update {
                    it.copy(
                        isAuthenticated = true,
                        githubUsername = username,
                        globalError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAuthenticated = false,
                        githubUsername = "",
                        globalError = "Token GitHub: ${e.message}"
                    )
                }
            }
        }
    }

    fun onZipSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val parsed = ZipParser.parseZip(context, uri)
                parsedFiles = parsed.files

                val existingRepos = _uiState.value.history
                    .map { it.repoName }
                    .distinct()
                    .filter { it.isNotBlank() }

                val autoSelectedExisting = if (existingRepos.contains(parsed.info.sanitizedRepoName)) {
                    parsed.info.sanitizedRepoName
                } else ""

                _uiState.update {
                    it.copy(
                        selectedZipUri = uri,
                        projectInfo = parsed.info,
                        steps = defaultSteps(),
                        downloadedApkFile = null,
                        artifactDownloadUrl = null,
                        workflowRunUrl = null,
                        globalError = null,
                        existingRepoOptions = existingRepos,
                        isExistingRepoUpdate = autoSelectedExisting.isNotBlank(),
                        selectedExistingRepo = autoSelectedExisting
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(globalError = "Erro ao processar arquivo ZIP: ${e.message}")
                }
            }
        }
    }

    fun setUpdateExistingRepo(update: Boolean) {
        _uiState.update { it.copy(isExistingRepoUpdate = update) }
    }

    fun selectExistingRepo(repoName: String) {
        _uiState.update { it.copy(selectedExistingRepo = repoName) }
    }

    fun startBuild() {
        val state = _uiState.value
        val token = state.githubToken.trim()
        val info = state.projectInfo
        val files = parsedFiles

        if (token.isBlank()) {
            _uiState.update { it.copy(globalError = "Por favor, insira o seu GitHub Personal Access Token (PAT).") }
            return
        }
        if (info == null || files == null || files.isEmpty()) {
            _uiState.update { it.copy(globalError = "Por favor, selecione um arquivo ZIP válido do projeto Android.") }
            return
        }

        buildJob?.cancel()
        buildJob = viewModelScope.launch {
            runBuildFlow(this, token, info, files)
        }
    }

    private suspend fun runBuildFlow(
        scope: kotlinx.coroutines.CoroutineScope,
        token: String,
        info: ProjectInfo,
        originalFiles: Map<String, ByteArray>
    ) {
        val service = GitHubBuildService(token)

        _uiState.update {
            it.copy(
                isBuilding = true,
                steps = defaultSteps(),
                globalError = null,
                downloadedApkFile = null,
                artifactDownloadUrl = null,
                workflowRunUrl = null
            )
        }

        try {
            // STEP 1: Preparando projeto
            updateStep("prep", StepStatus.IN_PROGRESS, "Lendo código-fonte e gerando arquivos de compilação...")
            delay(500)

            val filesToUpload = originalFiles.toMutableMap()

            // Detect project requirements (AGP version, compatible Gradle and Java version)
            val reqs = BuildEnvironmentDetector.detectRequirements(filesToUpload)

            // Update or generate every gradle-wrapper.properties in the project to guarantee the required Gradle version
            val gradleWrapperContent = """
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${reqs.recommendedGradleVersion}-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
""".trimIndent().toByteArray(Charsets.UTF_8)

            // Update any existing gradle-wrapper.properties in any subdirectory
            val wrapperKeys = filesToUpload.keys.filter { it.endsWith("gradle-wrapper.properties") }
            for (key in wrapperKeys) {
                filesToUpload[key] = gradleWrapperContent
            }
            // Always ensure root gradle/wrapper/gradle-wrapper.properties is present
            filesToUpload["gradle/wrapper/gradle-wrapper.properties"] = gradleWrapperContent

            // Add automated GitHub Actions workflow to the repository with dynamic versions
            val workflowContent = WorkflowTemplate.getAndroidBuildWorkflowYaml(
                gradleVersion = reqs.recommendedGradleVersion,
                javaVersion = reqs.recommendedJavaVersion
            ).toByteArray(Charsets.UTF_8)
            filesToUpload[".github/workflows/android-build.yml"] = workflowContent

            updateStep(
                "prep",
                StepStatus.SUCCESS,
                "${filesToUpload.size} arquivos preparados (Gradle ${reqs.recommendedGradleVersion}, JDK ${reqs.recommendedJavaVersion})."
            )

            // STEP 2: Enviando arquivos para o GitHub
            updateStep("upload", StepStatus.IN_PROGRESS, "Conectando ao GitHub e preparando repositório...")

            val username = try {
                service.getAuthenticatedUser()
            } catch (e: Exception) {
                failStep("upload", "Falha de autenticação no GitHub: ${e.message}")
                return
            }

            var targetRepo = if (_uiState.value.isExistingRepoUpdate && _uiState.value.selectedExistingRepo.isNotBlank()) {
                _uiState.value.selectedExistingRepo
            } else {
                info.sanitizedRepoName
            }

            // Ensure unique repo if not updating existing
            if (!_uiState.value.isExistingRepoUpdate) {
                var candidate = targetRepo
                var counter = 1
                while (service.repoExists(username, candidate)) {
                    candidate = "${targetRepo}-$counter"
                    counter++
                }
                targetRepo = candidate
                service.createRepository(targetRepo, isPrivate = true)
                delay(1200) // Give GitHub a moment to initialize auto_init branch
            }

            _uiState.update { it.copy(currentRepoName = targetRepo) }
            updateStep("upload", StepStatus.IN_PROGRESS, "Enviando arquivos para $targetRepo...")

            val (branch, baseSha) = service.getDefaultBranchSha(username, targetRepo)

            service.commitFilesToRepo(
                owner = username,
                repo = targetRepo,
                branch = branch,
                baseCommitSha = baseSha,
                files = filesToUpload,
                onProgress = { uploaded, total ->
                    updateStep("upload", StepStatus.IN_PROGRESS, "Enviando: $uploaded de $total arquivos...")
                }
            )

            updateStep("upload", StepStatus.SUCCESS, "Arquivos sincronizados com sucesso no branch '$branch'.")

            // STEP 3: Configurando ambiente de compilação
            updateStep("workflow", StepStatus.IN_PROGRESS, "Disparando GitHub Actions...")
            delay(1000)

            // Trigger workflow dispatch or let push trigger it
            try {
                service.dispatchWorkflow(username, targetRepo, "android-build.yml", branch)
            } catch (e: Exception) {
                // Push might have already triggered it automatically
            }

            updateStep("workflow", StepStatus.SUCCESS, "GitHub Actions configurado e acionado.")

            // STEP 4: Compilando código-fonte
            updateStep("compile", StepStatus.IN_PROGRESS, "Aguardando início dos runners do GitHub...")

            // Poll for run creation
            var runInfo: GitHubBuildService.WorkflowRunInfo? = null
            var waitCount = 0
            while (runInfo == null && waitCount < 15) {
                delay(2000)
                runInfo = service.getLatestWorkflowRun(username, targetRepo)
                waitCount++
            }

            if (runInfo == null) {
                failStep("compile", "Não foi possível detectar o início da execução do workflow no GitHub.")
                return
            }

            val runId = runInfo.id
            _uiState.update { it.copy(workflowRunUrl = runInfo.htmlUrl) }

            // Poll run status until completed
            var isCompleted = false
            var pollAttempts = 0
            val maxAttempts = 120 // ~10 minutes

            while (!isCompleted && pollAttempts < maxAttempts && scope.isActive) {
                delay(5000)
                val currentRun = service.getWorkflowRunDetails(username, targetRepo, runId)

                if (currentRun.status == "in_progress") {
                    updateStep("compile", StepStatus.IN_PROGRESS, "Compilando com Gradle na nuvem (executando)...")
                } else if (currentRun.status == "queued") {
                    updateStep("compile", StepStatus.IN_PROGRESS, "Aguardando runner do GitHub na fila...")
                }

                if (currentRun.status == "completed") {
                    isCompleted = true
                    if (currentRun.conclusion == "success") {
                        updateStep("compile", StepStatus.SUCCESS, "Compilação concluída com sucesso!")
                    } else {
                        val failureDetail = service.getWorkflowFailureLogs(username, targetRepo, runId)
                            ?: "A compilação falhou no GitHub (${currentRun.conclusion})."
                        failStep("compile", failureDetail)
                        addHistory(info.name, targetRepo, runId, StepStatus.FAILED, null, runInfo.htmlUrl)
                        return
                    }
                }
                pollAttempts++
            }

            if (!isCompleted) {
                failStep("compile", "Tempo limite de compilação excedido.")
                return
            }

            // STEP 5: Gerando APK Release
            updateStep("generate_apk", StepStatus.IN_PROGRESS, "Localizando artefato APK gerado...")
            delay(1500)

            val artifacts = service.getArtifacts(username, targetRepo, runId)
            val apkArtifact = artifacts.firstOrNull { it.name.contains("apk", ignoreCase = true) }
                ?: artifacts.firstOrNull()

            if (apkArtifact == null) {
                failStep("generate_apk", "Nenhum arquivo APK foi encontrado nos artefatos da compilação.")
                addHistory(info.name, targetRepo, runId, StepStatus.FAILED, null, runInfo.htmlUrl)
                return
            }

            updateStep("generate_apk", StepStatus.SUCCESS, "APK Release gerado com sucesso (${apkArtifact.name}).")

            // STEP 6: Disponibilizando APK para download
            updateStep("ready_download", StepStatus.IN_PROGRESS, "Baixando e extraindo APK final...")

            val artifactBytes = service.downloadArtifactZip(apkArtifact.downloadUrl)
            val context = getApplication<Application>()
            val savedApk = ApkDownloader.saveArtifactApkToDownloads(context, artifactBytes, info.name)

            if (savedApk != null) {
                updateStep("ready_download", StepStatus.SUCCESS, "APK salvo em Downloads: ${savedApk.name}")
                _uiState.update {
                    it.copy(
                        downloadedApkFile = savedApk,
                        artifactDownloadUrl = apkArtifact.downloadUrl,
                        isBuilding = false
                    )
                }
                addHistory(info.name, targetRepo, runId, StepStatus.SUCCESS, savedApk.absolutePath, runInfo.htmlUrl)
            } else {
                updateStep("ready_download", StepStatus.SUCCESS, "APK disponível no GitHub Actions.")
                _uiState.update {
                    it.copy(
                        artifactDownloadUrl = apkArtifact.downloadUrl,
                        isBuilding = false
                    )
                }
                addHistory(info.name, targetRepo, runId, StepStatus.SUCCESS, null, runInfo.htmlUrl)
            }

        } catch (e: Exception) {
            val err = e.message ?: "Erro desconhecido durante o processo de compilação"
            markCurrentStepFailed(err)
            _uiState.update { it.copy(isBuilding = false, globalError = err) }
        }
    }

    private fun updateStep(stepId: String, status: StepStatus, description: String? = null) {
        _uiState.update { state ->
            val updated = state.steps.map { step ->
                if (step.id == stepId) {
                    step.copy(
                        status = status,
                        description = description ?: step.description,
                        errorMessage = if (status == StepStatus.FAILED) description else null
                    )
                } else step
            }
            state.copy(steps = updated)
        }
    }

    private fun failStep(stepId: String, error: String) {
        updateStep(stepId, StepStatus.FAILED, error)
        _uiState.update { it.copy(isBuilding = false, globalError = error) }
    }

    private fun markCurrentStepFailed(error: String) {
        _uiState.update { state ->
            val updated = state.steps.map { step ->
                if (step.status == StepStatus.IN_PROGRESS) {
                    step.copy(status = StepStatus.FAILED, errorMessage = error)
                } else step
            }
            state.copy(steps = updated)
        }
    }

    private fun addHistory(
        projectName: String,
        repoName: String,
        runId: Long?,
        status: StepStatus,
        apkPath: String?,
        htmlUrl: String?
    ) {
        val item = BuildHistoryItem(
            id = System.currentTimeMillis().toString(),
            projectName = projectName,
            repoName = repoName,
            runId = runId,
            timestamp = System.currentTimeMillis(),
            status = status,
            artifactDownloadUrl = apkPath,
            artifactName = apkPath?.let { File(it).name },
            htmlUrl = htmlUrl
        )
        val current = _uiState.value.history.toMutableList()
        current.add(0, item)
        _uiState.update { it.copy(history = current) }
        saveHistory(current)
    }

    private fun saveHistory(list: List<BuildHistoryItem>) {
        try {
            val array = JSONArray()
            list.take(20).forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("projectName", item.projectName)
                    put("repoName", item.repoName)
                    put("runId", item.runId ?: 0L)
                    put("timestamp", item.timestamp)
                    put("status", item.status.name)
                    put("artifactDownloadUrl", item.artifactDownloadUrl ?: "")
                    put("artifactName", item.artifactName ?: "")
                    put("htmlUrl", item.htmlUrl ?: "")
                }
                array.put(obj)
            }
            secureStorage.saveHistoryJson(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadHistory(): List<BuildHistoryItem> {
        return try {
            val jsonStr = secureStorage.getHistoryJson()
            if (jsonStr.isBlank()) return emptyList()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<BuildHistoryItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BuildHistoryItem(
                        id = obj.getString("id"),
                        projectName = obj.getString("projectName"),
                        repoName = obj.getString("repoName"),
                        runId = obj.optLong("runId"),
                        timestamp = obj.getLong("timestamp"),
                        status = try {
                            StepStatus.valueOf(obj.getString("status"))
                        } catch (e: Exception) {
                            StepStatus.SUCCESS
                        },
                        artifactDownloadUrl = obj.optString("artifactDownloadUrl").ifBlank { null },
                        artifactName = obj.optString("artifactName").ifBlank { null },
                        htmlUrl = obj.optString("htmlUrl").ifBlank { null }
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
