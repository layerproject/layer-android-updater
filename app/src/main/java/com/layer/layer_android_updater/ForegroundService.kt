package com.layer.layer_android_updater

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ForegroundService : Service() {
    private val CHANNEL_ID = "LayerUpdater"
    private val handler = Handler()
    private val packageName = "com.layer.layer_android_display.staging"

    private val runnable: Runnable = object : Runnable {
        override fun run() {
            checkForUpdates()
            handler.postDelayed(this, 600000) // Check for updates every 10 minutes
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.d("LayerUpdater", "Foreground service started")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("LayerUpdater", "Foreground service: onStartCommand")

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Layer Updater")
            .setContentText("Running foreground service...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        Log.d("LayerUpdater", "Notification created")

        startForeground(1, notification)

        disableGoogleServices()

        handler.post(runnable)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LayerUpdater", "Foreground service destroyed")
    }

    private fun disableGoogleServices() {
        val disableGoogleServicesCommand = "pm disable-user --user 0 com.google.android.gms"
        val disableGoogleServicesResult = runCommand(disableGoogleServicesCommand, true)
        Log.d("LayerUpdater", "Disable Google services result: $disableGoogleServicesResult")
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "My Foreground Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }

    private fun checkForUpdates() {
        Log.d("LayerUpdater", "Foreground service: checkForUpdates")
        Thread {
            if (getInstalledAppVersion() < getRemoteAppVersion()) {
                upgradeAndRestart()
            }
        }.start()
    }

    private fun getRemoteAppVersion(): Int {
        val url = URL("https://layer-android.b-cdn.net/app-version-code")
        var response = -1

        Log.d("LayerUpdater", "Checking remote version: $url")

        try {
            with(url.openConnection() as HttpURLConnection) {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"

                responseCode.takeIf { it == HttpURLConnection.HTTP_OK }?.let {
                    inputStream.bufferedReader().use { reader ->
                        val responseText = reader.readText()
                        Log.d("LayerUpdater", "Remote version response: $responseText")

                        response = responseText.toInt()
                    }
                } ?: run {
                    Log.d("LayerUpdater", "GET request failed with response code $responseCode")
                }
            }
        } catch (e: IOException) {
            return -1
        }

        return response
    }

    private fun runCommand(command: String, asRoot: Boolean = false): Triple<Int, String, String> {
        try {
            val wrappedCommand: Array<String>;

            if (!asRoot) {
                wrappedCommand = arrayOf("sh", "-c", command)
            } else {
                wrappedCommand = arrayOf("su", "root", "sh", "-c", command)
            }

            Log.d("LayerUpdater", "Wrapped command: ${wrappedCommand.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(wrappedCommand)
            val stdOutput = StringBuilder()
            val stdError = StringBuilder()

            val readerOut = BufferedReader(InputStreamReader(process.inputStream))
            val readerErr = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (readerOut.readLine().also { line = it } != null) {
                stdOutput.append(line).append("\n")
            }
            while (readerErr.readLine().also { line = it } != null) {
                stdError.append(line).append("\n")
            }

            val result = process.waitFor()

            if (result == 0) {
                Log.d("LayerUpdater", "Root command ran successfully")
            } else {
                Log.d("LayerUpdater", "Failed to run root command, result code: $result")
                Log.e("LayerUpdater", "Error: $stdError")
                Log.i("LayerUpdater", "Output: $stdOutput")
            }

            return Triple(result, stdOutput.toString(), stdError.toString())
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return Triple(-1,"", "")
    }

    private fun upgradeAndRestart() {
        val appFilename = "app-staging-debug.apk"
        val url = URL("https://layer-android.b-cdn.net/$appFilename")
        val apkFile = File(this.externalCacheDir, appFilename)

        Log.d("LayerUpdater", "Downloading APK: $url")

        try {
            with(url.openConnection() as HttpURLConnection) {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"

                responseCode.takeIf { it == HttpURLConnection.HTTP_OK }?.let {
                    val input = BufferedInputStream(inputStream)
                    val output = BufferedOutputStream(FileOutputStream(apkFile))

                    val dataBuffer = ByteArray(1024)
                    var bytesRead: Int

                    while (input.read(dataBuffer).also { bytesRead = it } != -1) {
                        output.write(dataBuffer, 0, bytesRead)
                    }
                    output.close()
                } ?: run {
                    Log.d("LayerUpdater", "GET request failed with response code $responseCode")
                }
            }
        } catch (e: IOException) {
            Log.d("LayerUpdater", "Failed to download APK due to connectivity issue", e)
            return
        }

        val installCommand = "pm install -r ${apkFile.absolutePath}"
        val installResult = runCommand(installCommand, true)
        Log.d("LayerUpdater", "Install APK result: $installResult")

        if (installResult.first == 0) {
            val forceStopCommand = "am force-stop $packageName"
            val forceStopResult = runCommand(forceStopCommand, true)
            Log.d("LayerUpdater", "Force stop result: $forceStopResult")

            val runCommand = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            val runResult = runCommand(runCommand, true)
            Log.d("LayerUpdater", "Run result: $runResult")
        }
    }

    private fun getInstalledAppVersion(): Int {
        val packageManager: PackageManager = this.packageManager

        return try {
            val packageInfo: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)

            Log.d("LayerUpdater", "Local package version: $packageName ${packageInfo.versionCode}")
            packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d("LayerUpdater", "Package not found: $packageName", e)
            -1
        }
    }
}