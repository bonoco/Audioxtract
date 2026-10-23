package com.bonoco.audioxtract

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WhisperTest {
    private const val TAG = "WhisperTest"

    /** Teste de fumaça: vídeo -> texto no Logcat. */
    suspend fun run(context: Context, videoUri: Uri) = withContext(Dispatchers.IO) {
        val pcm = File(context.cacheDir, "whisper_input.pcm")
        if (pcm.exists()) pcm.delete()

        // 1. Vídeo -> PCM cru 16 kHz mono 16-bit (sem cabeçalho)
        val input = FFmpegKitConfig.getSafParameterForRead(context, videoUri)
        val session = FFmpegKit.execute(
            "-y -i $input -vn -ac 1 -ar 16000 -f s16le \"${pcm.absolutePath}\""
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e(TAG, "FFmpeg falhou: ${session.failStackTrace}")
            return@withContext
        }

        // 2. PCM -> FloatArray entre -1 e 1
        val shorts = ByteBuffer.wrap(pcm.readBytes())
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        Log.d(TAG, "Amostras: ${samples.size}")

        // 3. Whisper
        val whisper = WhisperContext.createContextFromAsset(
            context.assets, "models/ggml-base.bin"
        )
        try {
            val texto = whisper.transcribeData(samples, printTimestamp = true)
            Log.d(TAG, "RESULTADO:\n$texto")
        } finally {
            whisper.release()
        }
    }
}
