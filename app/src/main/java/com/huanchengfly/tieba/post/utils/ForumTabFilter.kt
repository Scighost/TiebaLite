package com.huanchengfly.tieba.post.utils

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.getStringSet

/**
 * 吧内分区（吧 tab）屏蔽配置的读写工具。
 *
 * 每个吧单独保存一份被屏蔽分区的 tabId 集合，存储于 DataStore，
 * key 为 "${forumName}_blocked_tabs"。仅用于吧内“最新”列表的过滤。
 */
object ForumTabFilter {
    private const val KEY_SUFFIX = "_blocked_tabs"

    private fun keyOf(forumName: String): String = forumName + KEY_SUFFIX

    fun getBlockedTabs(forumName: String): Set<Int> =
        App.INSTANCE.dataStore.getStringSet(keyOf(forumName))
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    suspend fun setBlockedTabs(forumName: String, tabIds: Set<Int>) {
        App.INSTANCE.dataStore.edit { preferences ->
            val key = stringSetPreferencesKey(keyOf(forumName))
            if (tabIds.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = tabIds.map { it.toString() }.toSet()
            }
        }
    }
}
