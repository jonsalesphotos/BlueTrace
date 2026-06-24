package io.bluetrace.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneCatalogTest {

    // 结构对齐 Docs/architecture/scenes.json（含 comment 等未知键，应被忽略）
    private val json = """
        {
          "version": 1,
          "comment": "采集场景配置",
          "autoDefaultUserSubs": ["Unwear", "Desktop", "Pocket"],
          "scenes": [
            { "token": "HR", "zh": "心率", "en": "HeartRate", "subs": [
              {"token":"Static","zh":"静止","en":"Static"},
              {"token":"OutdoorRun","zh":"户外跑","en":"OutdoorRun"}
            ]},
            { "token": "Wear", "zh": "佩戴", "en": "Wear", "subs": [
              {"token":"Wearing","zh":"佩戴中","en":"Wearing"},
              {"token":"Unwear","zh":"未佩戴","en":"Unwear"}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun parses_scenes_ignoringUnknownKeys() {
        val cat = parseSceneCatalog(json)
        assertEquals(2, cat.scenes.size)
        assertEquals("心率", cat.scene("HR")?.zh)
        assertEquals("佩戴中", cat.sub("Wear", "Wearing")?.zh)
        assertEquals("户外跑", cat.sub("HR", "OutdoorRun")?.zh)
    }

    @Test
    fun firstSelection_isFirstSceneFirstSub() {
        // 默认 = JSON 第一个主场景的第一个子场景（用户裁决）
        assertEquals(SceneSelection("HR", "Static"), parseSceneCatalog(json).firstSelection)
    }

    @Test
    fun autoDefaultUserSubs_drivesDefaultUserSwitch() {
        val cat = parseSceneCatalog(json)
        assertTrue(cat.isAutoDefaultUser("Unwear"))
        assertTrue(cat.isAutoDefaultUser("Desktop"))
        assertFalse(cat.isAutoDefaultUser("Wearing"))
        assertFalse(cat.isAutoDefaultUser("OutdoorRun"))
    }

    @Test
    fun malformedJson_fallsBackToEmptyCatalog() {
        val cat = parseSceneCatalog("not json at all")
        assertEquals(SceneCatalog.EMPTY, cat)
        // 兜底至少含默认 Wear/Wearing，UI 不崩
        assertEquals("佩戴中", cat.sub("Wear", "Wearing")?.zh)
    }
}
