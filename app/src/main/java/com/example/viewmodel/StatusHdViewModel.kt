package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.media.DemoVideoGenerator
import com.example.media.VenlowVideoProcessor
import com.example.model.AspectFitMode
import com.example.model.EncodedOutputVideo
import com.example.model.EncodingConfig
import com.example.model.SharpenLevel
import com.example.model.StatusPreset
import com.example.model.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProcessingPhase {
    IDLE,
    PREPARING,
    ENCODING,
    COMPLETED,
    ERROR
}

data class StatusHdUiState(
    val selectedVideoMetadata: VideoMetadata? = null,
    val config: EncodingConfig = EncodingConfig(),
    val phase: ProcessingPhase = ProcessingPhase.IDLE,
    val statusText: String = "Selecione um vídeo para codificar em Alta Definição (1080×1920 30fps) para o Status.",
    val progressPercent: Int = 0,
    val latestEncodedVideo: EncodedOutputVideo? = null,
    val savedGalleryVideos: List<EncodedOutputVideo> = emptyList(),
    val isGeneratingDemo: Boolean = false,
    val previewProcessedOutput: Boolean = false,
    val showPipelineInspector: Boolean = false,
    val errorMessage: String? = null
)

class StatusHdViewModel(application: Application) : AndroidViewModel(application) {

    private val processor = VenlowVideoProcessor(application.applicationContext)

    private val _uiState = MutableStateFlow(StatusHdUiState())
    val uiState: StateFlow<StatusHdUiState> = _uiState.asStateFlow()

    private var activeEncodingJob: Job? = null

    init {
        refreshGalleryHistory()
    }

    fun refreshGalleryHistory() {
        viewModelScope.launch {
            val history = processor.loadSavedStatusHdVideos()
            _uiState.update { it.copy(savedGalleryVideos = history) }
        }
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    statusText = "Analisando vídeo selecionado...",
                    errorMessage = null,
                    previewProcessedOutput = false
                )
            }
            try {
                val meta = processor.extractMetadata(uri)
                _uiState.update {
                    it.copy(
                        selectedVideoMetadata = meta,
                        phase = ProcessingPhase.IDLE,
                        statusText = "Vídeo pronto (${meta.formattedResolution} • ${meta.formattedSizeMb}). Toque para codificar em HD.",
                        progressPercent = 0
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = ProcessingPhase.ERROR,
                        statusText = "Erro ao ler metadados do vídeo.",
                        errorMessage = e.localizedMessage
                    )
                }
            }
        }
    }

    fun generateAndSelectDemoVideo() {
        if (_uiState.value.isGeneratingDemo || _uiState.value.phase == ProcessingPhase.ENCODING) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGeneratingDemo = true,
                    statusText = "Gerando clipe de teste 1080p em alta resolução...",
                    errorMessage = null
                )
            }
            try {
                val demoUri = DemoVideoGenerator.generateTestClip(getApplication())
                val meta = processor.extractMetadata(demoUri)
                _uiState.update {
                    it.copy(
                        isGeneratingDemo = false,
                        selectedVideoMetadata = meta,
                        phase = ProcessingPhase.IDLE,
                        statusText = "Vídeo de teste 1080p carregado! Pronto para codificar no padrão Venlow.",
                        previewProcessedOutput = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingDemo = false,
                        phase = ProcessingPhase.ERROR,
                        statusText = "Não foi possível gerar o vídeo de demonstração.",
                        errorMessage = e.localizedMessage
                    )
                }
            }
        }
    }

    fun updatePreset(preset: StatusPreset) {
        _uiState.update { state ->
            state.copy(config = state.config.copy(preset = preset))
        }
    }

    fun updateAspectFitMode(mode: AspectFitMode) {
        _uiState.update { state ->
            state.copy(config = state.config.copy(aspectFitMode = mode))
        }
    }

    fun updateSharpenLevel(level: SharpenLevel) {
        _uiState.update { state ->
            state.copy(config = state.config.copy(sharpenLevel = level))
        }
    }

    fun toggleHdrToneMap(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(config = state.config.copy(hdrToSdrToneMap = enabled))
        }
    }

    fun toggleRemoveAudio(remove: Boolean) {
        _uiState.update { state ->
            state.copy(config = state.config.copy(removeAudio = remove))
        }
    }

    fun togglePreviewMode(showOutput: Boolean) {
        _uiState.update { it.copy(previewProcessedOutput = showOutput) }
    }

    fun togglePipelineInspector() {
        _uiState.update { it.copy(showPipelineInspector = !it.showPipelineInspector) }
    }

    fun startVenlowEncoding() {
        val currentMeta = _uiState.value.selectedVideoMetadata ?: return
        if (_uiState.value.phase == ProcessingPhase.ENCODING || _uiState.value.phase == ProcessingPhase.PREPARING) {
            return
        }

        val currentConfig = _uiState.value.config
        activeEncodingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ProcessingPhase.PREPARING,
                    statusText = "Preparando arquivo...",
                    progressPercent = 2,
                    errorMessage = null
                )
            }
            try {
                val encodedResult = processor.processVideoVenlowStyle(
                    videoUri = currentMeta.uri,
                    config = currentConfig,
                    onStatusMessage = { msg ->
                        _uiState.update { state ->
                            val newPhase = if (msg.startsWith("Preparando")) {
                                ProcessingPhase.PREPARING
                            } else {
                                ProcessingPhase.ENCODING
                            }
                            state.copy(phase = newPhase, statusText = msg)
                        }
                    },
                    onProgressPercent = { pct ->
                        _uiState.update { state ->
                            state.copy(progressPercent = pct)
                        }
                    }
                )

                Toast.makeText(
                    getApplication(),
                    "Pronto para postar no WhatsApp!",
                    Toast.LENGTH_LONG
                ).show()

                val updatedHistory = processor.loadSavedStatusHdVideos()
                _uiState.update {
                    it.copy(
                        phase = ProcessingPhase.COMPLETED,
                        statusText = "Concluído! Vídeo salvo na Galeria.",
                        progressPercent = 100,
                        latestEncodedVideo = encodedResult,
                        savedGalleryVideos = updatedHistory,
                        previewProcessedOutput = true
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update {
                    it.copy(
                        phase = ProcessingPhase.IDLE,
                        statusText = "Codificação cancelada.",
                        progressPercent = 0
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = ProcessingPhase.ERROR,
                        statusText = "Erro ao processar vídeo.",
                        errorMessage = e.localizedMessage ?: "Falha no codificador H.264"
                    )
                }
            }
        }
    }

    fun cancelEncoding() {
        activeEncodingJob?.cancel()
        activeEncodingJob = null
    }

    fun shareVideoToWhatsAppOrSystem(context: Context, videoUri: Uri, preferWhatsApp: Boolean = false) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, videoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (preferWhatsApp) {
                    setPackage("com.whatsapp")
                }
            }
            if (preferWhatsApp && shareIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(shareIntent)
            } else {
                val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(fallbackIntent, "Postar Status HD em...")
                )
            }
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Não foi possível abrir o compartilhamento: ${e.localizedMessage}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
