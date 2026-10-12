package com.example.service

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubBuildService(
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun newRequestBuilder(url: String): Request.Builder {
        val authHeader = if (token.startsWith("Bearer ") || token.startsWith("token ")) token else "Bearer $token"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", authHeader)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "Android-APK-Builder-App")
    }

    suspend fun getAuthenticatedUser(): String = withContext(Dispatchers.IO) {
        val request = newRequestBuilder("https://api.github.com/user").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                if (response.code == 401) {
                    throw IOException("Token do GitHub inválido ou expirado. Verifique as permissões.")
                }
                throw IOException("Falha ao autenticar no GitHub (${response.code}): $errBody")
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            json.optString("login")
        }
    }

    suspend fun repoExists(owner: String, repo: String): Boolean = withContext(Dispatchers.IO) {
        val request = newRequestBuilder("https://api.github.com/repos/$owner/$repo").get().build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun createRepository(repoName: String, isPrivate: Boolean = true): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("name", repoName)
            put("description", "Repositório criado automaticamente pelo APK Builder")
            put("private", isPrivate)
            put("auto_init", true) // Creates default branch with README
        }
        val request = newRequestBuilder("https://api.github.com/user/repos")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IOException("Falha ao criar repositório $repoName (${response.code}): $err")
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getString("name")
        }
    }

    suspend fun getDefaultBranchSha(owner: String, repo: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val repoReq = newRequestBuilder("https://api.github.com/repos/$owner/$repo").get().build()
        val defaultBranch = client.newCall(repoReq).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Erro ao obter dados do repo: ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            json.optString("default_branch", "main")
        }

        // Get ref for branch
        val refReq = newRequestBuilder("https://api.github.com/repos/$owner/$repo/git/ref/heads/$defaultBranch").get().build()
        val commitSha = client.newCall(refReq).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Erro ao obter branch $defaultBranch: ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            json.getJSONObject("object").getString("sha")
        }

        Pair(defaultBranch, commitSha)
    }

    suspend fun commitFilesToRepo(
        owner: String,
        repo: String,
        branch: String,
        baseCommitSha: String,
        files: Map<String, ByteArray>,
        onProgress: (uploaded: Int, total: Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val treeArray = JSONArray()
        var uploaded = 0
        val total = files.size

        // Batch upload blobs
        for ((path, bytes) in files) {
            val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val blobJson = JSONObject().apply {
                put("content", base64Content)
                put("encoding", "base64")
            }
            val blobReq = newRequestBuilder("https://api.github.com/repos/$owner/$repo/git/blobs")
                .post(blobJson.toString().toRequestBody(jsonMediaType))
                .build()

            val blobSha = client.newCall(blobReq).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    throw IOException("Erro ao enviar arquivo $path (${response.code}): $err")
                }
                val resObj = JSONObject(response.body?.string() ?: "{}")
                resObj.getString("sha")
            }

            val isExecutable = path == "gradlew" || path.endsWith("/gradlew")
            val mode = if (isExecutable) "100755" else "100644"

            treeArray.put(JSONObject().apply {
                put("path", path)
                put("mode", mode)
                put("type", "blob")
                put("sha", blobSha)
            })

            uploaded++
            onProgress(uploaded, total)
        }

        // Create tree
        val treeReqBody = JSONObject().apply {
            put("base_tree", baseCommitSha)
            put("tree", treeArray)
        }
        val treeReq = newRequestBuilder("https://api.github.com/repos/$owner/$repo/git/trees")
            .post(treeReqBody.toString().toRequestBody(jsonMediaType))
            .build()

        val newTreeSha = client.newCall(treeReq).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IOException("Erro ao gerar árvore de arquivos no GitHub (${response.code}): $err")
            }
            JSONObject(response.body?.string() ?: "{}").getString("sha")
        }

        // Create commit
        val commitReqBody = JSONObject().apply {
            put("message", "Compilação automatizada pelo APK Builder")
            put("tree", newTreeSha)
            put("parents", JSONArray().apply { put(baseCommitSha) })
        }
        val commitReq = newRequestBuilder("https://api.github.com/repos/$owner/$repo/git/commits")
            .post(commitReqBody.toString().toRequestBody(jsonMediaType))
            .build()

        val newCommitSha = client.newCall(commitReq).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IOException("Erro ao criar commit (${response.code}): $err")
            }
            JSONObject(response.body?.string() ?: "{}").getString("sha")
        }

        // Update branch ref
        val updateRefBody = JSONObject().apply {
            put("sha", newCommitSha)
            put("force", true)
        }
        val updateRefReq = newRequestBuilder("https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch")
            .patch(updateRefBody.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(updateRefReq).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IOException("Erro ao atualizar branch $branch (${response.code}): $err")
            }
        }

        newCommitSha
    }

    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowFileName: String = "android-build.yml",
        branch: String
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("ref", branch)
        }
        val req = newRequestBuilder("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowFileName/dispatches")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).execute().use { response ->
            response.isSuccessful || response.code == 204
        }
    }

    data class WorkflowRunInfo(
        val id: Long,
        val status: String, // queued, in_progress, completed
        val conclusion: String?, // success, failure, cancelled
        val htmlUrl: String,
        val createdAt: String
    )

    suspend fun getLatestWorkflowRun(owner: String, repo: String): WorkflowRunInfo? = withContext(Dispatchers.IO) {
        val req = newRequestBuilder("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=1").get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string() ?: "{}")
            val runs = json.optJSONArray("workflow_runs") ?: return@withContext null
            if (runs.length() == 0) return@withContext null
            val run = runs.getJSONObject(0)
            WorkflowRunInfo(
                id = run.getLong("id"),
                status = run.optString("status"),
                conclusion = if (run.isNull("conclusion")) null else run.optString("conclusion"),
                htmlUrl = run.optString("html_url"),
                createdAt = run.optString("created_at")
            )
        }
    }

    suspend fun getWorkflowRunDetails(owner: String, repo: String, runId: Long): WorkflowRunInfo = withContext(Dispatchers.IO) {
        val req = newRequestBuilder("https://api.github.com/repos/$owner/$repo/actions/runs/$runId").get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Erro ao obter status da execução ($runId): ${response.code}")
            }
            val run = JSONObject(response.body?.string() ?: "{}")
            WorkflowRunInfo(
                id = run.getLong("id"),
                status = run.optString("status"),
                conclusion = if (run.isNull("conclusion")) null else run.optString("conclusion"),
                htmlUrl = run.optString("html_url"),
                createdAt = run.optString("created_at")
            )
        }
    }

    data class ArtifactInfo(
        val id: Long,
        val name: String,
        val sizeInBytes: Long,
        val downloadUrl: String
    )

    suspend fun getArtifacts(owner: String, repo: String, runId: Long): List<ArtifactInfo> = withContext(Dispatchers.IO) {
        val req = newRequestBuilder("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts").get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val json = JSONObject(response.body?.string() ?: "{}")
            val artifacts = json.optJSONArray("artifacts") ?: return@withContext emptyList()
            val list = mutableListOf<ArtifactInfo>()
            for (i in 0 until artifacts.length()) {
                val item = artifacts.getJSONObject(i)
                list.add(
                    ArtifactInfo(
                        id = item.getLong("id"),
                        name = item.optString("name"),
                        sizeInBytes = item.optLong("size_in_bytes"),
                        downloadUrl = item.optString("archive_download_url")
                    )
                )
            }
            list
        }
    }

    suspend fun downloadArtifactZip(downloadUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val req = newRequestBuilder(downloadUrl).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha no download do artifact: código ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Conteúdo do artifact vazio")
        }
    }

    suspend fun getWorkflowFailureLogs(owner: String, repo: String, runId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val req = newRequestBuilder("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: "{}")
                val jobs = json.optJSONArray("jobs") ?: return@withContext null
                for (i in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(i)
                    if (job.optString("conclusion") == "failure") {
                        val steps = job.optJSONArray("steps")
                        if (steps != null) {
                            for (s in 0 until steps.length()) {
                                val step = steps.getJSONObject(s)
                                if (step.optString("conclusion") == "failure") {
                                    return@withContext "Falha na etapa '${step.optString("name")}' do GitHub Actions."
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Non-critical, ignore
        }
        null
    }
}
