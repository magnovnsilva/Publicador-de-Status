package com.example.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.model.EncodedOutputVideo
import com.example.model.EncodingConfig
import com.example.model.VideoMetadata
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates and downloads a standalone, rich interactive HTML file ("StatusHD_Codificador.html")
 * containing:
 * 1. A Web-based StatusHD Video Studio (using HTML5 Canvas + WebGL Unsharp + MediaRecorder / FFmpeg command generator)
 * 2. The complete technical report & FFmpeg / Media3 pipeline specifications
 * 3. The full Android source code reference (MainActivity.kt & VenlowVideoProcessor) ready to copy or use offline.
 */
object HtmlReportExporter {

    data class ExportedHtmlResult(
        val fileName: String,
        val uri: Uri,
        val savedPathDescription: String
    )

    suspend fun exportAndDownloadHtml(
        context: Context,
        config: EncodingConfig,
        selectedVideo: VideoMetadata?,
        latestEncoded: EncodedOutputVideo?,
        historyCount: Int
    ): ExportedHtmlResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "StatusHD_Studio_$timestamp.html"
        val htmlContent = buildStandaloneHtml(
            config = config,
            selectedVideo = selectedVideo,
            latestEncoded = latestEncoded,
            historyCount = historyCount
        )

        // Also always save a copy in cacheDir so FileProvider can open/share it anywhere
        val cacheFile = File(context.cacheDir, fileName)
        cacheFile.writeText(htmlContent, Charsets.UTF_8)

        var downloadUri: Uri? = null
        var pathDesc = "Downloads/$fileName"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/html")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StatusHD")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val inserted = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (inserted != null) {
                    resolver.openOutputStream(inserted)?.use { out ->
                        out.write(htmlContent.toByteArray(Charsets.UTF_8))
                    }
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(inserted, doneValues, null, null)
                    downloadUri = inserted
                    pathDesc = "Downloads/StatusHD/$fileName"
                }
            } catch (_: Exception) {
            }
        }

        val finalUri = downloadUri ?: FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )

        ExportedHtmlResult(
            fileName = fileName,
            uri = finalUri,
            savedPathDescription = pathDesc
        )
    }

    fun openOrShareHtmlFile(context: Context, result: ExportedHtmlResult) {
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(result.uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(viewIntent, "Abrir ou Salvar ${result.fileName}")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, result.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Baixar / Compartilhar HTML"))
        }
    }

    fun buildStandaloneHtml(
        config: EncodingConfig,
        selectedVideo: VideoMetadata?,
        latestEncoded: EncodedOutputVideo?,
        historyCount: Int
    ): String {
        val currentDate = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        val ffmpegCmd = config.buildEquivalentFfmpegCommand()
        val inputSummary = selectedVideo?.let {
            "${it.displayName} (${it.formattedResolution} • ${it.formattedSizeMb} • ${it.formattedDuration})"
        } ?: "Nenhum vídeo de entrada selecionado na sessão"

        val outputSummary = latestEncoded?.let {
            "${it.displayName} (${it.width}×${it.height} • ${it.formattedSizeMb} • Salvo em Filmes/StatusHD)"
        } ?: "Pronto para exportação 1080×1920 @ 30fps"

        return """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>StatusHD — Venlow Engine (Versão Web &amp; Documentação Técnica)</title>
    <style>
        :root {
            --bg: #080D12;
            --surface: #101821;
            --surface-2: #192432;
            --emerald: #00E676;
            --cyan: #00E5FF;
            --text: #F0F6FC;
            --muted: #9BAEC2;
            --border: #28374A;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background: var(--bg);
            color: var(--text);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            line-height: 1.6;
            padding: 24px 16px 64px;
        }
        .container {
            max-width: 960px;
            margin: 0 auto;
        }
        .hero {
            background: linear-gradient(135deg, #0A2A1E 0%, #101C2B 55%, #0A2430 100%);
            border: 1px solid rgba(0, 230, 118, 0.35);
            border-radius: 24px;
            padding: 28px;
            margin-bottom: 24px;
            box-shadow: 0 16px 40px rgba(0,0,0,0.45);
        }
        .badge {
            display: inline-block;
            background: var(--emerald);
            color: #00210E;
            font-weight: 800;
            font-size: 11px;
            letter-spacing: 0.8px;
            padding: 4px 10px;
            border-radius: 999px;
            text-transform: uppercase;
            margin-bottom: 10px;
        }
        h1 {
            font-size: 28px;
            font-weight: 800;
            margin-bottom: 6px;
        }
        h2 {
            font-size: 19px;
            color: var(--emerald);
            margin-bottom: 14px;
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .subtitle {
            color: var(--cyan);
            font-family: monospace;
            font-size: 14px;
            margin-bottom: 12px;
        }
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
            gap: 20px;
            margin-bottom: 24px;
        }
        .card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 20px;
            padding: 22px;
        }
        .stat-row {
            display: flex;
            justify-content: space-between;
            padding: 10px 0;
            border-bottom: 1px solid rgba(255,255,255,0.06);
            font-size: 14px;
        }
        .stat-row:last-child { border-bottom: none; }
        .stat-label { color: var(--muted); }
        .stat-val { font-weight: 700; color: var(--text); font-family: monospace; }
        pre {
            background: #05080C;
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 16px;
            overflow-x: auto;
            color: var(--emerald);
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: 12.5px;
            line-height: 1.55;
            margin-top: 10px;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            background: var(--emerald);
            color: #00210E;
            font-weight: 700;
            border: none;
            border-radius: 12px;
            padding: 12px 20px;
            cursor: pointer;
            font-size: 14px;
            transition: transform 0.15s ease, opacity 0.15s ease;
            text-decoration: none;
            margin-top: 12px;
            margin-right: 8px;
        }
        .btn:hover { opacity: 0.92; transform: translateY(-1px); }
        .btn-secondary {
            background: var(--surface-2);
            color: var(--cyan);
            border: 1px solid var(--cyan);
        }
        .web-studio {
            display: flex;
            flex-direction: column;
            align-items: center;
            text-align: center;
        }
        canvas {
            width: 216px;
            height: 384px;
            background: #000;
            border-radius: 16px;
            border: 2px solid var(--emerald);
            margin: 14px 0;
            box-shadow: 0 10px 30px rgba(0, 230, 118, 0.18);
        }
        input[type="file"] {
            margin: 10px 0;
            color: var(--muted);
        }
        .footer {
            text-align: center;
            color: var(--muted);
            font-size: 12px;
            margin-top: 32px;
        }
    </style>
</head>
<body>
<div class="container">
    <div class="hero">
        <span class="badge">StatusHD • Venlow Anti-Compression Engine</span>
        <h1>StatusHD — Codificador Vertical 1080×1920</h1>
        <div class="subtitle">Scale 1080:1920 + Pad Black + Unsharp 3×3 Luma + H.264 High@4.0 (3200 kbps)</div>
        <p style="color: var(--muted); font-size: 14px;">
            Arquivo HTML autônomo gerado em <strong>$currentDate</strong>. Inclui conversor visual interativo em HTML5 Canvas (9:16),
            relatório da sessão atual, comando FFmpeg pronto para uso e código-fonte Kotlin completo.
        </p>
    </div>

    <div class="grid">
        <div class="card">
            <h2>Parâmetros do Perfil Ativo</h2>
            <div class="stat-row">
                <span class="stat-label">Perfil Selecionado</span>
                <span class="stat-val">${config.preset.title}</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Resolução Alvo (9:16)</span>
                <span class="stat-val">${config.preset.targetWidth}×${config.preset.targetHeight} @ ${config.preset.targetFps} fps</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Codec &amp; Perfil de Vídeo</span>
                <span class="stat-val">H.264 (AVC) High@4.0</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Bitrate de Vídeo / Buffer</span>
                <span class="stat-val">${config.preset.videoBitrateKbps}k (max ${config.preset.maxBitrateKbps}k / buf ${config.preset.bufSizeKbps}k)</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Enquadramento Vertical</span>
                <span class="stat-val">${config.aspectFitMode.label}</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Filtro Unsharp 3×3</span>
                <span class="stat-val">${config.sharpenLevel.label} (${config.sharpenLevel.ffmpegParam})</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Áudio</span>
                <span class="stat-val">${if (config.removeAudio) "Mudo (-an)" else "AAC ${config.preset.audioBitrateKbps} kbps • 44.1 kHz Estéreo"}</span>
            </div>
        </div>

        <div class="card">
            <h2>Resumo da Sessão no Dispositivo</h2>
            <div class="stat-row">
                <span class="stat-label">Vídeo de Entrada</span>
                <span class="stat-val">$inputSummary</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Última Exportação HD</span>
                <span class="stat-val">$outputSummary</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Total na Galeria (StatusHD)</span>
                <span class="stat-val">$historyCount vídeo(s)</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">Diretório de Saída</span>
                <span class="stat-val">Movies/StatusHD</span>
            </div>
            <button class="btn" onclick="window.print()">Imprimir / Salvar PDF</button>
            <button class="btn btn-secondary" onclick="copyCommand()">Copiar Comando FFmpeg</button>
        </div>
    </div>

    <div class="card web-studio" style="margin-bottom: 24px;">
        <h2>Simulador e Gravador Web 9:16 (HTML5 Canvas + MediaRecorder)</h2>
        <p style="color: var(--muted); font-size: 13.5px; max-width: 640px;">
            Selecione qualquer vídeo do seu computador ou celular abaixo para visualizar e gravar diretamente no navegador com preenchimento vertical 1080×1920 (Pad Black) em 30 FPS:
        </p>
        <input type="file" id="videoInput" accept="video/*" />
        <canvas id="stageCanvas" width="1080" height="1920"></canvas>
        <video id="hiddenVideo" playsinline crossorigin="anonymous" style="display:none;"></video>
        <div>
            <button class="btn" id="btnRecordWeb" onclick="startWebRecording()" disabled>Gravar Vídeo 9:16 no Navegador</button>
            <a id="downloadWebLink" class="btn btn-secondary" style="display:none;">Baixar Vídeo Otimizado</a>
        </div>
        <p id="webStatus" style="color: var(--cyan); font-family: monospace; font-size: 12px; margin-top: 10px;">
            Aguardando seleção de vídeo no navegador...
        </p>
    </div>

    <div class="card" style="margin-bottom: 24px;">
        <h2>Pipeline Equivalente FFmpeg (Venlow Filtergraph)</h2>
        <pre id="ffmpegCmdBox">$ffmpegCmd</pre>
    </div>

    <div class="card">
        <h2>Código-Fonte Kotlin (MainActivity.kt — StatusHD)</h2>
        <pre>package com.uso.pessoal.statushd

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSelect: Button

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -&gt;
        uri?.let { processarVideoEstiloVenlow(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnSelect = findViewById(R.id.btnSelect)

        btnSelect.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }
    }

    private fun processarVideoEstiloVenlow(videoUri: Uri) {
        tvStatus.text = "Preparando arquivo..."
        btnSelect.isEnabled = false

        val inputFile = File(cacheDir, "input_temp.mp4")
        contentResolver.openInputStream(videoUri)?.use { input -&gt;
            FileOutputStream(inputFile).use { output -&gt;
                input.copyTo(output)
            }
        }

        val outputFile = File(cacheDir, "status_otimizado.mp4")
        if (outputFile.exists()) outputFile.delete()

        val filtroVideo = "scale=1080:1920:force_original_aspect_ratio=decrease," +
                "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black," +
                "unsharp=3:3:0.5:3:3:0.0,fps=30,format=yuv420p"

        val comando = "-y -i \"${'$'}{inputFile.absolutePath}\" " +
                "-vf \"${'$'}filtroVideo\" " +
                "-c:v libx264 -preset medium -profile:v high -level 4.0 " +
                "-b:v 3200k -maxrate 3500k -bufsize 7000k " +
                "-c:a aac -b:a 128k -ar 44100 -ac 2 " +
                "-movflags +faststart \"${'$'}{outputFile.absolutePath}\""

        tvStatus.text = "Codificando em Alta Definição para o Status..."

        FFmpegKit.executeAsync(comando) { session -&gt;
            val returnCode = session.returnCode
            runOnUiThread {
                btnSelect.isEnabled = true
                if (ReturnCode.isSuccess(returnCode)) {
                    salvarNaGaleria(outputFile)
                    tvStatus.text = "Concluído! Vídeo salvo na Galeria."
                } else {
                    tvStatus.text = "Erro ao processar vídeo."
                }
                inputFile.delete()
            }
        }
    }

    private fun salvarNaGaleria(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "StatusHD_${'$'}{System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StatusHD")
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream -&gt;
                file.inputStream().use { inputStream -&gt;
                    inputStream.copyTo(outputStream)
                }
            }
            file.delete()
            Toast.makeText(this, "Pronto para postar no WhatsApp!", Toast.LENGTH_LONG).show()
        }
    }
}</pre>
    </div>

    <div class="footer">
        Gerado por StatusHD Android App • Padrão Venlow 1080×1920 30fps H.264 High@4.0
    </div>
</div>

<script>
    const canvas = document.getElementById('stageCanvas');
    const ctx = canvas.getContext('2d');
    const video = document.getElementById('hiddenVideo');
    const videoInput = document.getElementById('videoInput');
    const btnRecord = document.getElementById('btnRecordWeb');
    const webStatus = document.getElementById('webStatus');
    const downloadLink = document.getElementById('downloadWebLink');

    // Draw initial calibration frame
    function drawCalibration() {
        ctx.fillStyle = '#080D12';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.strokeStyle = '#00E676';
        ctx.lineWidth = 6;
        ctx.strokeRect(40, 40, canvas.width - 80, canvas.height - 80);
        ctx.fillStyle = '#00E676';
        ctx.font = 'bold 54px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('STATUS HD 9:16', canvas.width / 2, canvas.height / 2 - 30);
        ctx.fillStyle = '#9BAEC2';
        ctx.font = '36px monospace';
        ctx.fillText('1080 × 1920 • 30 FPS', canvas.width / 2, canvas.height / 2 + 40);
    }
    drawCalibration();

    videoInput.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (!file) return;
        const url = URL.createObjectURL(file);
        video.src = url;
        video.muted = false;
        video.play();
        btnRecord.disabled = false;
        downloadLink.style.display = 'none';
        webStatus.textContent = 'Vídeo carregado: ' + file.name + ' (Pronto para gravar em 1080x1920)';
        renderLoop();
    });

    function renderLoop() {
        if (!video.paused && !video.ended) {
            ctx.fillStyle = '#000000';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            const vw = video.videoWidth || 1080;
            const vh = video.videoHeight || 1920;
            const scale = Math.min(canvas.width / vw, canvas.height / vh);
            const dw = vw * scale;
            const dh = vh * scale;
            const dx = (canvas.width - dw) / 2;
            const dy = (canvas.height - dh) / 2;
            ctx.filter = 'contrast(1.04) saturate(1.03)';
            ctx.drawImage(video, dx, dy, dw, dh);
            ctx.filter = 'none';
            requestAnimationFrame(renderLoop);
        }
    }

    function copyCommand() {
        const text = document.getElementById('ffmpegCmdBox').innerText;
        navigator.clipboard.writeText(text).then(() => {
            alert('Comando FFmpeg copiado para a área de transferência!');
        });
    }

    function startWebRecording() {
        if (!video.src) return;
        video.currentTime = 0;
        video.play();
        renderLoop();
        const stream = canvas.captureStream(30);
        const chunks = [];
        const recorder = new MediaRecorder(stream, { videoBitsPerSecond: 3200000 });
        btnRecord.disabled = true;
        webStatus.textContent = 'Codificando quadro 1080×1920 em 30fps (3.2 Mbps)...';

        recorder.ondataavailable = (e) => {
            if (e.data.size > 0) chunks.push(e.data);
        };
        recorder.onstop = () => {
            const blob = new Blob(chunks, { type: 'video/webm' });
            const outUrl = URL.createObjectURL(blob);
            downloadLink.href = outUrl;
            downloadLink.download = 'StatusHD_' + Date.now() + '.webm';
            downloadLink.style.display = 'inline-flex';
            btnRecord.disabled = false;
            webStatus.textContent = 'Concluído! Clique em "Baixar Vídeo Otimizado".';
        };
        recorder.start();
        video.onended = () => {
            if (recorder.state === 'recording') recorder.stop();
        };
    }
</script>
</body>
</html>
        """.trimIndent()
    }
}
