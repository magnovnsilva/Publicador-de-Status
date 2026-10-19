package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.media.UnsharpGlEffect
import com.example.model.AspectFitMode
import com.example.model.EncodedOutputVideo
import com.example.model.EncodingConfig
import com.example.model.SharpenLevel
import com.example.model.VideoMetadata

@OptIn(UnstableApi::class)
@Composable
fun VideoStagePlayerCard(
    selectedVideo: VideoMetadata?,
    latestEncodedVideo: EncodedOutputVideo?,
    previewProcessedOutput: Boolean,
    config: EncodingConfig,
    onTogglePreviewMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeUri: Uri? = if (previewProcessedOutput && latestEncodedVideo != null) {
        latestEncodedVideo.contentUri
    } else {
        selectedVideo?.uri
    }

    var isPlaying by remember { mutableStateOf(true) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
    }

    LaunchedEffect(activeUri, config.sharpenLevel, previewProcessedOutput) {
        if (activeUri != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(activeUri))
            if (!previewProcessedOutput && config.sharpenLevel != SharpenLevel.OFF) {
                try {
                    exoPlayer.setVideoEffects(listOf(UnsharpGlEffect(config.sharpenLevel.unsharpAmount)))
                } catch (_: Exception) {
                }
            } else {
                try {
                    exoPlayer.setVideoEffects(emptyList())
                } catch (_: Exception) {
                }
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = isPlaying
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("video_stage_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with mode switcher if both original and HD output exist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = "Preview 9:16",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = if (previewProcessedOutput && latestEncodedVideo != null) {
                                "Saída Codificada StatusHD"
                            } else {
                                "Monitor Vertical 9:16 (${config.preset.targetWidth}×${config.preset.targetHeight})"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (config.sharpenLevel != SharpenLevel.OFF) {
                                "Unsharp ${config.sharpenLevel.unsharpAmount}x • 30 FPS • H.264 High@4.0"
                            } else {
                                "30 FPS • H.264 High@4.0 • ${config.preset.videoBitrateKbps} kbps"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (latestEncodedVideo != null && selectedVideo != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !previewProcessedOutput,
                            onClick = { onTogglePreviewMode(false) },
                            label = { Text("Original", style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.testTag("preview_original_chip")
                        )
                        FilterChip(
                            selected = previewProcessedOutput,
                            onClick = { onTogglePreviewMode(true) },
                            label = { Text("Status HD", style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("preview_hd_chip")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 9:16 Vertical Frame Preview
            Box(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth(0.68f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                            )
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (activeUri != null) {
                    val resizeMode = when (config.aspectFitMode) {
                        AspectFitMode.PAD_BLACK -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectFitMode.CROP_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }

                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                this.resizeMode = resizeMode
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { playerView ->
                            playerView.player = exoPlayer
                            playerView.resizeMode = if (previewProcessedOutput) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            } else {
                                resizeMode
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Play/Pause Floating Button
                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .testTag("btn_play_pause_preview")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar vídeo" else "Reproduzir vídeo",
                            tint = Color.White
                        )
                    }

                    // Top badge overlay inside 9:16 frame
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (previewProcessedOutput) "HD OTIMIZADO" else "PREVIEW 9:16",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Quadro Vertical\n1080 × 1920",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Padrão Venlow Anti-Compressão",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Video Telemetry Footer
            if (selectedVideo != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TelemetryChip(
                        label = "ENTRADA",
                        value = "${selectedVideo.formattedResolution} • ${selectedVideo.formattedSizeMb}"
                    )
                    TelemetryChip(
                        label = "SAÍDA VENLOW",
                        value = if (latestEncodedVideo != null) {
                            "${latestEncodedVideo.width}×${latestEncodedVideo.height} • ${latestEncodedVideo.formattedSizeMb}"
                        } else {
                            "${config.preset.targetWidth}×${config.preset.targetHeight} • 3.2 Mbps"
                        },
                        highlight = true
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
