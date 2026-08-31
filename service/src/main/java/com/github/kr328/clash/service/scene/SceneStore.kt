package com.github.kr328.clash.service.scene

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.Scene
import com.github.kr328.clash.service.model.SceneTemplates
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SceneStore(context: Context) {
    private val store = ServiceStore(context)
    private var decodeFailureLogged = false

    var scenes: List<Scene>
        get() = decode(store.scenesJson)
        set(value) {
            store.scenesJson = JSON.encodeToString(
                ListSerializer(Scene.serializer()),
                normalizeInOrder(value),
            )
        }

    @Synchronized
    fun addMissingTemplates(): List<Scene> {
        val current = scenes
        val existing = current.mapTo(mutableSetOf()) { it.id }
        val updated = current + SceneTemplates.defaults().filterNot { it.id in existing }
        scenes = updated
        return scenes
    }

    @Synchronized
    fun update(scene: Scene) {
        scenes = scenes.map { if (it.id == scene.id) scene else it }
    }

    @Synchronized
    fun move(id: String, offset: Int) {
        if (offset == 0) return

        val current = scenes.toMutableList()
        val from = current.indexOfFirst { it.id == id }
        if (from < 0) return
        val target = (from + offset).coerceIn(current.indices)
        if (from == target) return

        val item = current.removeAt(from)
        current.add(target, item)
        scenes = current
    }

    private fun decode(raw: String): List<Scene> {
        if (raw.isBlank()) return SceneTemplates.defaults()

        return runCatching {
            JSON.decodeFromString(ListSerializer(Scene.serializer()), raw)
        }.onFailure {
            if (!decodeFailureLogged) {
                decodeFailureLogged = true
                Log.w("SceneStore decode failed; using disabled templates", it)
            }
        }.getOrElse {
            SceneTemplates.defaults()
        }.sortedBy { it.priority }
            .let(::normalizeInOrder)
    }

    private fun normalizeInOrder(value: List<Scene>): List<Scene> {
        return value.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .mapIndexed { index, scene -> scene.copy(priority = index) }
            .toList()
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // An explicit null on a non-nullable field would otherwise throw and drop the whole
            // scene list back to templates, losing every user-authored scene at once.
            coerceInputValues = true
        }
    }
}
