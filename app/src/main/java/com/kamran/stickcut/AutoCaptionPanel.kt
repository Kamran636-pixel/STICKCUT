package com.kamran.stickcut

import android.content.Context
import android.provider.OpenableColumns
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL

// ---------------------------------------------------------------------------
// Step 89 / 90: Auto captions with Groq Whisper (online, optional).
// - Your audio is sent ONLY after you tap "Yes, send" in the question box.
// - The API key is typed by you and kept on this phone only (never in the code).
// - The answer has the real word times that the Whisper model gives.
// This first version sends the audio file as it is (maximum 25 MB, free plan).
// Step 90: the offline engine check box is shown above this one.
// ---------------------------------------------------------------------------

object AsrKeyStore {
    private const val PREFS = "stickcut_asr"
    private const val KEY = "groq_key"

    fun load(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
    }

    fun save(context: Context, value: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.trim())
            .apply()
    }
}

class AsrException(message: String) : Exception(message)

class GroqAsrResult(
    val words: List<CaptionWord>,
    val text: String,
    val language: String?,
    val tookMs: Long
)

object GroqAsr {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val MAX_BYTES = 25L * 1024L * 1024L

    @Volatile
    private var active: HttpURLConnection? = null

    @Volatile
    private var cancelled = false

    // Stops the running request (the upload or the waiting for the answer).
    fun abort() {
        cancelled = true
        try {
            active?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return -1L
    }

    private fun mimeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3", "mpeg", "mpga" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
    }

    // Blocking. Call it away from the main thread.
    // languageCode: "bn", "en", ... or null for automatic.
    fun transcribe(
        context: Context,
        uri: Uri,
        fileName: String,
        apiKey: String,
        languageCode: String?,
        model: String,
        onStatus: (String) -> Unit
    ): GroqAsrResult {
        if (apiKey.isBlank()) throw AsrException("Please type your Groq API key first.")

        val size = sizeOf(context, uri)
        if (size > MAX_BYTES) {
            throw AsrException(
                "This audio is ${size / (1024 * 1024)} MB. The limit is 25 MB. " +
                    "Cutting long audio into parts will come in a later step."
            )
        }

        cancelled = false
        val started = System.currentTimeMillis()
        val boundary = "----StickCut" + started
        // Only plain letters in the file name, because it is written as bytes.
        val extension = fileName.substringAfterLast('.', "mp3").filter { it.isLetterOrDigit() }.ifEmpty { "mp3" }
        val sendName = "audio.$extension"

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        active = conn
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim())
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(0)

            onStatus("Sending the audio…")
            DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("model", model)
                field("response_format", "verbose_json")
                field("timestamp_granularities[]", "word")
                field("temperature", "0")
                if (languageCode != null) field("language", languageCode)

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$sendName\"\r\n")
                out.writeBytes("Content-Type: ${mimeFor(sendName)}\r\n\r\n")

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw AsrException("The audio file could not be opened.")
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) throw AsrException("The audio is bigger than 25 MB.")
                        if (cancelled) throw AsrException("Cancelled.")
                        out.write(buffer, 0, count)
                        onStatus("Sending the audio… ${total / 1024} KB")
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
            }

            onStatus("Waiting for Groq to listen… (this can take a while)")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val hint = when (code) {
                    401 -> " (the API key was not accepted)"
                    413 -> " (the file is too big)"
                    429 -> " (too many requests: wait a minute and try again)"
                    else -> ""
                }
                throw AsrException("Groq answered with error $code$hint: ${body.take(300)}")
            }

            return parse(body, System.currentTimeMillis() - started)
        } catch (e: AsrException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AsrException("No internet connection (cannot reach api.groq.com).")
        } catch (e: SecurityException) {
            throw AsrException(
                "The app has no internet permission yet. AndroidManifest.xml needs android.permission.INTERNET."
            )
        } catch (e: Exception) {
            if (cancelled) throw AsrException("Cancelled.")
            val message = e.message ?: e.javaClass.simpleName
            if (message.contains("Permission denied", ignoreCase = true)) {
                throw AsrException(
                    "The app has no internet permission yet. AndroidManifest.xml needs android.permission.INTERNET."
                )
            }
            throw AsrException("The request failed: $message")
        } finally {
            active = null
            try {
                conn.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun parse(body: String, tookMs: Long): GroqAsrResult {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AsrException("The answer could not be read: ${body.take(200)}")
        }
        val array = json.optJSONArray("words")
            ?: throw AsrException("Groq answered, but without word times. The answer started with: ${body.take(200)}")
        val whitespace = Regex("\\s+")
        val words = ArrayList<CaptionWord>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val text = o.optString("word", "").replace(whitespace, "")
            if (text.isEmpty()) continue
            val start = (o.optDouble("start", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
            val end = (o.optDouble("end", 0.0) * 1000.0).toLong().coerceAtLeast(start)
            words.add(CaptionWord(text, start, end))
        }
        if (words.isEmpty()) throw AsrException("No words were found in the audio.")
        val language = json.optString("language", "").ifEmpty { null }
        return GroqAsrResult(words, json.optString("text", ""), language, tookMs)
    }
}

// The last result is kept here so it stays when the screen is redrawn.
object AutoCaptionResultStore {
    var result by mutableStateOf<GroqAsrResult?>(null)
}

private val AcBlue = Color(0xFF2D8CFF)
private val AcCard = Color(0xFF0F2F70)
private val AcDark = Color(0xFF0A1A44)
private val AcOk = Color(0xFF6BE3A0)
private val AcWarn = Color(0xFFFFC857)
private val AcSoft = Color(0xFFB8D4FF)

@Composable
fun AutoCaptionPanel(audios: List<ImportedAudio>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(AsrKeyStore.load(context)) }
    var showKey by remember { mutableStateOf(false) }
    var languageIndex by remember { mutableStateOf(0) }
    var modelIndex by remember { mutableStateOf(0) }
    var audioIndex by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var askConsent by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    val languages = listOf("Bengali" to "bn", "English" to "en", "Auto" to null)
    val models = listOf(
        "More accurate" to "whisper-large-v3",
        "Faster, cheaper" to "whisper-large-v3-turbo"
    )
    val chosenAudio = audios.getOrNull(audioIndex.coerceIn(0, (audios.size - 1).coerceAtLeast(0)))
    val result = AutoCaptionResultStore.result

    fun start() {
        val audio = chosenAudio ?: return
        error = null
        info = null
        working = true
        status = "Starting…"
        job = scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    GroqAsr.transcribe(
                        context = context,
                        uri = audio.uri,
                        fileName = audio.name,
                        apiKey = apiKey,
                        languageCode = languages[languageIndex].second,
                        model = models[modelIndex].second,
                        onStatus = { status = it }
                    )
                }
                AutoCaptionResultStore.result = outcome
                info = "Done in ${outcome.tookMs / 1000} s."
            } catch (e: AsrException) {
                error = e.message
            } catch (e: kotlinx.coroutines.CancellationException) {
                error = "Cancelled."
            } catch (e: Exception) {
                error = "Something went wrong: ${e.message}"
            } finally {
                working = false
            }
        }
    }

    // Puts the words into the caption system (the "Word times" way of Step 88).
    fun useAsCaptions(r: GroqAsrResult) {
        val offset = audios.take(audioIndex).sumOf { it.durationMs }
        val sb = StringBuilder()
        for (w in r.words) {
            sb.append(CaptionFileParser.formatTime(w.startMs + offset)).append(' ')
            sb.append(CaptionFileParser.formatTime(w.endMs + offset)).append(' ')
            sb.append(w.text).append('\n')
        }
        val parsed = CaptionFileParser.parse(sb.toString(), null)
        if (parsed.cues.isEmpty()) {
            error = "The words could not be turned into captions."
            return
        }
        ImportedCaptionStore.load("Auto captions", parsed.cues)
        ImportedCaptionNotes.format = parsed.format
        ImportedCaptionNotes.messages = parsed.errors
        ImportedCaptionMode.useStudio = true
        info = "${parsed.cues.size} captions are ready. See the preview above."
    }

    OfflineEngineCheck()

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(shape)
            .background(AcDark)
            .border(1.dp, AcBlue.copy(alpha = 0.5f), shape)
            .padding(14.dp)
    ) {
        Text(
            text = "Auto captions (online, Groq Whisper)",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Listens to your voiceover and gives every word with its time. Your audio is sent to Groq (api.groq.com) only after you say Yes.",
            color = AcSoft,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (audios.isEmpty()) {
            Text(
                text = "No voiceover audio in this project yet.",
                color = AcWarn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    AsrKeyStore.save(context, it)
                },
                singleLine = true,
                label = { Text("Groq API key") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                colors = acFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (showKey) "Hide key" else "Show key",
                color = AcBlue,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { showKey = !showKey }
                    .padding(vertical = 4.dp)
            )

            if (audios.size > 1) {
                Text(text = "Audio", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                audios.forEachIndexed { index, audio ->
                    AcChip(
                        text = "${index + 1}. ${audio.name}",
                        selected = index == audioIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        onClick = { audioIndex = index }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            } else {
                Text(
                    text = "Audio: ${audios[0].name}",
                    color = AcSoft,
                    fontSize = 12.sp
                )
            }

            Text(
                text = "Language",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                languages.forEachIndexed { index, pair ->
                    AcChip(
                        text = pair.first,
                        selected = index == languageIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { languageIndex = index }
                    )
                }
            }
            Text(
                text = "Model",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                models.forEachIndexed { index, pair ->
                    AcChip(
                        text = pair.first,
                        selected = index == modelIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { modelIndex = index }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (working) {
                Text(text = status, color = AcSoft, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                AcChip(
                    text = "Cancel",
                    selected = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        GroqAsr.abort()
                        job?.cancel()
                    }
                )
            } else {
                AcChip(
                    text = "🎙 Make captions from the audio",
                    selected = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (apiKey.isBlank()) {
                            error = "Please type your Groq API key first (console.groq.com/keys)."
                        } else {
                            error = null
                            askConsent = true
                        }
                    }
                )
            }

            val errorText = error
            if (errorText != null) {
                Text(
                    text = "⚠ $errorText",
                    color = AcWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val infoText = info
            if (infoText != null) {
                Text(
                    text = infoText,
                    color = AcOk,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (result != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${result.words.size} words" +
                        (if (result.language != null) " • language: ${result.language}" else ""),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "First words (check them against the audio):",
                    color = AcSoft,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                result.words.take(12).forEach { w ->
                    Text(
                        text = "${CaptionFileParser.formatTime(w.startMs)} → ${CaptionFileParser.formatTime(w.endMs)}   ${w.text}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AcChip(
                        text = "Use as captions",
                        selected = true,
                        modifier = Modifier.weight(1f),
                        onClick = { useAsCaptions(result) }
                    )
                    AcChip(
                        text = "Discard",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            AutoCaptionResultStore.result = null
                            info = null
                        }
                    )
                }
                Text(
                    text = "\"Use as captions\" replaces the imported captions you have now. If the audio is only one of several, the times are shifted by the length of the audios before it.",
                    color = AcSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            containerColor = AcDark,
            title = { Text("Send your audio to Groq?", color = Color.White) },
            text = {
                Text(
                    text = "The audio file \"${chosenAudio?.name ?: ""}\" will be uploaded to Groq (api.groq.com) over the internet to be turned into text. " +
                        "Groq may charge your account after the free limit. Nothing is sent if you tap Cancel.",
                    color = AcSoft
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askConsent = false
                    start()
                }) { Text("Yes, send", color = AcOk) }
            },
            dismissButton = {
                TextButton(onClick = { askConsent = false }) { Text("Cancel", color = AcSoft) }
            }
        )
    }
}

@Composable
private fun acFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = AcBlue,
    unfocusedBorderColor = AcBlue.copy(alpha = 0.5f),
    focusedLabelColor = AcSoft,
    unfocusedLabelColor = AcSoft
)

@Composable
private fun AcChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val chipShape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(chipShape)
            .background(if (selected) AcBlue else AcCard)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else AcBlue.copy(alpha = 0.6f),
                chipShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
