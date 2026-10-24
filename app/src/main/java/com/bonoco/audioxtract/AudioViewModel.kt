package com.bonoco.audioxtract

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
import com.whispercpp.whisper.WhisperContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private class Cue(var start: Double, var end: Double, var text: String)

    fun transcribe() {
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
        message = "Preparando áudio..."
        isError = false

        viewModelScope.launch(Dispatchers.IO) {
            val pcm = File(ctx.cacheDir, "whisper_input.pcm")
            var whisper: WhisperContext? = null
            try {
                pcm.delete()
                val session = FFmpegKit.executeWithArguments(
                    arrayOf("-y", "-i", videoFile.absolutePath, "-vn",
                        "-ac", "1", "-ar", "16000", "-f", "s16le", pcm.absolutePath)
                )
                if (!ReturnCode.isSuccess(session.returnCode) || !pcm.exists() || pcm.length() == 0L) {
                    throw IllegalStateException("Não foi possível extrair o áudio. Verifique se o vídeo possui som.")
                }

                val shorts = ByteBuffer.wrap(pcm.readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
                pcm.delete()

                withContext(Dispatchers.Main) {
                    progress = 0.05f
                    message = "Transcrevendo... pode levar alguns minutos"
                }
                val w = WhisperContext.createContextFromAsset(ctx.assets, "models/ggml-base.bin")
                whisper = w

                // Blocos de 30 s: o progresso sobe a cada bloco (5% -> 98%)
                val allWords = mutableListOf<Triple<Double, Double, String>>()
                val chunk = 30 * 16000
                var start = 0
                while (start < samples.size) {
                    var end = minOf(start + chunk, samples.size)
                    if (end < samples.size) end = quietPoint(samples, end - 3 * 16000, end)
                    val raw = w.transcribeData(samples.copyOfRange(start, end), printTimestamp = true)
                    allWords += parseWords(raw, start / 16000.0)
                    start = end
                    val p = 0.05f + 0.93f * (start.toFloat() / samples.size)
                    withContext(Dispatchers.Main) { progress = p }
                }

                val srt = toSrt(allWords)
                if (srt.isBlank()) throw IllegalStateException("O Whisper não retornou texto.")

                val baseName = (fileName ?: "legenda").substringBeforeLast('.').ifEmpty { "legenda" }
                val tempSrt = File(ctx.cacheDir, "legenda_${System.currentTimeMillis()}.srt")
                tempSrt.writeText(srt)
                saveToMediaStore(
                    uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    relativePath = "Download/AudioXtract",
                    file = tempSrt,
                    name = "$baseName.srt",
                    mime = "application/x-subrip"
                )
                tempSrt.delete()

                withContext(Dispatchers.Main) {
                    progress = 1f
                    message = "Pronto! Legenda salva em: Downloads / AudioXtract / $baseName.srt"
                    isError = false
                    running = false
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioXtract", "Whisper falhou", e)
                withContext(Dispatchers.Main) {
                    message = e.localizedMessage ?: "Erro ao gerar legenda."
                    isError = true
                    running = false
                }
            } finally {
                pcm.delete()
                whisper?.release()
            }
        }
    }

    private fun parseWords(raw: String, offset: Double): List<Triple<Double, Double, String>> {
        val rx = Regex("""\[(\d{2}):(\d{2}):(\d{2})\.(\d{3}) --> (\d{2}):(\d{2}):(\d{2})\.(\d{3})\]:\s*(.*)""")
        val words = mutableListOf<Triple<Double, Double, String>>()
        raw.lines().forEach { line ->
            val g = rx.matchEntire(line.trim())?.groupValues ?: return@forEach
            val a = g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt() + g[4].toInt() / 1000.0
            val b = g[5].toInt() * 3600 + g[6].toInt() * 60 + g[7].toInt() + g[8].toInt() / 1000.0
            val t = g[9].trim()
            if (t.isEmpty() || (t.startsWith("[") && t.endsWith("]"))) return@forEach
            words.add(Triple(a + offset, b + offset, t))
        }
        return words
    }

    private fun toSrt(words: List<Triple<Double, Double, String>>): String {
        val cues = buildCues(words)
        return buildString {
            cues.forEachIndexed { i, c ->
                append("${i + 1}\n${srtTime(c.start)} --> ${srtTime(c.end)}\n${c.text}\n\n")
            }
        }
    }

    private fun buildCues(
        words: List<Triple<Double, Double, String>>,
        maxWords: Int = 5, maxDur: Double = 2.8, maxGap: Double = 0.8
    ): List<Cue> {
        val cues = mutableListOf<Cue>()
        val cur = mutableListOf<Triple<Double, Double, String>>()
        fun flush() {
            if (cur.isNotEmpty()) {
                val text = cur.joinToString(" ") { it.third }.trim()
                if (text.isNotEmpty()) cues.add(Cue(cur.first().first, cur.last().second, text))
                cur.clear()
            }
        }
        for (w in words) {
            if (cur.isNotEmpty() && (cur.size >= maxWords ||
                        w.second - cur.first().first > maxDur ||
                        w.first - cur.last().second > maxGap)) flush()
            cur.add(w)
            if ((w.third.endsWith(".") || w.third.endsWith("?") || w.third.endsWith("!")) && cur.size >= 3) flush()
        }
        flush()
        for (i in cues.indices) {
            val c = cues[i]
            c.end = maxOf(c.end, c.start + 0.3)
            if (i + 1 < cues.size && c.end > cues[i + 1].start)
                c.end = maxOf(cues[i + 1].start, c.start + 0.1)
        }
        return cues
    }

    private fun srtTime(sec: Double): String {
        var ms = Math.round(maxOf(sec, 0.0) * 1000)
        val h = ms / 3600000; ms %= 3600000
        val m = ms / 60000; ms %= 60000
        val s = ms / 1000; ms %= 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    // Acha o ponto mais silencioso (janelas de 100 ms) pra cortar o bloco sem partir palavra
    private fun quietPoint(s: FloatArray, from: Int, to: Int): Int {
        val win = 1600
        var best = to
        var bestE = Float.MAX_VALUE
        var i = maxOf(from, 0)
        while (i + win <= to) {
            var e = 0f
            for (j in i until i + win) e += s[j] * s[j]
            if (e < bestE) { bestE = e; best = i + win / 2 }
            i += win
        }
        return best
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
