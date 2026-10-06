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
import org.json.JSONArray
import java.io.File
import java.time.Instant

/**
 * Production test action that populates user data using the app's real bundled assets:
 * 1. Proto DataStore: Seeds user state following real topics ("1" Headlines, "3" Compose) and bookmarking real news ("1", "2").
 * 2. Room Database: Seeds topics and news resources directly from the app's bundled assets (topics.json, news.json).
 * 3. Temporary Cache: Creates a temporary cache file to verify exclusion from backup.
 */
class NiaSeedDataAction : BackupDeviceAction {
    override val phase: Int = BackupDeviceAction.PHASE_POPULATE

    override fun execute(context: Context, args: BackupDeviceActionArgs): BackupDeviceActionResult {
        // 1. Seed Proto DataStore (user_preferences.pb) referencing real topics and news
        val datastoreFile = File(context.filesDir, "datastore/user_preferences.pb")
        datastoreFile.parentFile?.mkdirs()
        val userPreferences = UserPreferences.newBuilder()
            .setHasDoneIntToStringIdMigration(true)
            .setHasDoneListToMapMigration(true)
            .setTopicChangeListVersion(1)
            .setNewsResourceChangeListVersion(1)
            .setDarkThemeConfig(DarkThemeConfigProto.DARK_THEME_CONFIG_DARK)
            .setThemeBrand(ThemeBrandProto.THEME_BRAND_ANDROID)
            .setUseDynamicColor(false)
            .setShouldHideOnboarding(true)
            .putFollowedTopicIds("1", true) // Real topic 1: Headlines
            .putFollowedTopicIds("3", true) // Real topic 3: Compose
            .putBookmarkedNewsResourceIds("1", true) // Real news 1: Android Dev Summit
            .putBookmarkedNewsResourceIds("2", true) // Real news 2: Pixel Watch
            .build()
        datastoreFile.outputStream().use { userPreferences.writeTo(it) }

        // 2. Seed Room SQLite Database (nia-database) matching NiaDatabase version 14 schema
        val db = context.openOrCreateDatabase("nia-database", Context.MODE_PRIVATE, null)
        db.enableWriteAheadLogging()
        db.version = 14

        // Setup room_master_table required by Room runtime schema verification
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '51271b81bde7c7997d67fb23c8f31780')")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS topics (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                shortDescription TEXT NOT NULL,
                longDescription TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                imageUrl TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS news_resources (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                url TEXT NOT NULL,
                header_image_url TEXT,
                publish_date INTEGER NOT NULL,
                type TEXT NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS news_resources_topics (
                news_resource_id TEXT NOT NULL,
                topic_id TEXT NOT NULL,
                PRIMARY KEY(news_resource_id, topic_id),
                FOREIGN KEY(news_resource_id) REFERENCES news_resources(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(topic_id) REFERENCES topics(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_news_resources_topics_news_resource_id ON news_resources_topics (news_resource_id)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_news_resources_topics_topic_id ON news_resources_topics (topic_id)",
        )

        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS newsResourcesFts USING FTS4(newsResourceId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS topicsFts USING FTS4(topicId TEXT NOT NULL, name TEXT NOT NULL, shortDescription TEXT NOT NULL, longDescription TEXT NOT NULL)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recentSearchQueries (
                query TEXT PRIMARY KEY NOT NULL,
                queriedDate INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // Seed Topics directly from the app's real bundled asset (topics.json)
        val topicsJson = context.assets.open("topics.json").bufferedReader().use { it.readText() }
        val topicsArray = JSONArray(topicsJson)
        val topicStatement = db.compileStatement(
            "INSERT OR REPLACE INTO topics (id, name, shortDescription, longDescription, url, imageUrl) VALUES (?, ?, ?, ?, ?, ?)",
        )
        val topicFtsStatement = db.compileStatement(
            "INSERT OR REPLACE INTO topicsFts (topicId, name, shortDescription, longDescription) VALUES (?, ?, ?, ?)",
        )

        for (i in 0 until topicsArray.length()) {
            val topic = topicsArray.getJSONObject(i)
            val id = topic.getString("id")
            val name = topic.getString("name")
            val shortDesc = topic.optString("shortDescription", "")
            val longDesc = topic.optString("longDescription", "")
            val url = topic.optString("url", "")
            val imageUrl = topic.optString("imageUrl", "")

            topicStatement.bindString(1, id)
            topicStatement.bindString(2, name)
            topicStatement.bindString(3, shortDesc)
            topicStatement.bindString(4, longDesc)
            topicStatement.bindString(5, url)
            topicStatement.bindString(6, imageUrl)
            topicStatement.executeInsert()

            topicFtsStatement.bindString(1, id)
            topicFtsStatement.bindString(2, name)
            topicFtsStatement.bindString(3, shortDesc)
            topicFtsStatement.bindString(4, longDesc)
            topicFtsStatement.executeInsert()
        }

        // Seed News Resources and Cross-References directly from the app's real bundled asset (news.json)
        val newsJson = context.assets.open("news.json").bufferedReader().use { it.readText() }
        val newsArray = JSONArray(newsJson)
        val newsStatement = db.compileStatement(
            "INSERT OR REPLACE INTO news_resources (id, title, content, url, header_image_url, publish_date, type) VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        val crossRefStatement = db.compileStatement(
            "INSERT OR REPLACE INTO news_resources_topics (news_resource_id, topic_id) VALUES (?, ?)",
        )
        val newsFtsStatement = db.compileStatement(
            "INSERT OR REPLACE INTO newsResourcesFts (newsResourceId, title, content) VALUES (?, ?, ?)",
        )

        val newsCount = minOf(newsArray.length(), 25)
        for (i in 0 until newsCount) {
            val news = newsArray.getJSONObject(i)
            val id = news.getString("id")
            val title = news.getString("title")
            val content = news.getString("content")
            val url = news.getString("url")
            val headerImageUrl = news.optString("headerImageUrl", "")
            val publishDateEpoch = try {
                Instant.parse(news.getString("publishDate")).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
            val type = news.optString("type", "Article")

            newsStatement.bindString(1, id)
            newsStatement.bindString(2, title)
            newsStatement.bindString(3, content)
            newsStatement.bindString(4, url)
            newsStatement.bindString(5, headerImageUrl)
            newsStatement.bindLong(6, publishDateEpoch)
            newsStatement.bindString(7, type)
            newsStatement.executeInsert()

            newsFtsStatement.bindString(1, id)
            newsFtsStatement.bindString(2, title)
            newsFtsStatement.bindString(3, content)
            newsFtsStatement.executeInsert()

            val topics = news.optJSONArray("topics")
            if (topics != null) {
                for (t in 0 until topics.length()) {
                    val topicId = topics.getString(t)
                    crossRefStatement.bindString(1, id)
                    crossRefStatement.bindString(2, topicId)
                    crossRefStatement.executeInsert()
                }
            }
        }

        // Seed Recent Search Queries for existing app topics
        val searchQueryStatement = db.compileStatement(
            "INSERT OR REPLACE INTO recentSearchQueries (query, queriedDate) VALUES (?, ?)",
        )
        searchQueryStatement.bindString(1, "Compose")
        searchQueryStatement.bindLong(2, 1700000000000)
        searchQueryStatement.executeInsert()

        searchQueryStatement.bindString(1, "Headlines")
        searchQueryStatement.bindLong(2, 1700000001000)
        searchQueryStatement.executeInsert()

        // Checkpoint WAL to flush memory buffers to disk
        try {
            val walCursor = db.rawQuery("PRAGMA wal_checkpoint(FULL);", null)
            walCursor.moveToFirst()
            walCursor.close()
        } catch (_: Throwable) {}
        db.close()

        // 3. Seed Ephemeral Cache File (must be excluded per Android backup specification)
        val cacheFile = File(context.cacheDir, "search_cache.tmp")
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText("ephemeral_search_cache_data")

        return BackupDeviceActionResult(
            mapOf(
                "status" to "success",
                "datastore_seeded" to "true",
                "room_seeded" to "true",
                "topics_seeded" to topicsArray.length().toString(),
                "news_seeded" to newsCount.toString(),
            ),
        )
    }
}
