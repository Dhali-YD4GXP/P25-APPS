package com.p25.apx1000.net

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.p25.apx1000.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Self-hosted over-the-air update.
 *
 * Checks the version manifest on the backend; if a newer build is available it
 * downloads the APK into the cache and opens the system install dialog (normal
 * apps cannot install silently). Data is preserved because it is an in-place
 * update with the same signature.
 */
object UpdateChecker {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun check(context: Context, callback: (String) -> Unit) {
        val app = context.applicationContext
        val client = OkHttpClient()
        val url = BuildConfig.P25_API_URL + "/api/version"
        client.newCall(Request.Builder().url(url).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback("check failed: ${e.javaClass.simpleName}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    response.close()
                    try {
                        val o = JSONObject(body)
                        val code = o.optInt("versionCode", 0)
                        val name = o.optString("versionName", "")
                        if (code > BuildConfig.VERSION_CODE) {
                            callback("update available ($name), downloading…")
                            download(app, o, callback)
                        } else {
                            callback("up-to-date")
                        }
                    } catch (t: Throwable) {
                        callback("version parse failed")
                    }
                }
            })
    }

    private fun download(app: Context, o: JSONObject, callback: (String) -> Unit) {
        val path = o.optString("apkUrl", "/p25.apk")
        val url = if (path.startsWith("http")) path else BuildConfig.P25_API_URL + path
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("download failed: ${e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val apk = File(app.cacheDir, "apk/update.apk").apply { parentFile?.mkdirs() }
                    response.body?.byteStream()?.use { ins ->
                        apk.outputStream().use { out -> ins.copyTo(out) }
                    }
                    response.close()
                    install(app, apk, callback)
                } catch (t: Throwable) {
                    callback("download failed: ${t.message}")
                }
            }
        })
    }

    private fun install(app: Context, apk: File, callback: (String) -> Unit) {
        mainHandler.post {
            try {
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", apk)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                app.startActivity(intent)
                callback("update ready — tap Install")
            } catch (t: Throwable) {
                callback("install failed: ${t.message}")
            }
        }
    }
}
