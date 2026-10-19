package edu.unt.smartsight.metaglassestest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Speaks through the glasses speakers.
 *
 * Meta routes media playback and TTS over A2DP. HFP is left unused so speech stays
 * on the high-quality output instead of the 8 kHz call profile.
 */
internal class GlassesSpeaker(
    context: Context,
    private val onGlassesUnavailable: () -> Unit,
    private val onGlassesReady: () -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val speechFile = File(appContext.cacheDir, "detection-speech.wav")
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(MEDIA_ATTRIBUTES)
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var reportedMissing = false
    private var player: MediaPlayer? = null
    private var closed = false

    init {
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (closed) {
                    return@post
                }
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TextToSpeech init failed: $status")
                    return@post
                }
                tts?.setLanguage(Locale.US)
                tts?.setAudioAttributes(MEDIA_ATTRIBUTES)
                tts?.setOnUtteranceProgressListener(speechListener)
                ttsReady = true
                pump()
            }
        }
    }

    fun speak(phrase: String) {
        val cleaned = phrase.trim()
        if (cleaned.isEmpty()) {
            return
        }
        mainHandler.post {
            if (closed) {
                return@post
            }
            while (queue.size >= MAX_QUEUED) {
                queue.removeFirst()
            }
            queue.add(cleaned)
            pump()
        }
    }

    override fun close() {
        mainHandler.post {
            closed = true
            queue.clear()
            releasePlayer()
            audioManager.abandonAudioFocusRequest(focusRequest)
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    private val speechListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainHandler.post { playOnGlasses() }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post { finishUtterance() }
        }
    }

    private fun pump() {
        if (!ttsReady || speaking || queue.isEmpty() || closed) {
            return
        }
        val phrase = queue.removeFirst()
        val engine = tts
        if (engine == null) {
            finishUtterance()
            return
        }
        speaking = true
        val result = engine.synthesizeToFile(phrase, Bundle(), speechFile, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "synthesizeToFile failed: $result")
            finishUtterance()
        }
    }

    private fun playOnGlasses() {
        if (closed) {
            finishUtterance()
            return
        }
        val device = selectGlassesOutput()
        if (device == null) {
            queue.clear()
            if (!reportedMissing) {
                reportedMissing = true
                onGlassesUnavailable()
            }
            finishUtterance()
            return
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.clearCommunicationDevice()
        audioManager.requestAudioFocus(focusRequest)
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(MEDIA_ATTRIBUTES)
            mediaPlayer.setDataSource(speechFile.absolutePath)
            val preferred = mediaPlayer.setPreferredDevice(device)
            if (!preferred) {
                Log.e(TAG, "setPreferredDevice failed for ${device.productName}")
                releasePlayer()
                if (!reportedMissing) {
                    reportedMissing = true
                    onGlassesUnavailable()
                }
                finishUtterance()
                return
            }
            mediaPlayer.setOnCompletionListener {
                noteRoute(it.routedDevice)
                releasePlayer()
                finishUtterance()
            }
            mediaPlayer.setOnErrorListener { failed, _, _ ->
                Log.e(TAG, "MediaPlayer error on ${failed.routedDevice?.productName}")
                releasePlayer()
                finishUtterance()
                true
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
            noteRoute(mediaPlayer.routedDevice ?: device)
        } catch (error: Exception) {
            Log.e(TAG, "Glasses playback failed", error)
            releasePlayer()
            finishUtterance()
        }
    }

    private fun noteRoute(device: AudioDeviceInfo?) {
        if (device != null && glassesRank(device) > 0) {
            if (reportedMissing) {
                reportedMissing = false
                onGlassesReady()
            }
            Log.i(TAG, "Speech route: ${device.productName} type=${device.type}")
        }
    }

    private fun finishUtterance() {
        speaking = false
        if (!closed) {
            pump()
        }
    }

    private fun releasePlayer() {
        player?.run {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            release()
        }
        player = null
    }

    private fun selectGlassesOutput(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .maxByOrNull { glassesRank(it) }
            ?.takeIf { glassesRank(it) > 0 }
    }

    private fun glassesRank(device: AudioDeviceInfo): Int {
        val typeRank = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> A2DP_RANK
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> BLE_RANK
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> SCO_RANK
            else -> return 0
        }
        val name = device.productName?.toString()?.lowercase(Locale.US).orEmpty()
        val nameRank = if ("meta" in name || "ray-ban" in name || "rayban" in name) NAME_RANK else 0
        return typeRank + nameRank
    }

    private companion object {
        const val TAG = "MetaGlassesTest"
        const val UTTERANCE_ID = "detection"
        const val MAX_QUEUED = 3
        const val A2DP_RANK = 30
        const val BLE_RANK = 20
        const val SCO_RANK = 10
        const val NAME_RANK = 5

        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
