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
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.test.backup.BackupDeviceAction
import androidx.test.backup.BackupDeviceActionArgs
import androidx.test.backup.BackupDeviceActionResult
import java.io.File
import java.security.MessageDigest

/**
 * Read-only on-device action that reports what is actually stored, independently of the bundled
 * `AssertStorageAction`. Selected by the `kind` arg:
 *
 * - `pref` (`pref_name`, `pref_key`): `present`, plus the stored `type` and `value`.
 * - `file` (`path`, relative to `filesDir` or absolute): `present`, `size`, `sha256`, and for
 *   files up to 8 KiB also `text` and `base64`.
 * - `db_row` (`db_name`, `table`, `key_col`, `key_val`): `present`, `row_count` and the first
 *   matching row as `col.<name>`.
 *
 * It never creates files or databases, and throws on invalid arguments.
 */
class NiaReadStorageAction : BackupDeviceAction {
    override val phase: Int = BackupDeviceAction.PHASE_VERIFY

    override fun execute(context: Context, args: BackupDeviceActionArgs): BackupDeviceActionResult {
        val payload = args.payload
        fun arg(key: String) = requireNotNull(payload[key]) { "Missing '$key' argument" }

        val result = when (val kind = payload["kind"]) {
            "pref" -> readPreference(context, arg("pref_name"), arg("pref_key"))
            "file" -> readFile(context, arg("path"))
            "db_row" -> readRows(context, arg("db_name"), arg("table"), arg("key_col"), arg("key_val"))
            else -> throw IllegalArgumentException("Unknown NiaReadStorageAction kind: $kind")
        }
        return BackupDeviceActionResult(result + ("status" to "success"))
    }

    private fun readPreference(context: Context, prefName: String, key: String): Map<String, String> {
        val all = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).all
        if (!all.containsKey(key)) return mapOf("present" to "false")
        val value = all[key]
        return mapOf(
            "present" to "true",
            "type" to (value?.javaClass?.simpleName ?: "null"),
            "value" to value.toString(),
        )
    }

    private fun readFile(context: Context, path: String): Map<String, String> {
        val file = File(path).let { if (it.isAbsolute) it else File(context.filesDir, path) }
        if (!file.isFile) return mapOf("present" to "false", "absolute_path" to file.absolutePath)
        val bytes = file.readBytes()
        val result = mutableMapOf(
            "present" to "true",
            "absolute_path" to file.absolutePath,
            "size" to bytes.size.toString(),
            "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) },
        )
        if (bytes.size <= MAX_INLINE_BYTES) {
            result["text"] = bytes.toString(Charsets.UTF_8)
            result["base64"] = Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return result
    }

    private fun readRows(
        context: Context,
        dbName: String,
        table: String,
        keyCol: String,
        keyVal: String,
    ): Map<String, String> {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.isFile) return mapOf("present" to "false", "db_exists" to "false")
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tableExists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { it.moveToFirst() }
            if (!tableExists) {
                return mapOf("present" to "false", "db_exists" to "true", "table_exists" to "false")
            }
            db.rawQuery("SELECT * FROM `$table` WHERE `$keyCol` = ?", arrayOf(keyVal)).use { cursor ->
                val result = mutableMapOf(
                    "db_exists" to "true",
                    "table_exists" to "true",
                    "present" to (cursor.count > 0).toString(),
                    "row_count" to cursor.count.toString(),
                )
                if (cursor.moveToFirst()) {
                    for (i in 0 until cursor.columnCount) {
                        result["col.${cursor.getColumnName(i)}"] = cursor.getString(i) ?: "<null>"
                    }
                }
                return result
            }
        }
    }

    private companion object {
        const val MAX_INLINE_BYTES = 8 * 1024
    }
}
