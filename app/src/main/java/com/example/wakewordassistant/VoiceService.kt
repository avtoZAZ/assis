package com.example.wakewordassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var porcupineManager: PorcupineManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningCommand = false
    private lateinit var geminiClient: GeminiClient
    private var textToSpeech: TextToSpeech? = null
    private var commandJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Слушаю wake word..."))

        geminiClient = GeminiClient(BuildConfig.GEMINI_API_KEY)
        textToSpeech = TextToSpeech(this, this)
        initSpeechRecognizer()
        initPorcupine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        porcupineManager?.stop()
        porcupineManager?.delete()
        speechRecognizer?.destroy()
        commandJob?.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale("ru", "RU")
            textToSpeech?.setAudioStreamType(AudioManager.STREAM_MUSIC)
        }
    }

    private fun initPorcupine() {
        if (BuildConfig.PICOVOICE_ACCESS_KEY.isBlank()) {
            updateNotification("Нет PICOVOICE ключа в BuildConfig")
            return
        }

        try {
            // Вариант с кастомным словом: setKeywordPath("/assets/your_wake_word.ppn")
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setKeywordPath("porcupine_android.ppn")
                .build(this) { onWakeWordDetected() }

            porcupineManager?.start()
            updateNotification("Wake word активен")
        } catch (e: PorcupineException) {
            updateNotification("Ошибка Porcupine: ${e.message}")
        }
    }

    private fun onWakeWordDetected() {
        if (isListeningCommand) return
        isListeningCommand = true
        updateNotification("Wake word пойман, слушаю команду...")
        startCommandRecognition()
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

                override fun onError(error: Int) {
                    isListeningCommand = false
                    updateNotification("Ошибка распознавания речи: $error")
                }

                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    isListeningCommand = false

                    if (text.isBlank()) {
                        updateNotification("Команда не распознана")
                        return
                    }

                    updateNotification("Команда: $text")
                    sendToGemini(text)
                }
            })
        }
    }

    private fun startCommandRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun sendToGemini(command: String) {
        commandJob?.cancel()
        commandJob = serviceScope.launch(Dispatchers.IO) {
            runCatching { geminiClient.ask(command) }
                .onSuccess { action ->
                    launch(Dispatchers.Main) { executeAction(action) }
                }
                .onFailure {
                    launch(Dispatchers.Main) {
                        speak("Ошибка при запросе к Gemini")
                        updateNotification("Gemini error: ${it.message}")
                    }
                }
        }
    }

    private fun executeAction(action: AssistantAction) {
        when (action.action) {
            "play_music" -> {
                val query = action.query.orEmpty().ifBlank { "музыка" }
                val playIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(android.app.SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(playIntent)
                updateNotification("Воспроизвожу: $query")
            }

            "speak" -> {
                val response = action.response.orEmpty().ifBlank { "Не понял команду" }
                speak(response)
                updateNotification("Ответ: $response")
            }

            else -> {
                speak("Неизвестное действие")
                updateNotification("Неизвестный action: ${action.action}")
            }
        }
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_tts")
    }

    private fun buildNotification(contentText: String): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WakeWord Assistant")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wakeword_assistant_channel"
        private const val NOTIFICATION_ID = 7001
    }
}
