package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.EncodedOutputVideo
import com.example.ui.components.EncodingControlsSheet
import com.example.ui.components.VideoStagePlayerCard
import com.example.viewmodel.ProcessingPhase
import com.example.viewmodel.StatusHdUiState
import com.example.viewmodel.StatusHdViewModel

@Composable
fun StatusHdScreen(
    viewModel: StatusHdViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Zero-permission Photo Picker for videos (compliant with Google Play policy)
    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.onVideoSelected(it) }
    }

    // Fallback GetContent("video/*") launcher matching the user's snippet
    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onVideoSelected(it) }
    }

    val launchVideoPicker = {
        try {
            pickVisualMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        } catch (_: Exception) {
            getContentLauncher.launch("video/*")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isExpandedScreen = maxWidth >= 720.dp

            if (isExpandedScreen) {
                // Canonical Two-Pane Studio Layout for Tablets & Foldables
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            StudioHeaderBanner()
                        }
                        item {
                            VideoStagePlayerCard(
                                selectedVideo = uiState.selectedVideoMetadata,
                                latestEncodedVideo = uiState.latestEncodedVideo,
                                previewProcessedOutput = uiState.previewProcessedOutput,
                                config = uiState.config,
                                onTogglePreviewMode = viewModel::togglePreviewMode
                            )
                        }
                        item {
                            ActionAndStatusConsole(
                                uiState = uiState,
                                onSelectVideoClick = launchVideoPicker,
                                onGenerateDemoClick = viewModel::generateAndSelectDemoVideo,
                                onStartEncodingClick = viewModel::startVenlowEncoding,
                                onCancelEncodingClick = viewModel::cancelEncoding,
                                onShareWhatsAppClick = { uri ->
                                    viewModel.shareVideoToWhatsAppOrSystem(context, uri, preferWhatsApp = true)
                                },
                                onShareSystemClick = { uri ->
                                    viewModel.shareVideoToWhatsAppOrSystem(context, uri, preferWhatsApp = false)
                                }
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            EncodingControlsSheet(
                                config = uiState.config,
                                showPipelineInspector = uiState.showPipelineInspector,
                                enabled = uiState.phase != ProcessingPhase.ENCODING && uiState.phase != ProcessingPhase.PREPARING,
                                onSelectPreset = viewModel::updatePreset,
                                onSelectAspectFitMode = viewModel::updateAspectFitMode,
                                onSelectSharpenLevel = viewModel::updateSharpenLevel,
                                onToggleHdrToneMap = viewModel::toggleHdrToneMap,
                                onToggleRemoveAudio = viewModel::toggleRemoveAudio,
                                onTogglePipelineInspector = viewModel::togglePipelineInspector
                            )
                        }
                        item {
                            GalleryHistorySectionHeader(count = uiState.savedGalleryVideos.size)
                        }
                        if (uiState.savedGalleryVideos.isEmpty()) {
                            item {
                                EmptyHistoryCard()
                            }
                        } else {
                            items(uiState.savedGalleryVideos, key = { it.id }) { item ->
                                SavedVideoHistoryCard(
                                    video = item,
                                    onShareWhatsApp = {
                                        viewModel.shareVideoToWhatsAppOrSystem(context, item.contentUri, preferWhatsApp = true)
                                    },
                                    onShareGeneral = {
                                        viewModel.shareVideoToWhatsAppOrSystem(context, item.contentUri, preferWhatsApp = false)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Single-Column Handheld Studio Layout
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        StudioHeaderBanner(
                            modifier = Modifier.widthIn(max = 600.dp)
                        )
                    }

                    item {
                        ActionAndStatusConsole(
                            uiState = uiState,
                            onSelectVideoClick = launchVideoPicker,
                            onGenerateDemoClick = viewModel::generateAndSelectDemoVideo,
                            onStartEncodingClick = viewModel::startVenlowEncoding,
                            onCancelEncodingClick = viewModel::cancelEncoding,
                            onShareWhatsAppClick = { uri ->
                                viewModel.shareVideoToWhatsAppOrSystem(context, uri, preferWhatsApp = true)
                            },
                            onShareSystemClick = { uri ->
                                viewModel.shareVideoToWhatsAppOrSystem(context, uri, preferWhatsApp = false)
                            },
                            modifier = Modifier.widthIn(max = 600.dp)
                        )
                    }

                    item {
                        VideoStagePlayerCard(
                            selectedVideo = uiState.selectedVideoMetadata,
                            latestEncodedVideo = uiState.latestEncodedVideo,
                            previewProcessedOutput = uiState.previewProcessedOutput,
                            config = uiState.config,
                            onTogglePreviewMode = viewModel::togglePreviewMode,
                            modifier = Modifier.widthIn(max = 600.dp)
                        )
                    }

                    item {
                        EncodingControlsSheet(
                            config = uiState.config,
                            showPipelineInspector = uiState.showPipelineInspector,
                            enabled = uiState.phase != ProcessingPhase.ENCODING && uiState.phase != ProcessingPhase.PREPARING,
                            onSelectPreset = viewModel::updatePreset,
                            onSelectAspectFitMode = viewModel::updateAspectFitMode,
                            onSelectSharpenLevel = viewModel::updateSharpenLevel,
                            onToggleHdrToneMap = viewModel::toggleHdrToneMap,
                            onToggleRemoveAudio = viewModel::toggleRemoveAudio,
                            onTogglePipelineInspector = viewModel::togglePipelineInspector,
                            modifier = Modifier.widthIn(max = 600.dp)
                        )
                    }

                    item {
                        GalleryHistorySectionHeader(
                            count = uiState.savedGalleryVideos.size,
                            modifier = Modifier.widthIn(max = 600.dp)
                        )
                    }

                    if (uiState.savedGalleryVideos.isEmpty()) {
                        item {
                            EmptyHistoryCard(
                                modifier = Modifier.widthIn(max = 600.dp)
                            )
                        }
                    } else {
                        items(uiState.savedGalleryVideos, key = { it.id }) { item ->
                            SavedVideoHistoryCard(
                                video = item,
                                onShareWhatsApp = {
                                    viewModel.shareVideoToWhatsAppOrSystem(context, item.contentUri, preferWhatsApp = true)
                                },
                                onShareGeneral = {
                                    viewModel.shareVideoToWhatsAppOrSystem(context, item.contentUri, preferWhatsApp = false)
                                },
                                modifier = Modifier.widthIn(max = 600.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioHeaderBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0A2A1E),
                            Color(0xFF101C2B),
                            Color(0xFF0A2430)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "StatusHD",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "VENLOW ENGINE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Codificador Vertical 1080×1920 • 30fps • H.264 High@4.0",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(
                    text = "Evita a compressão agressiva do WhatsApp Status aplicando escala vertical 9:16 com bordas pretas, filtro de nitidez Unsharp 3×3 e bitrate controlado de 3200 kbps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionAndStatusConsole(
    uiState: StatusHdUiState,
    onSelectVideoClick: () -> Unit,
    onGenerateDemoClick: () -> Unit,
    onStartEncodingClick: () -> Unit,
    onCancelEncodingClick: () -> Unit,
    onShareWhatsAppClick: (Uri) -> Unit,
    onShareSystemClick: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBusy = uiState.phase == ProcessingPhase.ENCODING ||
        uiState.phase == ProcessingPhase.PREPARING ||
        uiState.isGeneratingDemo

    val animatedProgress by animateFloatAsState(
        targetValue = (uiState.progressPercent / 100f).coerceIn(0f, 1f),
        label = "encoding_progress"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("status_console_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        border = BorderStroke(
            1.dp,
            when (uiState.phase) {
                ProcessingPhase.COMPLETED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                ProcessingPhase.ENCODING, ProcessingPhase.PREPARING -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                ProcessingPhase.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                ProcessingPhase.IDLE -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Live Status Text (matches tvStatus from user's prompt)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (uiState.phase) {
                    ProcessingPhase.PREPARING, ProcessingPhase.ENCODING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                    }
                    ProcessingPhase.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Concluído",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    ProcessingPhase.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Erro",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    ProcessingPhase.IDLE -> {
                        Icon(
                            imageVector = Icons.Default.HighQuality,
                            contentDescription = "Status",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.statusText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("tvStatus")
                    )
                    uiState.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (uiState.phase == ProcessingPhase.ENCODING || uiState.phase == ProcessingPhase.PREPARING) {
                    Text(
                        text = "${uiState.progressPercent}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Progress Bar during active transcoding
            AnimatedVisibility(
                visible = uiState.phase == ProcessingPhase.ENCODING || uiState.phase == ProcessingPhase.PREPARING
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .testTag("encoding_progress_bar"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Filtro: scale=1080:1920 + pad + unsharp=3:3:0.5",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "H.264 3200k + AAC 128k",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Primary Selection Buttons (matches btnSelect from user's prompt)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSelectVideoClick,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("btnSelect"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.selectedVideoMetadata == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (uiState.selectedVideoMetadata == null) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.selectedVideoMetadata == null) {
                            "Selecionar Vídeo"
                        } else {
                            "Trocar Vídeo"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = onGenerateDemoClick,
                    enabled = !isBusy,
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("btn_generate_demo"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
                ) {
                    if (uiState.isGeneratingDemo) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MovieCreation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Clipe Teste",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Encode / Cancel CTA when a video is loaded
            if (uiState.selectedVideoMetadata != null) {
                if (uiState.phase == ProcessingPhase.ENCODING || uiState.phase == ProcessingPhase.PREPARING) {
                    OutlinedButton(
                        onClick = onCancelEncodingClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("btn_cancel_encoding"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cancelar Processamento",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    Button(
                        onClick = onStartEncodingClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("btn_start_encoding"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Codificar em Alta Definição (Status HD)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Post-encoding Quick Share to WhatsApp / System sheet
            AnimatedVisibility(visible = uiState.latestEncodedVideo != null && uiState.phase == ProcessingPhase.COMPLETED) {
                val latest = uiState.latestEncodedVideo
                if (latest != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Salvo em Filmes/StatusHD",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${latest.displayName} (${latest.formattedSizeMb})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onShareWhatsAppClick(latest.contentUri) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("btn_share_whatsapp"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Postar no WhatsApp",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { onShareSystemClick(latest.contentUri) },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("btn_share_other"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Compartilhar em outros apps",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryHistorySectionHeader(
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderSpecial,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Galeria Filmes/StatusHD",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "$count vídeos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyHistoryCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("empty_history_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircleFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "Nenhum vídeo StatusHD exportado ainda",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Selecione um vídeo da galeria ou gere o Clipe Teste 1080p para criar seu primeiro vídeo otimizado em Filmes/StatusHD.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SavedVideoHistoryCard(
    video: EncodedOutputVideo,
    onShareWhatsApp: () -> Unit,
    onShareGeneral: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("history_item_${video.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "9:16\nHD",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Column {
                    Text(
                        text = video.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${video.width}×${video.height} • ${video.formattedSizeMb} • ${video.formattedDuration}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onShareWhatsApp,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("btn_history_whatsapp_${video.id}")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Enviar para o WhatsApp",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onShareGeneral,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("btn_history_share_${video.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Compartilhar vídeo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
