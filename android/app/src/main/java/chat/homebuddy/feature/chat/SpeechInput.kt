package chat.homebuddy.feature.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

interface SpeechListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(message: String)
}

/**
 * Dictation for questions. Uses the on-device recognizer when the phone has one (Android 12+),
 * so the audio stays on the phone; otherwise the phone's speech service, which may send it to its
 * provider (the privacy policy says so). The result only fills the text box.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = onDevice() || SpeechRecognizer.isRecognitionAvailable(context)

    private fun onDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(listener: SpeechListener) {
        release()
        val created = runCatching {
            if (onDevice()) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (created == null) {
            listener.onError("Voice input isn't available on this phone.")
            return
        }
        recognizer = created
        created.setRecognitionListener(
            object : RecognitionListener {
                override fun onPartialResults(results: Bundle?) {
                    results.firstResult()?.let(listener::onPartial)
                }

                override fun onResults(results: Bundle?) {
                    listener.onFinal(results.firstResult().orEmpty())
                }

                override fun onError(error: Int) {
                    listener.onError(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic and try again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Home Buddy needs microphone access for voice input."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice input needs a connection on this phone."
                            else -> "Voice input stopped. You can type your question instead."
                        },
                    )
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
