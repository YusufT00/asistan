package com.example.asistan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

class AsistanService : Service() {

    private lateinit var wm: WindowManager
    private var panel: TextView? = null
    private var bubble: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tr = Locale("tr", "TR")
    private var running = false
    private var emptyCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        val izinler = Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!izinler) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        emptyCount = 0
        removeBubble()
        showPanel("...")
        greet()
        return START_STICKY
    }

    // ---------- Karşılama ve konuşma ----------

    private fun greet() {
        val ad = getSharedPreferences("ayar", MODE_PRIVATE).getString("ad", "") ?: ""
        val hitap = if (ad.isBlank()) "" else " $ad"
        val metin = "Hey$hitap, merhaba. Ne yapmamı istersin?"
        if (ttsReady) {
            say(metin)
        } else {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = tr
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { handler.post { if (running) startListening() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { handler.post { if (running) startListening() } }
                    })
                    ttsReady = true
                    say(metin)
                } else {
                    startListening()
                }
            }
        }
    }

    private fun say(text: String, showPanel: Boolean = true) {
        recognizer?.cancel()
        if (showPanel) showPanel(text)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    // ---------- Dinleme ----------

    private fun startListening() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setText("Bu telefonda ses tanıma yok")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { setText("Dinliyorum...") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partial: Bundle?) {
                    val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) setText(t)
                }

                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) relisten() else handle(t)
                }

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopAll()
                    } else {
                        relisten()
                    }
                }
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(i)
    }

    private fun relisten() {
        emptyCount++
        if (emptyCount >= 6) {
            // Uzun süre ses gelmedi: pil için dinlemeyi bırak, baloncuğa dokununca devam eder
            collapseToBubble()
            return
        }
        handler.postDelayed({ startListening() }, 600)
    }

    // ---------- Komutlar ----------

    private fun handle(spoken: String) {
        emptyCount = 0
        val t = spoken.lowercase(tr)
        val words = t.split(" ", "'", "’")

        when {
            listOf("kapat", "görüşürüz", "hoşça kal", "çıkış").any { t.contains(it) } &&
                !words.contains("aç") -> {
                say("Görüşürüz")
                handler.postDelayed({ stopAll() }, 1500)
            }
            words.contains("aç") || t.contains("açar mısın") -> openApp(t)
            else -> {
                // İleride buraya yapay zeka (Claude API vb.) bağlanacak
                say("Bunu henüz yapamıyorum. Şunu duydum: $spoken")
            }
        }
    }

    private fun norm(s: String): String =
        s.lowercase(tr).filter { it.isLetterOrDigit() }

    private fun openApp(spoken: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = norm(spoken)

        // Konuşulan metin, uygulama adını (Türkçe ek almış olsa da) içeriyorsa eşleştir
        val best = pm.queryIntentActivities(launcher, 0).mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val n = norm(label)
            val key = if (n.length > 4) n.dropLast(1) else n
            if (key.length >= 3 && q.contains(key)) Triple(ri.activityInfo.packageName, label, key.length) else null
        }.maxByOrNull { it.third }

        if (best == null) {
            say("Bu isimde bir uygulama bulamadım")
            return
        }

        val launch = pm.getLaunchIntentForPackage(best.first)
        if (launch == null) {
            say("${best.second} açılamadı")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)

        collapseToBubble(keepListening = true)
        say("${best.second} açılıyor", showPanel = false)
    }

    // ---------- Ekrandaki arayüz ----------

    private fun overlayParams(gravity: Int, x: Int, y: Int, w: Int, h: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showPanel(text: String) {
        removeBubble()
        if (panel == null) {
            panel = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6202030"))
                    cornerRadius = dp(24).toFloat()
                }
            }
            wm.addView(
                panel,
                overlayParams(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(48),
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            )
        }
        panel?.text = text
    }

    private fun setText(text: String) { panel?.text = text }

    private fun collapseToBubble(keepListening: Boolean = false) {
        panel?.let { wm.removeView(it) }
        panel = null
        if (bubble == null) {
            bubble = TextView(this).apply {
                text = "AI"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 16f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC5B5BD6"))
                }
                setOnClickListener {
                    emptyCount = 0
                    removeBubble()
                    showPanel("Dinliyorum...")
                    startListening()
                }
            }
            wm.addView(
                bubble,
                overlayParams(Gravity.END or Gravity.CENTER_VERTICAL, 0, 0, dp(56), dp(56))
            )
        }
        if (!keepListening) recognizer?.cancel()
    }

    private fun removeBubble() {
        bubble?.let { wm.removeView(it) }
        bubble = null
    }

    // ---------- Servis yaşam döngüsü ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("asistan", "Asistan", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "asistan")
            .setContentTitle("Asistan çalışıyor")
            .setContentText("Seni dinlemeye hazır")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopAll() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        panel?.let { wm.removeView(it) }
        panel = null
        removeBubble()
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopAll()
        super.onDestroy()
    }
}
