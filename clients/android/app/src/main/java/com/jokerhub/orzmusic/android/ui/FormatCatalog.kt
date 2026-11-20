package com.jokerhub.orzmusic.android.ui

import androidx.compose.ui.graphics.Color
import com.jokerhub.orzmusic.core.model.FormatCount
import com.jokerhub.orzmusic.core.playback.DecoderManifest

/**
 * 网页端侧栏的「格式分组」在 Android 上的对应物。
 *
 * 分组只影响浏览方式，不参与播放策略判断 —— 播放策略仍然只看
 * [DecoderManifest] 与后端 `playStrategy`。新增格式时优先让它落到
 * `other`，不要为了显示把它塞进“已支持”集合。
 */
data class FormatMeta(
    val id: String,
    val label: String,
    val group: String,
    val color: Color,
    val count: Int = 0
)

data class FormatGroup(
    val id: String,
    val label: String,
    val items: List<FormatMeta>
)

object FormatCatalog {

    private val groupColors = mapOf(
        "modules" to Color(0xFF8BE9FD),
        "retro" to Color(0xFFFBBF24),
        "synth" to Color(0xFF39E58C),
        "standard" to Color(0xFFA1A1AA),
        "other" to Color(0xFF94A3B8)
    )

    private val groupLabels = mapOf(
        "modules" to "模块音乐",
        "retro" to "复古主机",
        "synth" to "芯片与合成",
        "standard" to "常规音频",
        "other" to "其他格式"
    )

    private val standardFormats = listOf(
        "mp3" to "MP3", "ogg" to "OGG", "flac" to "FLAC",
        "wav" to "WAV", "m4a" to "M4A", "aac" to "AAC", "opus" to "OPUS"
    )

    private val extraGroups = mapOf(
        "thx" to "modules",
        "nsf" to "retro", "nsfe" to "retro", "spc" to "retro", "sid" to "retro",
        "sap" to "retro", "vgm" to "retro", "vgz" to "retro", "gym" to "retro",
        "ay" to "retro", "gbs" to "retro", "hes" to "retro", "kss" to "retro",
        "sgc" to "retro", "gb" to "retro", "pce" to "retro",
        "rad" to "synth", "hsc" to "synth", "amd" to "synth", "d00" to "synth",
        "v2m" to "synth", "ahx" to "synth", "midi" to "synth"
    )

    private val known: Map<String, FormatMeta> = buildMap {
        DecoderManifest.formats.forEach { format ->
            put(
                format.id,
                FormatMeta(
                    id = format.id,
                    label = format.label,
                    group = format.group,
                    color = groupColors[format.group] ?: groupColors.getValue("other")
                )
            )
        }
        standardFormats.forEach { (id, label) ->
            put(id, FormatMeta(id, label, "standard", groupColors.getValue("standard")))
        }
        extraGroups.forEach { (id, group) ->
            put(id, FormatMeta(id, id.uppercase(), group, groupColors[group] ?: groupColors.getValue("other")))
        }
    }

    fun meta(format: String): FormatMeta {
        val id = format.lowercase()
        return known[id] ?: FormatMeta(
            id = id,
            label = id.uppercase(),
            group = "other",
            color = groupColors.getValue("other")
        )
    }

    fun groups(counts: List<FormatCount>): List<FormatGroup> {
        val items = counts
            .filter { it.count > 0 && it.format.isNotBlank() }
            .map { meta(it.format).copy(count = it.count) }
            .distinctBy { it.id }
        return listOf("modules", "retro", "synth", "standard", "other").map { group ->
            FormatGroup(
                id = group,
                label = groupLabels.getValue(group),
                items = items.filter { it.group == group }
            )
        }.filter { it.items.isNotEmpty() }
    }

    fun groupLabel(id: String): String = groupLabels[id] ?: id
    fun groupColor(id: String): Color = groupColors[id] ?: groupColors.getValue("other")
}
