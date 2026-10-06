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

import androidx.test.backup.host.BackupActionResult
import androidx.test.backup.host.BackupRestoreController
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.ShellCommandOutput
import com.android.adblib.shellAsText
import com.android.adblib.tools.createStandaloneSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.function.ThrowingSupplier
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.Properties

/** Runs [block], failing the test if it does not finish within [seconds]. */
internal fun <T> withinSeconds(seconds: Long, block: suspend CoroutineScope.() -> T): T =
    assertTimeoutPreemptively(Duration.ofSeconds(seconds), ThrowingSupplier { runBlocking(block = block) })

/** Application ID of the `demoDebug` variant under test. */
internal const val APP_ID = "samples.apps.nowinandroid.demo.debug"

internal const val PROBE_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaProbeAction"
internal const val READ_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaReadStorageAction"
internal const val SLOW_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaSlowDeviceAction"
internal const val SEED_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaSeedDataAction"
internal const val VERIFY_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaVerifyDataAction"

/**
 * Runs adb shell commands through its own adblib session, so tests can check device state
 * independently of the library under test.
 */
internal object DeviceChecks {
    private val session: AdbSession by lazy { createStandaloneSession() }

    fun shell(serial: String, command: String): ShellCommandOutput = runBlocking {
        session.deviceServices.shellAsText(DeviceSelector.fromSerialNumber(serial), command)
    }

    /** Serial numbers of the online devices, sorted like the extension sorts them. */
    fun onlineSerials(): List<String> = runBlocking {
        session.hostServices.devices().filter { it.deviceState == DeviceState.ONLINE }
            .map { it.serialNumber }.sorted()
    }

    fun pidOf(serial: String, packageName: String = APP_ID): String =
        shell(serial, "pidof $packageName").stdout.trim()

    fun resumedActivity(serial: String): String =
        shell(serial, "dumpsys activity activities").stdout.lineSequence()
            .firstOrNull { it.contains("ResumedActivity") }.orEmpty().trim()

    /** Grants POST_NOTIFICATIONS, so NiA's permission prompt does not cover its activity. */
    fun grantNotifications(serial: String, packageName: String = APP_ID) {
        shell(serial, "pm grant $packageName android.permission.POST_NOTIFICATIONS")
    }

    fun lastUpdateTime(serial: String, packageName: String = APP_ID): String =
        shell(serial, "dumpsys package $packageName").stdout.lineSequence()
            .firstOrNull { it.trim().startsWith("lastUpdateTime=") }.orEmpty().trim()

    fun log(serial: String, tag: String, message: String) {
        shell(serial, "log -t $tag $message")
    }
}

/** Runs [READ_ACTION] and returns its data, failing the test if the action itself failed. */
internal suspend fun BackupRestoreController.readStorage(vararg args: Pair<String, String>): Map<String, String> {
    val result = runOnDevice(READ_ACTION, mapOf(*args))
    assertTrue(result is BackupActionResult.Success, "NiaReadStorageAction failed: ${result.describe()}")
    return (result as BackupActionResult.Success).data
}

internal suspend fun BackupRestoreController.readPref(prefName: String, key: String) =
    readStorage("kind" to "pref", "pref_name" to prefName, "pref_key" to key)

internal suspend fun BackupRestoreController.readFile(path: String) =
    readStorage("kind" to "file", "path" to path)

internal suspend fun BackupRestoreController.readDbRow(
    dbName: String,
    table: String,
    keyCol: String,
    keyVal: String,
) = readStorage(
    "kind" to "db_row",
    "db_name" to dbName,
    "table" to table,
    "key_col" to keyCol,
    "key_val" to keyVal,
)

internal fun BackupActionResult.describe(): String = when (this) {
    is BackupActionResult.Success -> "Success(data=${data.mapValues { it.value.take(200) }})"
    is BackupActionResult.Failure -> "Failure(errorMessage=${errorMessage.take(500)})"
    else -> toString()
}

/** The in-band `status` an action reported, or null for a [BackupActionResult.Failure]. */
internal val BackupActionResult.reportedStatus: String?
    get() = (this as? BackupActionResult.Success)?.data?.get("status")

/** APKs of the app under test, as passed to the suite by AGP. */
internal fun testedApks(): List<Path> {
    val props = Properties()
    System.getenv("com.android.junit.engine.input.parameters")?.let { path ->
        File(path).takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { props.load(it) }
    }
    val paths = props.getProperty("com.android.agp.test.TESTED_APKS")
        ?: System.getProperty("com.android.agp.test.TESTED_APKS")
        ?: return emptyList()
    return paths.split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File).flatMap { file ->
        if (file.isDirectory) file.listFiles().orEmpty().filter { it.name.endsWith(".apk") } else listOf(file)
    }.filter { it.isFile && it.name.endsWith(".apk") }.map { it.toPath() }
}
