package com.breno.audioxtract

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Formatos de saída. */
enum class AudioFormat(
    val label: String,
    val ext: String,
    val fallbackMime: String,
    val lossy: Boolean
) {
    MP3("MP3", "mp3", "audio/mpeg", true),
    M4A("M4A (AAC)", "m4a", "audio/mp4", true),
    OPUS("OPUS", "opus", "audio/ogg", true),
    OGG("OGG", "ogg", "audio/ogg", true),
    WAV("WAV", "wav", "audio/x-wav", false),
    FLAC("FLAC", "flac", "audio/flac", false);

    fun codecArgs(kbps: Int): List<String> = when (this) {
        MP3 -> listOf("-c:a", "libmp3lame", "-b:a", "${kbps}k")
        M4A -> listOf("-c:a", "aac", "-b:a", "${kbps}k")
        OPUS -> listOf("-c:a", "libopus", "-b:a", "${kbps}k")
        OGG -> listOf("-c:a", "libvorbis", "-b:a", "${kbps}k")
        WAV -> listOf("-c:a", "pcm_s16le")
        FLAC -> listOf("-c:a", "flac")
    }
}

class AudioViewModel(app: Application) : AndroidViewModel(app) {
    var uri by mutableStateOf<Uri?>(null)
    var fileName by mutableStateOf<String?>(null)
    var format by mutableStateOf(AudioFormat.MP3)
    var bitrate by mutableIntStateOf(192)
    var progress by mutableFloatStateOf(0f)
    var running by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)

    private var localVideoFile: File? = null

    init {
        try {
            val ver = FFmpegKitConfig.getFFmpegVersion()
            android.util.Log.d("AudioXtract", "FFmpegKit pronto: $ver")
        } catch (t: Throwable) {
            android.util.Log.e("AudioXtract", "Erro ao carregar FFmpegKit", t)
            message = "Erro ao carregar biblioteca de áudio: ${t.localizedMessage}"
            isError = true
        }
    }

    fun pick(u: Uri) {
        uri = u
        message = "Carregando arquivo..."
        isError = false
        progress = 0f
        running = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                } ?: "video_selecionado"

                // Copia o arquivo selecionado imediatamente para o cache local
                val tempFile = File(ctx.cacheDir, "input_video_temp")
                ctx.contentResolver.openInputStream(u)?.use { inputStream ->
                    tempFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    throw IllegalStateException("O arquivo selecionado está vazio.")
                }

                withContext(Dispatchers.Main) {
                    localVideoFile = tempFile
                    fileName = name
                    message = null
                    running = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    localVideoFile = null
                    fileName = null
                    message = "Erro ao carregar vídeo: ${e.localizedMessage ?: e.message}"
                    isError = true
                    running = false
                }
            }
        }
    }

    fun convert() {
        val videoFile = localVideoFile ?: return
        if (!videoFile.exists()) {
            message = "Selecione o vídeo novamente."
            isError = true
            return
        }
        if (running) return

        val ctx = getApplication<Application>()
        running = true
        progress = 0f
        message = null
        isError = false

        viewModelScope.launch(Dispatchers.IO) {
            var tempOutputFile: File? = null

            try {
                val inputPath = videoFile.absolutePath

                // Calcula duração do vídeo
                var totalMs = 0.0
                try {
                    val mediaInfo = FFprobeKit.getMediaInformation(inputPath)
                    totalMs = (mediaInfo.mediaInformation?.duration?.toDoubleOrNull() ?: 0.0) * 1000.0
                } catch (_: Exception) {}

                FFmpegKitConfig.enableStatisticsCallback { st ->
                    if (totalMs > 0) {
                        val currentProgress = (st.time.toDouble() / totalMs).toFloat().coerceIn(0f, 1f)
                        viewModelScope.launch(Dispatchers.Main) {
                            progress = currentProgress
                        }
                    }
                }

                val fmt = format
                val baseName = (fileName ?: "audio").substringBeforeLast('.').ifEmpty { "audio" }
                val outName = "$baseName.${fmt.ext}"
                val tempOut = File(ctx.cacheDir, "out_${System.currentTimeMillis()}.${fmt.ext}")
                tempOutputFile = tempOut

                val args = listOf("-y", "-i", inputPath, "-vn") + fmt.codecArgs(bitrate) + tempOut.absolutePath

                // Executa a extração
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                FFmpegKitConfig.enableStatisticsCallback(null)

                val ok = ReturnCode.isSuccess(session.returnCode)
                if (!ok || !tempOut.exists() || tempOut.length() == 0L) {
                    val failMsg = session.failStackTrace ?: session.output ?: "Formato não suportado."
                    android.util.Log.e("AudioXtract", "FFmpeg falhou: $failMsg")
                    throw IllegalStateException("Não foi possível extrair o áudio. Verifique se o vídeo possui som.")
                }

                // Salva no armazenamento do celular
                val savedPath = saveToPublicStorage(tempOut, outName, fmt)

                withContext(Dispatchers.Main) {
                    progress = 1f
                    message = "Pronto! Salvo em: $savedPath"
                    isError = false
                    running = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.localizedMessage ?: e.message ?: "Erro ao converter vídeo."
                    isError = true
                    running = false
                }
            } finally {
                tempOutputFile?.delete()
                FFmpegKitConfig.enableStatisticsCallback(null)
            }
        }
    }

    private fun saveToPublicStorage(file: File, name: String, fmt: AudioFormat): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fmt.ext) ?: fmt.fallbackMime

        return try {
            saveToMediaStore(
                uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                relativePath = "Download/AudioXtract",
                file = file,
                name = name,
                mime = mime
            )
            "Downloads / AudioXtract / $name"
        } catch (_: Exception) {
            try {
                saveToMediaStore(
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    relativePath = "Music/AudioXtract",
                    file = file,
                    name = name,
                    mime = mime
                )
                "Músicas / AudioXtract / $name"
            } catch (e: Exception) {
                throw IllegalStateException("Não foi possível salvar na memória do celular: ${e.message}")
            }
        }
    }

    private fun saveToMediaStore(
        uri: Uri,
        relativePath: String,
        file: File,
        name: String,
        mime: String
    ) {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = resolver.insert(uri, values)
            ?: throw IllegalStateException("Falha ao criar o arquivo no destino.")

        try {
            resolver.openOutputStream(dest)?.use { outStream ->
                file.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: throw IllegalStateException("Não foi possível gravar o arquivo de áudio.")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(dest, values, null, null)
        } catch (e: Exception) {
            resolver.delete(dest, null, null)
            throw e
        }
    }
}
