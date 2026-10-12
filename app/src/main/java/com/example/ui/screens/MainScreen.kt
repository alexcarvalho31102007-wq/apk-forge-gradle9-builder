package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.StepStatus
import com.example.ui.components.BuildActionSection
import com.example.ui.components.BuildHistorySection
import com.example.ui.components.GitHubTokenInputCard
import com.example.ui.components.ProjectSelectionCard
import com.example.ui.components.StepChecklistCard
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.PrimaryBlue
import com.example.util.ApkDownloader
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // If persistable not granted, normal read still works
            }
            viewModel.onZipSelected(it)
        }
    }

    val hasApkReady = uiState.downloadedApkFile != null || uiState.steps.all { it.status == StepStatus.SUCCESS }
    val isAnyStepInProgressOrDone = uiState.steps.any { it.status != StepStatus.PENDING }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PrimaryBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "APK Builder",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Compilação Android via GitHub Actions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))

                // Global error banner if any
                AnimatedVisibility(visible = uiState.globalError != null) {
                    uiState.globalError?.let { err ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .testTag("error_card"),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = ErrorRed.copy(alpha = 0.1f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = ErrorRed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ErrorRed
                                )
                            }
                        }
                    }
                }
            }

            // 1. GitHub Token Card
            item {
                GitHubTokenInputCard(
                    token = uiState.githubToken,
                    isAuthenticated = uiState.isAuthenticated,
                    username = uiState.githubUsername,
                    onTokenChange = { viewModel.onTokenChanged(it) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 2. Project Zip Selection Card
            item {
                ProjectSelectionCard(
                    projectInfo = uiState.projectInfo,
                    isExistingRepoUpdate = uiState.isExistingRepoUpdate,
                    existingRepoOptions = uiState.existingRepoOptions,
                    selectedExistingRepo = uiState.selectedExistingRepo,
                    onSelectZipClick = {
                        zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    },
                    onToggleUpdateExisting = { viewModel.setUpdateExistingRepo(it) },
                    onSelectExistingRepo = { viewModel.selectExistingRepo(it) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 3. Checklist of Real-Time Steps (shown during/after build or once started)
            item {
                AnimatedVisibility(visible = isAnyStepInProgressOrDone || uiState.isBuilding) {
                    Column {
                        StepChecklistCard(steps = uiState.steps)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // 4. Primary Actions (Iniciar Compilação / Baixar APK)
            item {
                BuildActionSection(
                    isBuilding = uiState.isBuilding,
                    canBuild = uiState.projectInfo != null && uiState.githubToken.isNotBlank(),
                    hasApkReady = hasApkReady,
                    onStartBuild = { viewModel.startBuild() },
                    onDownloadOrInstallApk = {
                        val apkFile = uiState.downloadedApkFile
                        if (apkFile != null && apkFile.exists()) {
                            ApkDownloader.promptInstallApk(context, apkFile)
                        } else if (!uiState.artifactDownloadUrl.isNullOrBlank()) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.artifactDownloadUrl))
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "APK não encontrado localmente.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenGitHubUrl = {
                        val url = uiState.workflowRunUrl
                        if (!url.isNullOrBlank()) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    },
                    gitHubUrl = uiState.workflowRunUrl
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 5. Build History Section
            item {
                BuildHistorySection(
                    history = uiState.history,
                    onOpenUrl = { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
