/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.backup

import android.content.Context
import androidx.test.backup.BackupDeviceAction
import androidx.test.backup.BackupDeviceActionArgs
import androidx.test.backup.BackupDeviceActionResult
import com.google.samples.apps.nowinandroid.core.datastore.DarkThemeConfigProto
import com.google.samples.apps.nowinandroid.core.datastore.ThemeBrandProto
import com.google.samples.apps.nowinandroid.core.datastore.UserPreferences
import java.io.File

/**
 * Production test action that validates Now in Android user data after restoration:
 * 1. Proto DataStore: Verifies dark theme, theme brand, followed topics, bookmarks, onboarding status.
 * 2. Room Database: Verifies topics, news resources, topic cross-references, search history, and FTS tables.
 * 3. Temporary Cache: Verifies cache files were NOT restored (Android backup exclusion contract).
 */
class NiaVerifyDataAction : BackupDeviceAction {
    override val phase: Int = BackupDeviceAction.PHASE_VERIFY

    override fun execute(context: Context, args: BackupDeviceActionArgs): BackupDeviceActionResult {
        // 1. Verify Proto DataStore (user_preferences.pb)
        val datastoreFile = File(context.filesDir, "datastore/user_preferences.pb")
        check(datastoreFile.exists()) {
            "Proto DataStore file '${datastoreFile.absolutePath}' does not exist after restore"
        }
        val userPrefs = datastoreFile.inputStream().use { UserPreferences.parseFrom(it) }

        check(userPrefs.darkThemeConfig == DarkThemeConfigProto.DARK_THEME_CONFIG_DARK) {
            "DarkThemeConfig mismatch: expected DARK_THEME_CONFIG_DARK but got ${userPrefs.darkThemeConfig}"
        }
        check(userPrefs.themeBrand == ThemeBrandProto.THEME_BRAND_ANDROID) {
            "ThemeBrand mismatch: expected THEME_BRAND_ANDROID but got ${userPrefs.themeBrand}"
        }
        check(!userPrefs.useDynamicColor) {
            "UseDynamicColor mismatch: expected false but got ${userPrefs.useDynamicColor}"
        }
        check(userPrefs.shouldHideOnboarding) {
            "ShouldHideOnboarding mismatch: expected true but got ${userPrefs.shouldHideOnboarding}"
        }
        check(userPrefs.followedTopicIdsMap["1"] == true) {
            "Followed topic '1' (Headlines) not found in restored DataStore: ${userPrefs.followedTopicIdsMap}"
        }
        check(userPrefs.followedTopicIdsMap["3"] == true) {
            "Followed topic '3' (Compose) not found in restored DataStore: ${userPrefs.followedTopicIdsMap}"
        }
        check(userPrefs.bookmarkedNewsResourceIdsMap["1"] == true) {
            "Bookmarked news '1' not found in restored DataStore: ${userPrefs.bookmarkedNewsResourceIdsMap}"
        }
        check(userPrefs.bookmarkedNewsResourceIdsMap["2"] == true) {
            "Bookmarked news '2' not found in restored DataStore: ${userPrefs.bookmarkedNewsResourceIdsMap}"
        }

        // 2. Verify Room SQLite Database (nia-database)
        val dbFile = context.getDatabasePath("nia-database")
        check(dbFile.exists()) {
            "Database file '${dbFile.absolutePath}' does not exist after restore"
        }

        val db = context.openOrCreateDatabase("nia-database", Context.MODE_PRIVATE, null)
        try {
            // Verify topics restored from assets
            db.rawQuery("SELECT name FROM topics WHERE id = '1'", null).use { cursor ->
                check(cursor.moveToFirst()) { "Topic '1' was not restored in database" }
                val name = cursor.getString(0)
                check(name == "Headlines") { "Expected topic '1' to be 'Headlines' but got '$name'" }
            }
            db.rawQuery("SELECT name FROM topics WHERE id = '3'", null).use { cursor ->
                check(cursor.moveToFirst()) { "Topic '3' was not restored in database" }
                val name = cursor.getString(0)
                check(name == "Compose") { "Expected topic '3' to be 'Compose' but got '$name'" }
            }
            db.rawQuery("SELECT COUNT(*) FROM topics", null).use { cursor ->
                val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                check(count >= 19) { "Expected at least 19 topics restored, but found $count" }
            }

            // Verify news_resources restored from assets
            db.rawQuery("SELECT title FROM news_resources WHERE id = '1'", null).use { cursor ->
                check(cursor.moveToFirst()) { "News resource '1' was not restored" }
                val title = cursor.getString(0)
                check(title.startsWith("Android Dev Summit")) {
                    "Restored news '1' title mismatch: expected starting with 'Android Dev Summit' but got '$title'"
                }
            }
            db.rawQuery("SELECT title FROM news_resources WHERE id = '2'", null).use { cursor ->
                check(cursor.moveToFirst()) { "News resource '2' was not restored" }
                val title = cursor.getString(0)
                check(title.contains("Pixel Watch")) {
                    "Restored news '2' title mismatch: expected containing 'Pixel Watch' but got '$title'"
                }
            }
            db.rawQuery("SELECT COUNT(*) FROM news_resources", null).use { cursor ->
                val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                check(count >= 25) { "Expected at least 25 news resources restored, but found $count" }
            }

            // Verify news_resources_topics cross-references
            db.rawQuery("SELECT COUNT(*) FROM news_resources_topics WHERE news_resource_id = '2'", null).use { cursor ->
                val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                check(count == 3) { "Expected 3 topic cross-references for news resource '2', but found $count" }
            }

            // Verify recentSearchQueries
            db.rawQuery("SELECT query FROM recentSearchQueries ORDER BY queriedDate ASC", null).use { cursor ->
                val queries = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    queries.add(cursor.getString(0))
                }
                val expectedQueries = listOf("Compose", "Headlines")
                check(queries == expectedQueries) {
                    "Recent search queries mismatch: expected $expectedQueries but got $queries"
                }
            }

            // Verify FTS virtual tables
            db.rawQuery("SELECT newsResourceId FROM newsResourcesFts WHERE newsResourcesFts MATCH 'Pixel'", null).use { cursor ->
                val matchedNewsIds = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    matchedNewsIds.add(cursor.getString(0))
                }
                check("2" in matchedNewsIds) {
                    "FTS search for 'Pixel' failed to locate news resource '2' in $matchedNewsIds"
                }
            }
            db.rawQuery("SELECT topicId FROM topicsFts WHERE topicsFts MATCH 'Compose'", null).use { cursor ->
                val matchedTopicIds = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    matchedTopicIds.add(cursor.getString(0))
                }
                check("3" in matchedTopicIds) {
                    "FTS search for 'Compose' failed to locate topic '3' in $matchedTopicIds"
                }
            }
        } finally {
            db.close()
        }

        // 3. Verify Cache Exclusion (must NOT be restored)
        val tempCacheFile = File(context.cacheDir, "search_cache.tmp")
        check(!tempCacheFile.exists()) {
            "Cache file '${tempCacheFile.absolutePath}' was restored! Cache files must be excluded from backup."
        }

        return BackupDeviceActionResult(
            mapOf(
                "status" to "success",
                "datastore_verified" to "true",
                "room_verified" to "true",
                "cache_excluded_verified" to "true",
            ),
        )
    }
}
