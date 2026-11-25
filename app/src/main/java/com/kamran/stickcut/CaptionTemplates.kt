package com.kamran.stickcut

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------------------
// Step 84 / 87: caption template library (data-driven).
// A template is plain data. The list of templates is in CaptionTemplateData.kt.
// Templates only use the built-in fonts, never your uploaded font.
// ---------------------------------------------------------------------------

data class CaptionTemplate(
    val id: String,
    val name: String,
    val category: String,
    val font: CaptionFont = CaptionFont.SANS_BOLD,
    val sizePercent: Float = 6f,
    val textColor: Long = 0xFFFFFFFFL,
    val outlineColor: Long = 0xFF000000L,
    val outlineWidth: Float = 0.12f,
    val background: Boolean = false,
    val backgroundColor: Long = 0x99000000L,
    val highlightColor: Long = 0xFFFFD400L,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val animation: CaptionAnimation = CaptionAnimation.NONE,
    val maxWords: Int = 3,
    val maxChars: Int = 24,
    val uppercase: Boolean = false,
    val highlight: CaptionHighlightMode = CaptionHighlightMode.OFF,
    val builtIn: Boolean = true
) {
    // Keeps "enabled" and the uploaded font path of the current style.
    fun applyTo(base: CaptionStyle): CaptionStyle = base.copy(
        font = font,
        sizePercent = sizePercent,
        textColor = textColor,
        outlineColor = outlineColor,
        outlineWidth = outlineWidth,
        background = background,
        backgroundColor = backgroundColor,
        highlightColor = highlightColor,
        position = position,
        animation = animation,
        maxWords = maxWords,
        maxChars = maxChars,
        uppercase = uppercase
    )

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("category", category)
        o.put("font", font.name)
        o.put("sizePercent", sizePercent.toDouble())
        o.put("textColor", textColor)
        o.put("outlineColor", outlineColor)
        o.put("outlineWidth", outlineWidth.toDouble())
        o.put("background", background)
        o.put("backgroundColor", backgroundColor)
        o.put("highlightColor", highlightColor)
        o.put("position", position.name)
        o.put("animation", animation.name)
        o.put("maxWords", maxWords)
        o.put("maxChars", maxChars)
        o.put("uppercase", uppercase)
        o.put("highlight", highlight.name)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): CaptionTemplate? {
            val id = o.optString("id", "")
            val name = o.optString("name", "")
            if (id.isEmpty() || name.isEmpty()) return null
            val d = CaptionTemplate(id = id, name = name, category = "My templates", builtIn = false)
            val fontValue = runCatching { CaptionFont.valueOf(o.optString("font")) }.getOrDefault(d.font)
            return d.copy(
                category = o.optString("category", "My templates").ifEmpty { "My templates" },
                font = if (fontValue == CaptionFont.CUSTOM) CaptionFont.SANS_BOLD else fontValue,
                sizePercent = o.optDouble("sizePercent", d.sizePercent.toDouble()).toFloat()
                    .coerceIn(CaptionStyle.MIN_SIZE, CaptionStyle.MAX_SIZE),
                textColor = o.optLong("textColor", d.textColor),
                outlineColor = o.optLong("outlineColor", d.outlineColor),
                outlineWidth = o.optDouble("outlineWidth", d.outlineWidth.toDouble()).toFloat()
                    .coerceIn(0f, 0.3f),
                background = o.optBoolean("background", d.background),
                backgroundColor = o.optLong("backgroundColor", d.backgroundColor),
                highlightColor = o.optLong("highlightColor", d.highlightColor),
                position = runCatching { CaptionPosition.valueOf(o.optString("position")) }
                    .getOrDefault(d.position),
                animation = runCatching { CaptionAnimation.valueOf(o.optString("animation")) }
                    .getOrDefault(d.animation),
                maxWords = o.optInt("maxWords", d.maxWords).coerceIn(1, 8),
                maxChars = o.optInt("maxChars", d.maxChars).coerceIn(8, 60),
                uppercase = o.optBoolean("uppercase", d.uppercase),
                highlight = runCatching { CaptionHighlightMode.valueOf(o.optString("highlight")) }
                    .getOrDefault(d.highlight)
            )
        }

        fun fromCurrent(
            id: String,
            name: String,
            style: CaptionStyle,
            highlight: CaptionHighlightMode
        ): CaptionTemplate = CaptionTemplate(
            id = id,
            name = name,
            category = "My templates",
            font = if (style.font == CaptionFont.CUSTOM) CaptionFont.SANS_BOLD else style.font,
            sizePercent = style.sizePercent,
            textColor = style.textColor,
            outlineColor = style.outlineColor,
            outlineWidth = style.outlineWidth,
            background = style.background,
            backgroundColor = style.backgroundColor,
            highlightColor = style.highlightColor,
            position = style.position,
            animation = style.animation,
            maxWords = style.maxWords,
            maxChars = style.maxChars,
            uppercase = style.uppercase,
            highlight = highlight,
            builtIn = false
        )
    }
}

// Keeps your own templates, favourites and recent list in one small JSON file.
object CaptionTemplateStore {
    var custom by mutableStateOf<List<CaptionTemplate>>(emptyList())
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recents by mutableStateOf<List<String>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)

    private var loaded = false
    private var file: File? = null

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        file = File(context.filesDir, "caption_templates.json")
        load()
    }

    fun allTemplates(): List<CaptionTemplate> = CaptionTemplates.builtIn + custom

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val list = ArrayList<CaptionTemplate>()
            val arr = root.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until arr.length()) {
                CaptionTemplate.fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
            custom = list
            val fav = HashSet<String>()
            val favArr = root.optJSONArray("favorites") ?: JSONArray()
            for (i in 0 until favArr.length()) fav.add(favArr.getString(i))
            favorites = fav
            val rec = ArrayList<String>()
            val recArr = root.optJSONArray("recents") ?: JSONArray()
            for (i in 0 until recArr.length()) rec.add(recArr.getString(i))
            recents = rec
        } catch (e: Exception) {
            message = "Could not read your saved templates (${e.message}). Starting empty."
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val root = JSONObject()
            root.put("version", 1)
            val arr = JSONArray()
            custom.forEach { arr.put(it.toJson()) }
            root.put("custom", arr)
            root.put("favorites", JSONArray(favorites.toList()))
            root.put("recents", JSONArray(recents))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            message = "Could not save templates: ${e.message}"
        }
    }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        save()
    }

    fun markUsed(id: String) {
        recents = (listOf(id) + recents.filter { it != id }).take(12)
        save()
    }

    fun addCustom(name: String, style: CaptionStyle, highlight: CaptionHighlightMode) {
        val clean = name.trim().take(30)
        if (clean.isEmpty()) {
            message = "Please type a name for the template."
            return
        }
        val id = "my_" + System.currentTimeMillis()
        custom = custom + CaptionTemplate.fromCurrent(id, clean, style, highlight)
        if (style.font == CaptionFont.CUSTOM) {
            message = "Saved. Your uploaded font is not stored in templates, so the template uses the normal bold font."
        } else {
            message = null
        }
        save()
    }

    fun deleteCustom(id: String) {
        custom = custom.filter { it.id != id }
        favorites = favorites - id
        recents = recents.filter { it != id }
        save()
    }
}

// Undo / Redo for the caption style and the highlight mode.
// Changes made less than 0.4 s apart (a slider drag) count as one change.
object CaptionHistory {
    private class Snap(val style: CaptionStyle, val mode: CaptionHighlightMode)

    private val undoList = ArrayList<Snap>()
    private val redoList = ArrayList<Snap>()
    private var lastPush = 0L

    var undoCount by mutableStateOf(0)
        private set
    var redoCount by mutableStateOf(0)
        private set

    private fun sync() {
        undoCount = undoList.size
        redoCount = redoList.size
    }

    private fun current() = Snap(CaptionStore.style, CaptionHighlightStore.mode)

    // Call this just BEFORE changing the style.
    fun record() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPush >= 400L || undoList.isEmpty()) {
            undoList.add(current())
            if (undoList.size > 60) undoList.removeAt(0)
        }
        lastPush = now
        redoList.clear()
        sync()
    }

    fun undo() {
        if (undoList.isEmpty()) return
        redoList.add(current())
        val s = undoList.removeAt(undoList.size - 1)
        CaptionStore.style = s.style
        CaptionHighlightStore.mode = s.mode
        lastPush = 0L
        sync()
    }

    fun redo() {
        if (redoList.isEmpty()) return
        undoList.add(current())
        val s = redoList.removeAt(redoList.size - 1)
        CaptionStore.style = s.style
        CaptionHighlightStore.mode = s.mode
        lastPush = 0L
        sync()
    }
}

private val TpBlue = Color(0xFF2D8CFF)
private val TpCard = Color(0xFF0F2F70)
private val TpWarn = Color(0xFFFFC857)
private val TpSoft = Color(0xFFB8D4FF)
private const val TP_ALL = "All"
private const val TP_FAV = "Favorites"
private const val TP_RECENT = "Recent"
private const val TP_MINE = "My templates"
private const val TP_PAGE = 12

@Composable
fun CaptionTemplatePanel() {
    val context = LocalContext.current
    remember { CaptionTemplateStore.init(context); true }

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(TP_ALL) }
    var showSave by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var appliedId by remember { mutableStateOf<String?>(null) }
    var limit by remember(category, query) { mutableStateOf(TP_PAGE) }

    val favorites = CaptionTemplateStore.favorites
    val custom = CaptionTemplateStore.custom
    val recents = CaptionTemplateStore.recents
    val all = CaptionTemplates.builtIn + custom

    val base = when (category) {
        TP_ALL -> all
        TP_FAV -> all.filter { it.id in favorites }
        TP_RECENT -> recents.mapNotNull { id -> all.firstOrNull { it.id == id } }
        TP_MINE -> custom
        else -> all.filter { it.category == category }
    }
    val q = query.trim()
    val list = if (q.isEmpty()) {
        base
    } else {
        base.filter { it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true) }
    }

    fun apply(t: CaptionTemplate) {
        CaptionHistory.record()
        CaptionStore.style = t.applyTo(CaptionStore.style).normalized()
        CaptionHighlightStore.mode = t.highlight
        ImportedCaptionMode.useStudio = true
        CaptionTemplateStore.markUsed(t.id)
        appliedId = t.id
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Templates (${all.size})",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search templates") },
            colors = tpFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val names = listOf(TP_ALL, TP_FAV, TP_RECENT) + CaptionTemplates.categories + TP_MINE
            names.forEach { name ->
                TpChip(
                    text = name,
                    selected = name == category,
                    onClick = { category = name }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (list.isEmpty()) {
            Text(
                text = when (category) {
                    TP_FAV -> "No favourites yet. Tap ☆ on a template."
                    TP_RECENT -> "No recent templates yet. Tap a template to use it."
                    TP_MINE -> "No saved templates yet. Use \"Save current look\" below."
                    else -> "No template matches your search."
                },
                color = TpSoft,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        list.take(limit).chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { t ->
                    TemplateCard(
                        template = t,
                        isFavorite = t.id in favorites,
                        isApplied = t.id == appliedId,
                        modifier = Modifier.weight(1f),
                        onApply = { apply(t) },
                        onFavorite = { CaptionTemplateStore.toggleFavorite(t.id) },
                        onDelete = if (t.builtIn) null else ({ CaptionTemplateStore.deleteCustom(t.id) })
                    )
                }
                if (row.size < 2) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (list.size > limit) {
            TpChip(
                text = "Show more (${list.size - limit} more)",
                selected = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = { limit += TP_PAGE }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TpChip(
                text = "💾 Save current look",
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    nameInput = ""
                    showSave = true
                }
            )
            TpChip(
                text = "Reset style",
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    CaptionHistory.record()
                    val old = CaptionStore.style
                    CaptionStore.style = CaptionStyle().copy(
                        enabled = old.enabled,
                        customFontPath = old.customFontPath
                    )
                    CaptionHighlightStore.mode = CaptionHighlightMode.OFF
                    appliedId = null
                }
            )
        }

        val message = CaptionTemplateStore.message
        if (message != null) {
            Text(
                text = message,
                color = TpWarn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            text = "A template changes the whole caption style (all captions). Choosing one replaces your uploaded font with its own built-in font; pick your font again afterwards if you want it.",
            color = TpSoft,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            containerColor = Color(0xFF0A1A44),
            title = { Text("Save current look", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(30) },
                    singleLine = true,
                    label = { Text("Template name") },
                    colors = tpFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    CaptionTemplateStore.addCustom(
                        nameInput,
                        CaptionStore.style,
                        CaptionHighlightStore.mode
                    )
                    showSave = false
                    category = TP_MINE
                }) { Text("Save", color = Color(0xFF6BE3A0)) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("Cancel", color = TpSoft) }
            }
        )
    }
}

@Composable
private fun tpFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = TpBlue,
    unfocusedBorderColor = TpBlue.copy(alpha = 0.5f),
    focusedLabelColor = TpSoft,
    unfocusedLabelColor = TpSoft
)

@Composable
private fun TpChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) TpBlue else TpCard)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else TpBlue.copy(alpha = 0.6f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
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

private val sampleWords = listOf("এই", "হলো", "নমুনা")
private val sampleChunk = CaptionChunk(
    sceneIndex = 0,
    startMs = 0L,
    endMs = 1500L,
    text = sampleWords.joinToString(" "),
    words = sampleWords
)

@Composable
private fun TemplateCard(
    template: CaptionTemplate,
    isFavorite: Boolean,
    isApplied: Boolean,
    modifier: Modifier,
    onApply: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val shape = RoundedCornerShape(12.dp)
    // The sample box is small, so the letters are drawn larger than in the real video.
    val thumbStyle = template.applyTo(CaptionStyle()).copy(
        enabled = true,
        sizePercent = template.sizePercent * 2.2f
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(TpCard)
            .border(
                if (isApplied) 2.dp else 1.dp,
                if (isApplied) Color(0xFF6BE3A0) else TpBlue.copy(alpha = 0.6f),
                shape
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF2B3A5C))
                .clickable(onClick = onApply)
        ) {
            CaptionOverlay(
                chunk = sampleChunk,
                style = thumbStyle,
                localMs = 700L,
                modifier = Modifier.fillMaxSize(),
                highlightMode = template.highlight
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = template.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onApply)
            )
            Text(
                text = if (isFavorite) "★" else "☆",
                color = if (isFavorite) Color(0xFFFFD400) else Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable(onClick = onFavorite)
                    .padding(horizontal = 4.dp)
            )
            if (onDelete != null) {
                Text(
                    text = "🗑",
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}
