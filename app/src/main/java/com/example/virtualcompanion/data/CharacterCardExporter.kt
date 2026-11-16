package com.example.virtualcompanion.data

import com.example.virtualcompanion.data.model.CharacterCard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把当前角色卡导出成社区标准格式（SillyTavern Character Card V2）。
 *
 * 为什么导 V2 而不是 V3：V3 多出来的东西（内嵌资产、多语言备注、Lorebook）
 * 我们一样都没有，写了也是空壳；而 V2 是酒馆里兼容面最广的一档。
 *
 * 为什么 `systemPromptExtra` 拼到 `description` 末尾而不是写进 `system_prompt`：
 * 酒馆的 `system_prompt` 语义是「整段替换掉它自己的系统提示词」，
 * 我们的补充要求一旦写进去，会在酒馆里顶掉酒馆的框架，行为完全变样。
 * 而 `description` 是酒馆一定会塞进提示词的部分，放这里语义不变、也不冲突。
 *
 * 我们独有的三项（唤醒词、音色、Live2D 绑定）没有标准位置，
 * 按 V2 规范放进 `extensions`（规范明说扩展字段不应被销毁），
 * 命名空间用 `virtual_companion`，避免和别人的键撞车。
 */
object CharacterCardExporter {

    /** 扩展字段的命名空间。改这个值会让旧导出文件的私有字段读不回来。 */
    const val EXT_NS = "virtual_companion"

    /** 拼在 description 末尾的小标题，用它隔开「人设」与「补充要求」。 */
    private const val EXTRA_HEADING = "\n\n【补充要求】\n"

    /**
     * 生成 V2 卡的 JSON 文本。
     *
     * 用 2 空格缩进：用户可能自己打开看，或者发给别人；
     * 压缩成一行虽然小几百字节，但没法看也没法 diff。
     */
    fun toV2Json(card: CharacterCard): String {
        val data = JSONObject().apply {
            put("name", card.name.ifBlank { "未命名角色" })
            put("description", mergeDescription(card))
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.exampleDialogue)

            // 规范里的必填项，我们不用但要写全，否则酒馆那边可能报缺字段。
            put("creator_notes", "")
            put("system_prompt", "")
            put("post_history_instructions", "")
            put("alternate_greetings", JSONArray())
            put("character_book", JSONObject.NULL)
            put("tags", JSONArray())
            put("creator", "")
            put("character_version", "1.0")

            put(
                "extensions",
                JSONObject().put(
                    EXT_NS,
                    JSONObject().apply {
                        put("wakeWord", card.wakeWord)
                        put("ttsVoiceId", card.ttsVoiceId)
                        put("live2dModelEntry", card.live2dModelEntry)
                    },
                ),
            )
        }
        val root = JSONObject().apply {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", data)
        }
        // 用户名只在导出时替换 {{user}}：酒馆导入后由它自己替换，
        // 但别人直接看 JSON 时能读懂写的是谁。
        return root.toString(2)
    }

    /**
     * 建议的文件名。Android 的 SAF 会自己处理重名，这里只要保证：
     * 合法、能看出是谁、以 .json 结尾。
     */
    fun suggestedFileName(card: CharacterCard): String {
        val safe = card.name.ifBlank { "character" }
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim()
            .trimEnd('.')
            .take(40)
            .ifBlank { "character" }
        return "$safe.json"
    }

    /** 人设正文 + 补充要求。两者都空时给一句兜底，别导出空 description。 */
    private fun mergeDescription(card: CharacterCard): String {
        val body = card.description.trim()
        val extra = card.systemPromptExtra.trim()
        return when {
            body.isEmpty() && extra.isEmpty() -> "（这个角色还没有写设定）"
            extra.isEmpty() -> body
            body.isEmpty() -> extra
            else -> body + EXTRA_HEADING + extra
        }
    }
}
