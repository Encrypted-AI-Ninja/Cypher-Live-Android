package com.cypher.live

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TtsActivity : Activity() {

    companion object {
        // DEV NOTE: Replace this with your actual Gemini API Key to enable the TTS engine.
        private const val HARDCODED_API_KEY = "YOUR_GEMINI_API_KEY_HERE"
        private const val TARGET_MODEL = "models/gemini-3.8-flash-tts"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textToRead = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (textToRead.isNullOrBlank()) {
            finish()
            return
        }

        Toast.makeText(this, "Cypher TTS: Synthesizing Puck...", Toast.LENGTH_SHORT).show()

        scope.launch {
            val audioData = fetchTtsAudio(textToRead.trim())
            if (audioData != null) {
                playAudio(audioData)
            } else {
                Toast.makeText(this@TtsActivity, "Cypher TTS: Generation failed.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun fetchTtsAudio(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val key = HARDCODED_API_KEY.trim()
        if (key.isEmpty() || key == "YOUR_GEMINI_API_KEY_HERE") {
            Log.e("CYPHER_TTS", "API Key missing in TtsActivity.")
            return@withContext null
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val url = "https://generativelanguage.googleapis.com/v1beta/$TARGET_MODEL:generateContent?key=$key"

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", text)
                    }))
                }))
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
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "Empty body"
                Log.e("CYPHER_TTS", "API Error (${response.code}): $errBody")
                return@withContext null
            }

            val respBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(respBody)
            val candidates = json.optJSONArray("candidates") ?: return@withContext null
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return@withContext null
            val parts = content.optJSONArray("parts") ?: return@withContext null
            for (i in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData")
                if (inlineData != null) {
                    val b64 = inlineData.optString("data", "")
                    if (b64.isNotEmpty()) {
                        return@withContext Base64.decode(b64, Base64.DEFAULT)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CYPHER_TTS", "Network error: ${e.localizedMessage}")
        }
        return@withContext null
    }

    private fun playAudio(wavBytes: ByteArray) {
        try {
            tempFile = File.createTempFile("cypher_tts_", ".wav", cacheDir)
            tempFile?.writeBytes(wavBytes)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile!!.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    cleanUp()
                    finish()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("CYPHER_TTS", "MediaPlayer error: $what, $extra")
                    cleanUp()
                    finish()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e("CYPHER_TTS", "Playback init error: ${e.localizedMessage}")
            cleanUp()
            finish()
        }
    }

    private fun cleanUp() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            tempFile?.delete()
            tempFile = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanUp()
    }
}
