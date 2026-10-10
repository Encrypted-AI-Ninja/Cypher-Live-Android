# Cypher Live: Android Neural Co-Pilot

A native Android dual-engine AI co-pilot featuring real-time bidirectional WebSocket audio streaming (Gemini 3.8 Live) and system-wide text-to-speech integration (Gemini 3.8 Flash TTS).

## 🧠 Architecture Overview

This project is split into two independent neural engines operating natively on Android hardware:

### 1. Live Streaming Engine (`MainActivity.kt`)
A persistent, bidirectional audio stream connecting the device's microphone and speakers directly to Google's Gemini 3.8 Live backend.
* **Low-Latency WebSockets:** Maintains a live `wss://` connection to the `v1beta` endpoint.
* **PCM Audio Encoding:** Captures raw 16kHz PCM audio, calculates RMS volume telemetry, and streams base64-encoded bytes via the strict `realtimeInput.audio` JSON schema.
* **VAD Bypass:** Integrates manual turn-yielding overrides to manage backend Voice Activity Detection limitations in complex audio environments.
* **Dynamic Audio Routing:** Seamlessly switches between the device speaker and Bluetooth SCO/BLE headsets during active transmission.

### 2. System-Wide TTS Engine (`TtsActivity.kt`)
An on-demand, system-wide text synthesis pipeline.
* **Intent Interception:** Binds to the Android `ACTION_PROCESS_TEXT` intent, allowing users to highlight text in browsers or documents and route it directly to the AI.
* **REST API Integration:** Sends raw text payloads to the dedicated `gemini-3.8-flash-tts` REST endpoint.
* **WAV Decoding & Lifecycle:** Handles base64 24kHz WAV payloads and manages native Android `MediaPlayer` resources within a translucent activity layer to prevent disrupting the user's active foreground app.

## 🛠️ Technical Stack
* **Language:** Kotlin
* **Concurrency:** Kotlin Coroutines (`Dispatchers.IO`, `Channels`)
* **Networking:** OkHttp (WebSockets & REST)
* **Audio Processing:** `AudioRecord`, `AudioTrack`, `MediaPlayer`

## 🚀 Setup & Deployment
1. Clone the repository to Android Studio.
2. Replace the `"YOUR_GEMINI_API_KEY_HERE"` placeholders in both `MainActivity.kt` and `TtsActivity.kt` with an active Google AI Studio API key.
3. Ensure `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, and `BLUETOOTH_CONNECT` permissions are granted on the physical Android device.
4. Compile and deploy via `gradle installDebug`.

---
*Built and optimized for modern foldable form factors.*
