package com.example.viewmodel

import android.app.Application
import android.content.ClipData
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
    val autoShareAfterEncoding: Boolean = false,
    val errorMessage: String? = null
)

class StatusHdViewModel(application: Application) : AndroidViewModel(application) {

    private val processor = VenlowVideoProcessor(application.applicationContext)

    private val _uiState = MutableStateFlow(StatusHdUiState())
    val uiState: StateFlow<StatusHdUiState> = _uiState.asStateFlow()

    private var activeEncodingJob: Job? = null
    private var pendingSaveAsVideo: EncodedOutputVideo? = null

    init {
        refreshGalleryHistory()
    }

    fun refreshGalleryHistory() {
        viewModelScope.launch {
            val history = processor.loadSavedStatusHdVideos()
            _uiState.update { state ->
                state.copy(
                    savedGalleryVideos = history,
                    latestEncodedVideo = state.latestEncodedVideo ?: history.firstOrNull()
                )
            }
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

    fun startVenlowEncoding(
        context: Context? = null,
        shareDirectlyAfterEncoding: Boolean = false
    ) {
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
                    autoShareAfterEncoding = shareDirectlyAfterEncoding,
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

                val updatedHistory = processor.loadSavedStatusHdVideos()
                _uiState.update {
                    it.copy(
                        phase = ProcessingPhase.COMPLETED,
                        statusText = "Concluído! Vídeo salvo na Galeria e pronto para o Status.",
                        progressPercent = 100,
                        latestEncodedVideo = encodedResult,
                        savedGalleryVideos = updatedHistory,
                        previewProcessedOutput = true
                    )
                }

                Toast.makeText(
                    getApplication(),
                    "Pronto para postar no WhatsApp Status!",
                    Toast.LENGTH_LONG
                ).show()

                if (shareDirectlyAfterEncoding && context != null) {
                    shareEncodedVideoToStatus(context, encodedResult, preferWhatsApp = true)
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

    /**
     * Compartilha o vídeo convertido diretamente para o WhatsApp Status (ou WhatsApp Business / Seletor do Android)
     * utilizando um FileProvider URI autenticado com ClipData e FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun shareEncodedVideoToStatus(
        context: Context,
        video: EncodedOutputVideo,
        preferWhatsApp: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                val shareableUri = processor.prepareShareableFileProviderUri(video)
                val shareClipData = ClipData.newRawUri(video.displayName, shareableUri)

                if (preferWhatsApp) {
                    val whatsAppPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
                    var launchedWhatsApp = false

                    for (pkg in whatsAppPackages) {
                        val waIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            setPackage(pkg)
                            putExtra(Intent.EXTRA_STREAM, shareableUri)
                            clipData = shareClipData
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.grantUriPermission(
                                pkg,
                                shareableUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }

                        if (waIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(waIntent)
                            launchedWhatsApp = true
                            break
                        }
                    }

                    if (launchedWhatsApp) return@launch
                }

                // Se o WhatsApp não estiver instalado (ex: emulador) ou o usuário escolheu compartilhar em outros apps:
                val chooserTarget = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, shareableUri)
                    clipData = shareClipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(chooserTarget, "Postar Status HD em...").apply {
                    clipData = shareClipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Erro ao compartilhar vídeo: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Faz o download/salvamento direto na pasta pública "Downloads/StatusHD" do aparelho.
     */
    fun downloadVideoToPublicDownloads(context: Context, video: EncodedOutputVideo) {
        viewModelScope.launch {
            val savedUri = processor.saveCopyToPublicDownloads(video)
            if (savedUri != null) {
                Toast.makeText(
                    context,
                    "Download concluído! Salvo em Downloads/StatusHD/${video.displayName}",
                    Toast.LENGTH_LONG
                ).show()
                _uiState.update {
                    it.copy(statusText = "Arquivo salvo em Downloads/StatusHD/${video.displayName}")
                }
            } else {
                Toast.makeText(
                    context,
                    "Não foi possível salvar na pasta Downloads.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun prepareSaveAsDocument(video: EncodedOutputVideo, launchCreateDocument: (String) -> Unit) {
        pendingSaveAsVideo = video
        val defaultName = video.displayName.ifBlank { "StatusHD_${System.currentTimeMillis()}.mp4" }
        launchCreateDocument(defaultName)
    }

    fun onSaveAsDocumentDestinationPicked(context: Context, destinationUri: Uri?) {
        val video = pendingSaveAsVideo ?: _uiState.value.latestEncodedVideo ?: return
        pendingSaveAsVideo = null
        if (destinationUri == null) return

        viewModelScope.launch {
            val ok = processor.copyVideoToDestinationUri(video, destinationUri)
            if (ok) {
                Toast.makeText(
                    context,
                    "Vídeo salvo com sucesso no local escolhido!",
                    Toast.LENGTH_LONG
                ).show()
                _uiState.update {
                    it.copy(statusText = "Vídeo baixado e salvo com sucesso: ${video.displayName}")
                }
            } else {
                Toast.makeText(
                    context,
                    "Erro ao salvar o arquivo no destino selecionado.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
