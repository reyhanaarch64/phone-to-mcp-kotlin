package rh.mcp.phone

import android.content.Context
import android.speech.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SpeechController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var initStatus: Int = TextToSpeech.ERROR

    init {
        engine = TextToSpeech(appContext) { status ->
            initStatus = status
            ready.set(status == TextToSpeech.SUCCESS)
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = RuntimeLog.event(appContext, "TTS utterance completed")
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = RuntimeLog.event(appContext, "TTS utterance failed")
                override fun onError(utteranceId: String, errorCode: Int) =
                    RuntimeLog.event(appContext, "TTS utterance failed code=$errorCode")
            })
        }
    }

    @Synchronized
    fun speak(text: String, language: String, queue: String): org.json.JSONObject {
        require(text.isNotBlank()) { "text wajib diisi" }
        require(text.length <= 10_000) { "text maksimal 10000 karakter" }
        val tts = engine
        check(ready.get() && tts != null) { "Android TextToSpeech engine belum siap (status=$initStatus)" }
        val locale = if (language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(language)
        val availability = tts.isLanguageAvailable(locale)
        require(availability >= TextToSpeech.LANG_AVAILABLE) { "bahasa tidak didukung engine: ${locale.toLanguageTag()}" }
        tts.language = locale
        val mode = if (queue == "add") TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val utterance = UUID.randomUUID().toString()
        val status = tts.speak(text, mode, null, utterance)
        check(status == TextToSpeech.SUCCESS) { "TTS engine menolak permintaan" }
        return org.json.JSONObject().put("accepted", true).put("utteranceId", utterance)
            .put("language", locale.toLanguageTag()).put("languageAvailability", availability)
            .put("engine", tts.defaultEngine ?: "system")
    }

    @Synchronized
    fun stop(): org.json.JSONObject {
        val stopped = engine?.stop() == TextToSpeech.SUCCESS
        return org.json.JSONObject().put("stopped", stopped)
    }

    override fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready.set(false)
    }
}
