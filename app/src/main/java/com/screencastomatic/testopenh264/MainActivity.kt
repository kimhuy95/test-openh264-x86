package com.screencastomatic.testopenh264

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val originalFile = File("//android_asset/sample.mp4")
    private val inputFile = createInputFile()
    private val outputFile = createOutputFile()

    private val originalPlayer by lazy { createPlayer() }
    private val editedPlayer by lazy { createPlayer() }

    private var durationMs = 0L

    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        System.loadLibrary("openh264")

        originalPlayer.prepare(createMediaSource(originalFile))
        originalPlayerView.player = originalPlayer

        editedPlayerView.player = editedPlayer

        testButton.setOnClickListener { test() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (canEdit()) {
                edit()
            } else {
                message.text = "Please accept write external storage permission!"
            }
        }
    }

    private fun createPlayer(): SimpleExoPlayer {
        return SimpleExoPlayer.Builder(this).build()
    }

    private fun createMediaSource(file: File): MediaSource {
        val dataSourceFactory =
            DefaultDataSourceFactory(this, Util.getUserAgent(this, getString(R.string.app_name)))
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Uri.fromFile(file))
    }

    private fun test() {
        message.text = "Starting..."
        showInfo()
        if (canEdit()) {
            edit()
        } else {
            requestPermission()
        }
    }

    private fun edit() {
        disableTestButton()
        worker = thread {
            if (inputFile.exists()) {
                crop()
            } else {
                requestInputFile()
                crop()
            }
        }
    }

    private fun requestInputFile() {
        val inputStream = assets.open(originalFile.name)
        val outputStream = FileOutputStream(inputFile)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
    }

    private fun crop() {
        val retriever = MediaMetadataRetriever().apply {
            setDataSource(
                this@MainActivity,
                Uri.fromFile(inputFile)
            )
        }
        durationMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()

        val inputs = listOf("-y", "-c:v", "libopenh264", "-i", inputFile.absolutePath)

        val width = 500
        val height = 500
        val x = 300
        val y = 100
        val cropFilter = "crop=${width}:${height}:${x}:${y}"
        val filter = "-filter:v $cropFilter -max_muxing_queue_size 512".split(" ")

        val output = "-c:v libopenh264 -strict 2 ${outputFile.absolutePath}".split(" ")

        val cmd = inputs + filter + output

        executeFFmpegCommand(cmd)
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_CODE_PERMISSIONS
        )
    }

    private fun canEdit(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun executeFFmpegCommand(command: List<String>) {
        Config.enableLogCallback { log -> onProgress(log.text) }

        FFmpeg.execute(command.toTypedArray())

        when (FFmpeg.getLastReturnCode()) {
            FFmpeg.RETURN_CODE_SUCCESS -> {
                onSuccess()
            }
            FFmpeg.RETURN_CODE_CANCEL -> {
                onFailure()
            }
            else -> {
                onFailure()
            }
        }
    }

    private fun onProgress(msg: String) {
        val timePattern = Pattern.compile("(?<=time=)[\\d:.]*")
        val scanner = Scanner(msg)
        val match = scanner.findWithinHorizon(timePattern, 0)
        if (match != null) {
            try {
                val tokens = match.split(":")
                val hoursMs = tokens[0].toInt() * 3600 * 1000
                val minutesMs = tokens[1].toInt() * 60 * 1000
                val secondsGroup = tokens[2].split(".")
                val secondsMs = secondsGroup[0].toInt() * 1000
                val ms = secondsGroup[1].toInt()
                val doneMs = hoursMs + minutesMs + secondsMs + ms
                val currentProgress =
                    (doneMs.toDouble() / durationMs * 100).toInt().coerceAtMost(100)

                Handler(Looper.getMainLooper()).post {
                    message.text = "${currentProgress}%"
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun onSuccess() {
        Handler(Looper.getMainLooper()).post {
            hideInfo()
            editedPlayer.prepare(createMediaSource(outputFile))
            enableTestButton()

            stopWorker()
        }
    }

    private fun onFailure() {
        Handler(Looper.getMainLooper()).post {
            message.text = "Unexpected error!"

            enableTestButton()

            stopWorker()
        }
    }

    private fun stopWorker() {
        worker?.interrupt()
        worker = null
    }

    private fun createInputFile() = File(getVideoDirectory(), "test-openh264-x86-input.mp4")

    private fun createOutputFile() = File(getVideoDirectory(), "test-openh264-x86-output.mp4")

    private fun getVideoDirectory(): File {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (!path.exists()) {
            path.mkdirs()
        }
        return path
    }

    private fun hideInfo() {
        infoGroup.visibility = View.GONE
    }

    private fun showInfo() {
        infoGroup.visibility = View.VISIBLE
    }

    private fun enableTestButton() {
        testButton.apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorAccent))
            isClickable = true
            isFocusable = true
        }
    }

    private fun disableTestButton() {
        testButton.apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorGrey))
            isClickable = false
            isFocusable = false
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}

