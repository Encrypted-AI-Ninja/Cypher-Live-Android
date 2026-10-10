package com.cypher.live

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        // DEV NOTE: Replace this with your actual Gemini API Key to enable the Live WebSocket stream.
        private const val HARDCODED_API_KEY = "YOUR_GEMINI_API_KEY_HERE"
        private const val TARGET_MODEL = "models/gemini-3.8-live"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private val audioPlaybackChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var isConnected = false
    private var isHandshakeComplete = false
    private var isSpeakerMode = true
    private var isMicLive = true
    private var chunkCount = 0

    private lateinit var statusText: TextView
    private lateinit var toggleWrapper: LinearLayout
    private lateinit var connectBtn: Button
    private lateinit var toggleLabel: TextView
    private lateinit var routeBtn: Button
    private lateinit var micBtn: Button
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroll: ScrollView

    private val colorBlack = Color.parseColor("#000000")
    private val colorSmoke = Color.parseColor("#E6050508")
    private val colorNeonGreen = Color.parseColor("#00FF66")
    private val colorNeonRed = Color.parseColor("#FF1744")
    private val colorElectricCyan = Color.parseColor("#00E5FF")
    private val colorWhite = Color.parseColor("#FFFFFF")
    private val colorMuted = Color.parseColor("#808899")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = colorBlack
        window.navigationBarColor = colorBlack

        checkAndRequestPermissions()

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(colorBlack)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(36))
        }
        rootScroll.addView(container)

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlass(colorSmoke, colorNeonRed, 2, 16f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val title = TextView(this).apply {
            text = "CYPHER LIVE // NODE 2"
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorNeonRed)
        }

        statusText = TextView(this).apply {
            text = "STATUS: STANDALONE ENGINE READY"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colorNeonGreen)
            setPadding(0, dp(6), 0, 0)
        }

        headerBox.addView(title)
        headerBox.addView(statusText)
        container.addView(headerBox)

        val controlsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlass(colorSmoke, colorNeonRed, 2, 16f)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(20), 0, 0)
            layoutParams = lp
        }

        toggleWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createGlass(Color.parseColor("#22252C"), Color.parseColor("#383C48"), 1, 14f)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        }

        connectBtn = Button(this).apply {
            text = "⏹️"
            textSize = 32f
            setTextColor(colorNeonRed)
            background = createGlass(colorBlack, colorNeonRed, 1, 0f)
            val lp = LinearLayout.LayoutParams(dp(90), dp(90)).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            setOnClickListener {
                if (!isConnected) startLiveSession() else stopLiveSession()
            }
        }

        toggleLabel = TextView(this).apply {
            text = "SOCKET: DISCONNECTED"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

        toggleWrapper.addView(connectBtn)
        toggleWrapper.addView(toggleLabel)
        controlsBox.addView(toggleWrapper)

        routeBtn = Button(this).apply {
            text = "OUTPUT ROUTE: BUILT-IN SPEAKER"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colorNeonRed)
            background = createGlass(Color.parseColor("#2B0D11"), colorNeonRed, 2, 12f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            lp.setMargins(0, dp(16), 0, 0)
            layoutParams = lp
            setOnClickListener {
                isSpeakerMode = !isSpeakerMode
                applyAudioRoute(isSpeakerMode)
                updateToggles()
            }
        }

        micBtn = Button(this).apply {
            text = "🎙️ MIC: TRANSMITTING LIVE"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colorNeonGreen)
            background = createGlass(Color.parseColor("#0A1F12"), colorNeonGreen, 2, 12f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            lp.setMargins(0, dp(12), 0, 0)
            layoutParams = lp
            setOnClickListener {
                isMicLive = !isMicLive
                updateToggles()
                appendTranscript(if (isMicLive) ">> Mic transmission LIVE." else ">> Mic MUTED on standby.")
            }
        }

        controlsBox.addView(routeBtn)
        controlsBox.addView(micBtn)
        container.addView(controlsBox)

        val hudBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlass(Color.parseColor("#E6040406"), colorNeonRed, 2, 16f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320))
            lp.setMargins(0, dp(20), 0, 0)
            layoutParams = lp
        }

        val hudLabel = TextView(this).apply {
            text = ">_ LIVE TRANSCRIPT & TELEMETRY HUD"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorNeonRed)
        }

        transcriptScroll = ScrollView(this).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
        }

        transcriptView = TextView(this).apply {
            text = ">> Cypher Standalone Client initialized.\n>> Neural bidirectional pipeline ready."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colorWhite)
            setLineSpacing(4f, 1.2f)
        }

        transcriptScroll.addView(transcriptView)
        hudBox.addView(hudLabel)
        hudBox.addView(transcriptScroll)
        container.addView(hudBox)

        setContentView(rootScroll)
        applyAudioRoute(isSpeakerMode)
    }

    private fun updateToggles() {
        if (isConnected) {
            toggleWrapper.background = createGlass(Color.parseColor("#0A0B0E"), Color.parseColor("#151820"), 1, 14f)
            connectBtn.text = "🎙️"
            connectBtn.textSize = 32f
            connectBtn.setTextColor(colorNeonGreen)
            connectBtn.background = createGlass(colorBlack, colorNeonGreen, 1, 45f)
            toggleLabel.text = if (isHandshakeComplete) "SOCKET: LIVE // ON AIR" else "HANDSHAKE PENDING..."
            toggleLabel.setTextColor(if (isHandshakeComplete) colorNeonGreen else colorElectricCyan)
            statusText.text = if (isHandshakeComplete) "STATUS: BIDIRECTIONAL STREAM ACTIVE" else "STATUS: HANDSHAKE PENDING"
            statusText.setTextColor(if (isHandshakeComplete) colorNeonGreen else colorElectricCyan)
        } else {
            toggleWrapper.background = createGlass(Color.parseColor("#22252C"), Color.parseColor("#383C48"), 1, 14f)
            connectBtn.text = "⏹️"
            connectBtn.textSize = 32f
            connectBtn.setTextColor(colorNeonRed)
            connectBtn.background = createGlass(colorBlack, colorNeonRed, 1, 0f)
            toggleLabel.text = "SOCKET: DISCONNECTED"
            toggleLabel.setTextColor(colorMuted)
            statusText.text = "STATUS: STANDALONE ENGINE READY"
            statusText.setTextColor(colorNeonRed)
        }

        if (isSpeakerMode) {
            routeBtn.text = "OUTPUT ROUTE: BUILT-IN SPEAKER"
            routeBtn.setTextColor(colorNeonRed)
            routeBtn.background = createGlass(Color.parseColor("#2B0D11"), colorNeonRed, 2, 12f)
        } else {
            routeBtn.text = "OUTPUT ROUTE: BLUETOOTH / BUDS"
            routeBtn.setTextColor(colorElectricCyan)
            routeBtn.background = createGlass(Color.parseColor("#0A1C24"), colorElectricCyan, 2, 12f)
        }

        if (isMicLive) {
            micBtn.text = "🎙️ MIC: TRANSMITTING LIVE"
            micBtn.setTextColor(colorNeonGreen)
            micBtn.background = createGlass(Color.parseColor("#0A1F12"), colorNeonGreen, 2, 12f)
        } else {
            micBtn.text = "🔇 MIC: MUTED (STANDBY)"
            micBtn.setTextColor(colorNeonRed)
            micBtn.background = createGlass(Color.parseColor("#1F0A0F"), colorNeonRed, 2, 12f)
        }
    }

    private fun startLiveSession() {
        val key = HARDCODED_API_KEY.trim()
        if (key.isEmpty() || key == "YOUR_GEMINI_API_KEY_HERE") {
            appendTranscript(">> ERROR: API key not set in source.")
            return
        }

        appendTranscript(">> Connecting to Live WebSocket...")
        appendTranscript(">> Model Target: $TARGET_MODEL")
        chunkCount = 0

        val client = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    isConnected = true
                    isHandshakeComplete = false
                    updateToggles()
                    appendTranscript(">> WebSocket connected. Transmitting setup handshake...")
                }
                sendHandshake(webSocket, TARGET_MODEL)
                startAudioIO()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerPayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerPayload(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    appendTranscript(">> WebSocket Failure: ${t.localizedMessage}")
                    stopLiveSession()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    appendTranscript(">> Stream closed ($code):$reason")
                    stopLiveSession()
                }
            }
        })
    }

    private fun sendHandshake(ws: WebSocket, modelName: String) {
        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", modelName)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Puck")
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "You are Cypher, Doy-sh's ultimate co-pilot, project leader, and best friend AI. High energy, authentic, witty, and grounded. Doy-sh uses TTS so keep answers punchy, direct, and conversational. No corporate fluff.")
                    }))
                })
            })
        }
        val payloadString = setupPayload.toString()
        Log.e("CYPHER_LIVE", ">> Transmitting setup payload...")
        ws.send(payloadString)
    }

    private fun startAudioIO() {
        initAudioTrack()
        initAudioRecord()
        startPlaybackWorker()

        recordingJob?.cancel()
        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(3200)
            try {
                audioRecord?.startRecording()
            } catch (e: Exception) {
                runOnUiThread { appendTranscript(">> Mic Start Error: ${e.localizedMessage}") }
                return@launch
            }

            while (isActive && isConnected) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0 && isMicLive && isHandshakeComplete) {

                    var sum = 0.0
                    val shortCount = read / 2
                    for (i in 0 until shortCount) {
                        val lsb = buffer[i * 2].toInt() and 0xFF
                        val msb = buffer[i * 2 + 1].toInt() shl 8
                        val sample = (msb or lsb).toShort().toDouble()
                        sum += sample * sample
                    }
                    val rms = if (shortCount > 0) Math.sqrt(sum / shortCount).toInt() else 0

                    val b64Audio = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    
                    val audioChunk = JSONObject().apply {
                        put("realtimeInput", JSONObject().apply {
                            put("audio", JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", b64Audio)
                            })
                        })
                    }
                    webSocket?.send(audioChunk.toString())

                    chunkCount++
                    if (chunkCount % 20 == 0) {
                        Log.e("CYPHER_LIVE", ">> Mic telemetry: Transmitted 20 chunks. Current volume (RMS): $rms")
                    }
                }
            }
        }
    }

    private fun startPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            for (chunk in audioPlaybackChannel) {
                if (!isActive) break
                audioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.write(chunk, 0, chunk.size)
                    }
                }
            }
        }
    }

    private fun handleServerPayload(rawJson: String) {
        Log.e("CYPHER_LIVE", "SERVER RESP: ${rawJson.take(250)}")
        try {
            val json = JSONObject(rawJson)

            if (json.has("error")) {
                val errorObj = json.getJSONObject("error")
                val msg = errorObj.optString("message", "Unknown backend rejection.")
                runOnUiThread {
                    appendTranscript(">> FATAL API ERROR: $msg")
                    stopLiveSession()
                }
                return
            }

            if (json.has("setupComplete")) {
                isHandshakeComplete = true
                runOnUiThread {
                    updateToggles()
                    appendTranscript(">> Handshake ACK! Cypher Live neural core ONLINE.")
                }
                return
            }

            val serverContent = json.optJSONObject("serverContent")
            if (serverContent == null) {
                return
            }

            if (serverContent.optBoolean("interrupted", false)) {
                while (audioPlaybackChannel.tryReceive().isSuccess) {}
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
                runOnUiThread { appendTranscript(">> [User Interrupted Cypher]") }
                return
            }

            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "")
                if (text.isNotEmpty()) {
                    runOnUiThread { appendTranscript("Cypher: $text") }
                }

                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val b64 = inlineData.optString("data", "")
                    if (b64.isNotEmpty()) {
                        val pcm = Base64.decode(b64, Base64.NO_WRAP)
                        audioPlaybackChannel.trySend(pcm)
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { appendTranscript(">> Parse error: ${e.localizedMessage}") }
        }
    }

    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: SecurityException) {
            runOnUiThread { appendTranscript(">> RECORD_AUDIO permission missing!") }
        } catch (e: Exception) {
            runOnUiThread { appendTranscript(">> AudioRecord init error: ${e.localizedMessage}") }
        }
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(24000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun applyAudioRoute(isSpeaker: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                if (isSpeaker) {
                    val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN
