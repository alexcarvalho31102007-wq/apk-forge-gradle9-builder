package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BuildStep
import com.example.model.StepStatus
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen

@Composable
fun StepChecklistCard(
    steps: List<BuildStep>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("step_checklist_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Progresso da Compilação",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            steps.forEachIndexed { index, step ->
                StepItemRow(
                    step = step,
                    isLast = index == steps.lastIndex
                )
            }
        }
    }
}

@Composable
fun StepItemRow(
    step: BuildStep,
    isLast: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "step_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "rotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("step_item_${step.id}"),
        verticalAlignment = Alignment.Top
    ) {
        // Status indicator column with vertical connecting line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (step.status) {
                            StepStatus.SUCCESS -> SuccessGreen
                            StepStatus.FAILED -> ErrorRed
                            StepStatus.IN_PROGRESS -> PrimaryBlue.copy(alpha = 0.15f)
                            StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .border(
                        width = if (step.status == StepStatus.IN_PROGRESS) 2.dp else 1.dp,
                        color = when (step.status) {
                            StepStatus.SUCCESS -> SuccessGreen
                            StepStatus.FAILED -> ErrorRed
                            StepStatus.IN_PROGRESS -> PrimaryBlue
                            StepStatus.PENDING -> MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (step.status) {
                    StepStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Concluído",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    StepStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Falhou",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    StepStatus.IN_PROGRESS -> {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Em andamento",
                            tint = PrimaryBlue,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotation)
                        )
                    }
                    StepStatus.PENDING -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                    }
                }
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(38.dp)
                        .background(
                            if (step.status == StepStatus.SUCCESS) SuccessGreen.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Step text and details
        Column(
            modifier = Modifier
                .padding(top = 3.dp, bottom = if (isLast) 0.dp else 16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (step.status == StepStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Medium
                ),
                color = when (step.status) {
                    StepStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
                    StepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                    StepStatus.FAILED -> ErrorRed
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                }
            )

            AnimatedVisibility(visible = step.description.isNotBlank()) {
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == StepStatus.FAILED) ErrorRed.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
