package com.example.asistan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ayar", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Asistan kurulumu"
            textSize = 24f
        }
        val name = EditText(this).apply {
            hint = "Adın (sana bu isimle seslenecek)"
            setText(prefs.getString("ad", ""))
        }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        root.addView(title)
        root.addView(name)

        root.addView(btn("1) Mikrofon ve bildirim izni ver") {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1
            )
        })

        root.addView(btn("2) Diğer uygulamaların üzerinde göster izni") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        root.addView(btn("3) Varsayılan asistan olarak seç") {
            // Açılan ekranda "Varsayılan dijital asistan uygulaması" -> Asistan seçilir
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        })

        root.addView(btn("4) Asistanı başlat") {
            prefs.edit().putString("ad", name.text.toString().trim()).apply()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Önce 2. adımdaki izni ver", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, AsistanService::class.java))
            }
        })

        setContentView(root)
    }
}
