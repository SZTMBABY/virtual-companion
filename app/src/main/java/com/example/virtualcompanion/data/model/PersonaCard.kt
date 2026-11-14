package com.example.virtualcompanion.data.model

import org.json.JSONObject

/**
 * 主人（用户）的角色卡。与 [CharacterCard] 一起构成提示词的两层结构。
 *
 * 之所以只有两层而没有世界书：见 docs/可行性方案.md 决策 Q2=Q3 轮 B。
 */
data class PersonaCard(
    /** 角色怎么称呼用户。留空时回落 "主人"。 */
    val name: String = "主人",
    /** 用户希望被叫的昵称，优先于 [name] 使用。 */
    val nickname: String = "",
    /** 关于用户本人的事实：作息、工作、喜好等。会原样进系统提示。 */
    val background: String = "",
    /** 用户明确表达过的偏好/雷点，优先级高于 [background]。 */
    val preferences: String = "",
) {
    /** 提示词里实际使用的称呼。 */
    val displayName: String get() = nickname.ifBlank { name.ifBlank { "主人" } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("nickname", nickname)
        put("background", background)
        put("preferences", preferences)
    }

    companion object {
        fun fromJson(o: JSONObject?): PersonaCard {
            if (o == null) return PersonaCard()
            return PersonaCard(
                name = o.optString("name", "主人"),
                nickname = o.optString("nickname", ""),
                background = o.optString("background", ""),
                preferences = o.optString("preferences", ""),
            )
        }
    }
}
