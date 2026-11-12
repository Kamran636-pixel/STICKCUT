package com.kamran.stickcut

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Everything needed to bring a finished Auto Edit back after the app was closed.
data class SavedProject(
    val scenes: List<Scene>,
    val audios: List<ImportedAudio>,
    val settings: AnimationSettings,
    val totalMs: Long,
    val savedAtMs: Long,
    // Names of image or voiceover files that can no longer be opened.
    val missingFiles: List<String>
)

object ProjectStorage {

    private const val FILE_NAME = "stickcut_project.json"
    private const val TEMP_NAME = "stickcut_project.tmp"
    private const val FORMAT_VERSION = 1

    private fun projectFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = projectFile(context).exists()

    fun clear(context: Context) {
        try {
            projectFile(context).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T {
        if (name == null) return fallback
        return try {
            enumValueOf<T>(name)
        } catch (e: Exception) {
            fallback
        }
    }

    private fun configToJson(config: AnimationConfig): JSONObject {
        return JSONObject()
            .put("type", config.type.name)
            .put("durationMs", config.durationMs)
            .put("speed", config.speed.name)
            .put("intensity", config.intensity.toDouble())
    }

    private fun configFromJson(json: JSONObject): AnimationConfig {
        val base = AnimationConfig()
        return AnimationConfig(
            type = enumOr(json.optString("type", ""), base.type),
            durationMs = json.optLong("durationMs", base.durationMs),
            speed = enumOr(json.optString("speed", ""), base.speed),
            intensity = json.optDouble("intensity", base.intensity.toDouble()).toFloat()
        ).normalized()
    }

    private fun settingsToJson(settings: AnimationSettings): JSONObject {
        val perScene = JSONArray()
        settings.perScene.forEach { (sceneIndex, config) ->
            perScene.put(
                JSONObject()
                    .put("scene", sceneIndex)
                    .put("config", configToJson(config))
            )
        }
        val pool = JSONArray()
        settings.randomPool.forEach { pool.put(it.name) }
        return JSONObject()
            .put("mode", settings.mode.name)
            .put("global", configToJson(settings.global))
            .put("perScene", perScene)
            .put("transitionType", settings.transition.type.name)
            .put("transitionMs", settings.transition.durationMs)
            .put("randomSeed", settings.randomSeed)
            .put("randomPool", pool)
    }

    private fun settingsFromJson(json: JSONObject): AnimationSettings {
        val base = AnimationSettings()
        val perScene = mutableMapOf<Int, AnimationConfig>()
        val perSceneArray = json.optJSONArray("perScene")
        if (perSceneArray != null) {
            for (i in 0 until perSceneArray.length()) {
                val item = perSceneArray.optJSONObject(i) ?: continue
                val configJson = item.optJSONObject("config") ?: continue
                perScene[item.getInt("scene")] = configFromJson(configJson)
            }
        }
        val pool = mutableSetOf<AnimationType>()
        val poolArray = json.optJSONArray("randomPool")
        if (poolArray != null) {
            for (i in 0 until poolArray.length()) {
                val type = enumOr(poolArray.optString(i, ""), AnimationType.NONE)
                if (type != AnimationType.NONE) pool.add(type)
            }
        }
        val globalJson = json.optJSONObject("global")
        return AnimationSettings(
            mode = enumOr(json.optString("mode", ""), base.mode),
            global = if (globalJson != null) configFromJson(globalJson) else base.global,
            perScene = perScene,
            transition = TransitionConfig(
                type = enumOr(json.optString("transitionType", ""), base.transition.type),
                durationMs = json.optLong("transitionMs", base.transition.durationMs)
            ).normalized(),
            randomSeed = json.optLong("randomSeed", base.randomSeed),
            randomPool = if (pool.isEmpty()) base.randomPool else pool
        )
    }

    // Returns true only when the file was really written.
    fun save(
        context: Context,
        result: SceneBuildResult,
        audios: List<ImportedAudio>,
        settings: AnimationSettings
    ): Boolean {
        if (result.scenes.isEmpty()) return false
        return try {
            val scenes = JSONArray()
            result.scenes.forEach { scene ->
                scenes.put(
                    JSONObject()
                        .put("index", scene.index)
                        .put("imageNumber", scene.imageNumber)
                        .put("imageUri", scene.imageUri.toString())
                        .put("imageName", scene.imageName)
                        .put("startMs", scene.startMs)
                        .put("endMs", scene.endMs)
                        .put("text", scene.text)
                        .put("partPosition", scene.partPosition)
                )
            }
            val audioArray = JSONArray()
            audios.forEach { audio ->
                audioArray.put(
                    JSONObject()
                        .put("uri", audio.uri.toString())
                        .put("name", audio.name)
                        .put("durationMs", audio.durationMs)
                )
            }
            val root = JSONObject()
                .put("version", FORMAT_VERSION)
                .put("savedAtMs", System.currentTimeMillis())
                .put("totalMs", result.totalMs)
                .put("scenes", scenes)
                .put("audios", audioArray)
                .put("settings", settingsToJson(settings))

            val temp = File(context.filesDir, TEMP_NAME)
            temp.writeText(root.toString(), Charsets.UTF_8)
            val target = projectFile(context)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    private fun canOpen(context: Context, uri: Uri): Boolean {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                false
            } else {
                stream.close()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // Reads the saved project. Returns null when nothing is saved or the file is damaged.
    // Call this away from the main thread because it opens every file once to check it.
    fun load(context: Context): SavedProject? {
        val file = projectFile(context)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", 0) != FORMAT_VERSION) return null

            val scenes = mutableListOf<Scene>()
            val sceneArray = root.getJSONArray("scenes")
            for (i in 0 until sceneArray.length()) {
                val s = sceneArray.getJSONObject(i)
                scenes.add(
                    Scene(
                        index = s.getInt("index"),
                        imageNumber = s.getLong("imageNumber"),
                        imageUri = Uri.parse(s.getString("imageUri")),
                        imageName = s.getString("imageName"),
                        startMs = s.getLong("startMs"),
                        endMs = s.getLong("endMs"),
                        text = s.optString("text", ""),
                        partPosition = s.getInt("partPosition")
                    )
                )
            }
            if (scenes.isEmpty()) return null

            val audios = mutableListOf<ImportedAudio>()
            val audioArray = root.optJSONArray("audios")
            if (audioArray != null) {
                for (i in 0 until audioArray.length()) {
                    val a = audioArray.getJSONObject(i)
                    audios.add(
                        ImportedAudio(
                            uri = Uri.parse(a.getString("uri")),
                            name = a.getString("name"),
                            durationMs = a.getLong("durationMs")
                        )
                    )
                }
            }

            val settingsJson = root.optJSONObject("settings")
            val settings = if (settingsJson != null) settingsFromJson(settingsJson) else AnimationSettings()

            val missing = mutableListOf<String>()
            val checkedImages = mutableSetOf<String>()
            scenes.forEach { scene ->
                val key = scene.imageUri.toString()
                if (checkedImages.add(key) && !canOpen(context, scene.imageUri)) {
                    missing.add(scene.imageName)
                }
            }
            audios.forEach { audio ->
                if (!canOpen(context, audio.uri)) missing.add(audio.name)
            }

            SavedProject(
                scenes = scenes,
                audios = audios,
                settings = settings,
                totalMs = root.optLong("totalMs", scenes.last().endMs),
                savedAtMs = root.optLong("savedAtMs", 0L),
                missingFiles = missing
            )
        } catch (e: Exception) {
            null
        }
    }
}
