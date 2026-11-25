package com.kamran.stickcut

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

// ---------------------------------------------------------------------------
// Step 86: keeps the caption settings and the imported captions on the phone.
//
// File: stickcut_captions.json (next to the project file).
// Saved: Caption Studio style, word highlight mode, Animated/Classic choice,
//        and the imported captions (with your edits and the Classic look).
//
// start() is called from ProjectStorage. The first time it runs it brings the
// saved things back, but only for parts you have not changed yet in this
// session. After that it saves every change automatically (0.7 s after it).
// ---------------------------------------------------------------------------

private data class CaptionSnap(
    val style: CaptionStyle,
    val mode: CaptionHighlightMode,
    val studio: Boolean,
    val imported: ImportedCaptionState
)

object CaptionPersistence {

    private const val FILE_NAME = "stickcut_captions.json"
    private const val VERSION = 1

    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val file = File(app.filesDir, FILE_NAME)

        restore(file)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            snapshotFlow {
                CaptionSnap(
                    CaptionStore.style,
                    CaptionHighlightStore.mode,
                    ImportedCaptionMode.useStudio,
                    ImportedCaptionStore.state
                )
            }.collectLatest { snap ->
                delay(700L)
                write(file, snap)
            }
        }
    }

    // ----- writing -----

    private fun styleToJson(s: CaptionStyle): JSONObject {
        val o = JSONObject()
        o.put("enabled", s.enabled)
        o.put("font", s.font.name)
        if (s.customFontPath != null) o.put("customFontPath", s.customFontPath)
        o.put("sizePercent", s.sizePercent.toDouble())
        o.put("textColor", s.textColor)
        o.put("outlineColor", s.outlineColor)
        o.put("outlineWidth", s.outlineWidth.toDouble())
        o.put("background", s.background)
        o.put("backgroundColor", s.backgroundColor)
        o.put("highlightColor", s.highlightColor)
        o.put("position", s.position.name)
        o.put("animation", s.animation.name)
        o.put("maxWords", s.maxWords)
        o.put("maxChars", s.maxChars)
        o.put("uppercase", s.uppercase)
        return o
    }

    private fun srtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val h = t / 3_600_000L
        val m = (t / 60_000L) % 60L
        val s = (t / 1000L) % 60L
        val milli = t % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    // The captions are stored as normal SRT text, so they come back through
    // the same reader that imported them the first time.
    private fun toSrt(cues: List<CaptionCue>): String {
        val blankLines = Regex("\\n\\s*\\n")
        val sb = StringBuilder()
        var number = 1
        for (cue in cues.sortedBy { it.startMs }) {
            val text = cue.text.replace(blankLines, "\n").trim()
            if (text.isEmpty()) continue
            sb.append(number).append('\n')
            sb.append(srtTime(cue.startMs)).append(" --> ").append(srtTime(cue.endMs)).append('\n')
            sb.append(text).append("\n\n")
            number++
        }
        return sb.toString()
    }

    private fun write(file: File, snap: CaptionSnap) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("style", styleToJson(snap.style))
            root.put("highlightMode", snap.mode.name)
            root.put("useStudio", snap.studio)

            val st = snap.imported
            if (st.cues.isNotEmpty()) {
                val imp = JSONObject()
                imp.put("fileName", st.fileName ?: "captions")
                imp.put("enabled", st.enabled)
                imp.put("look", st.look.name)
                imp.put("size", st.size.name)
                val scale = st.customScale
                if (scale != null) imp.put("customScale", scale.toDouble())
                imp.put("lineSpacing", CaptionLayoutMath.lineSpacing(st).toDouble())
                imp.put("cueCount", st.cues.size)
                imp.put("srt", toSrt(st.cues))
                root.put("imported", imp)
            }

            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            CaptionTemplateStore.message = "Could not save the caption settings: ${e.message}"
        }
    }

    // ----- reading -----

    private fun <T : Enum<T>> enumOr(value: String?, all: Array<T>, fallback: T): T {
        if (value == null) return fallback
        return all.firstOrNull { it.name == value } ?: fallback
    }

    private fun styleFromJson(o: JSONObject): CaptionStyle {
        val d = CaptionStyle()
        val fontPath = o.optString("customFontPath", "")
        val pathOk = fontPath.isNotEmpty() && File(fontPath).exists()
        var font = enumOr(o.optString("font", ""), CaptionFont.values(), d.font)
        if (font == CaptionFont.CUSTOM && !pathOk) font = CaptionFont.SANS_BOLD
        return d.copy(
            enabled = o.optBoolean("enabled", d.enabled),
            font = font,
            customFontPath = if (pathOk) fontPath else null,
            sizePercent = o.optDouble("sizePercent", d.sizePercent.toDouble()).toFloat(),
            textColor = o.optLong("textColor", d.textColor),
            outlineColor = o.optLong("outlineColor", d.outlineColor),
            outlineWidth = o.optDouble("outlineWidth", d.outlineWidth.toDouble()).toFloat(),
            background = o.optBoolean("background", d.background),
            backgroundColor = o.optLong("backgroundColor", d.backgroundColor),
            highlightColor = o.optLong("highlightColor", d.highlightColor),
            position = enumOr(o.optString("position", ""), CaptionPosition.values(), d.position),
            animation = enumOr(o.optString("animation", ""), CaptionAnimation.values(), d.animation),
            maxWords = o.optInt("maxWords", d.maxWords),
            maxChars = o.optInt("maxChars", d.maxChars),
            uppercase = o.optBoolean("uppercase", d.uppercase)
        ).normalized()
    }

    private fun restore(file: File) {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", 0) != VERSION) return

            // Only bring the look back if it is still untouched in this session.
            val lookUntouched = CaptionStore.style == CaptionStyle() &&
                CaptionHighlightStore.mode == CaptionHighlightMode.OFF &&
                ImportedCaptionMode.useStudio
            if (lookUntouched) {
                val styleJson = root.optJSONObject("style")
                if (styleJson != null) CaptionStore.style = styleFromJson(styleJson)
                CaptionHighlightStore.mode = enumOr(
                    root.optString("highlightMode", ""),
                    CaptionHighlightMode.values(),
                    CaptionHighlightMode.OFF
                )
                ImportedCaptionMode.useStudio = root.optBoolean("useStudio", true)
            }

            // Only bring the imported captions back if none are loaded now.
            val imp = root.optJSONObject("imported")
            if (imp != null && ImportedCaptionStore.state.cues.isEmpty()) {
                restoreImported(imp)
            }
        } catch (e: Exception) {
            CaptionTemplateStore.message = "Could not bring back the saved captions: ${e.message}"
        }
    }

    private fun restoreImported(imp: JSONObject) {
        val parsed = CaptionFileParser.parse(imp.optString("srt", ""), null)
        if (parsed.cues.isEmpty()) {
            CaptionTemplateStore.message = "The saved captions could not be read, so they were not restored."
            return
        }
        ImportedCaptionStore.load(imp.optString("fileName", "captions"), parsed.cues)
        ImportedCaptionNotes.format = parsed.format

        val look = CaptionLook.values().firstOrNull { it.name == imp.optString("look", "") }
        if (look != null) ImportedCaptionStore.setLook(look)
        val size = CaptionSizeChoice.values().firstOrNull { it.name == imp.optString("size", "") }
        if (size != null) ImportedCaptionStore.setSize(size)
        if (!imp.isNull("customScale")) {
            ImportedCaptionStore.setCustomScale(imp.getDouble("customScale").toFloat())
        }
        if (imp.has("lineSpacing")) {
            ImportedCaptionStore.setLineSpacing(imp.getDouble("lineSpacing").toFloat())
        }
        ImportedCaptionStore.setEnabled(imp.optBoolean("enabled", true))

        val saved = imp.optInt("cueCount", parsed.cues.size)
        if (saved != parsed.cues.size) {
            CaptionTemplateStore.message =
                "Brought back ${parsed.cues.size} of $saved saved captions. Please check the list."
        }
    }
}
