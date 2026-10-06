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
import androidx.test.backup.host.BackupRestoreConfig
import androidx.test.backup.host.BackupRestoreController
import androidx.test.backup.host.BackupRestoreExtension
import androidx.test.backup.host.BackupTransportMode
import androidx.test.backup.host.Device
import androidx.test.backup.host.Isolation
import androidx.test.backup.host.IsolationPolicy
import androidx.test.backup.host.StorageDomain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Exercises every public API of `androidx.test.backup` 1.0.0-alpha01 against Now in Android.
 *
 * Restored data is always re-read with [NiaReadStorageAction], so a test only passes when the data
 * really survived the backup and restore, independently of the library's own verification.
 */
@ExtendWith(BackupRestoreExtension::class)
@BackupRestoreConfig(applicationId = APP_ID)
@Isolation(IsolationPolicy.AUTOMATIC)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaAlpha01ApiTest {

    private companion object {
        const val PREFS = "nia_alpha01_prefs"
        const val DB = "nia_alpha01.db"
        const val TABLE = "sync_log"
        const val ISOLATION_KEY = "isolation_marker"
        val BINARY = byteArrayOf(0x00, 0x01, 0x7F, -0x80, -0x01, 0x4E, 0x49, 0x41)
    }

    private fun allDomains(tag: String) = listOf(
        StorageDomain.Preference(PREFS, "string_$tag", "user_42"),
        StorageDomain.Preference(PREFS, "int_$tag", 5),
        StorageDomain.Preference(PREFS, "long_$tag", 1725800000000L),
        StorageDomain.Preference(PREFS, "float_$tag", 1.25f),
        StorageDomain.Preference(PREFS, "bool_$tag", true),
        StorageDomain.TextFile("sync/$tag.json", "{\"sync_version\": 1, \"status\": \"COMPLETED\"}"),
        StorageDomain.BinaryFile("security/$tag.key", BINARY),
        StorageDomain.Database(
            dbName = DB,
            table = TABLE,
            primaryKeyCol = "sync_id",
            primaryKeyVal = "id_$tag",
            columnValues = mapOf("sync_id" to "id_$tag", "status" to "SUCCESS", "records" to 42),
        ),
    )

    /** Re-reads every domain from [allDomains] and asserts it was restored exactly. */
    private suspend fun BackupRestoreController.assertAllDomainsRestored(tag: String) {
        val expectedPrefs = listOf(
            Triple("string_$tag", "String", "user_42"),
            Triple("int_$tag", "Integer", "5"),
            Triple("long_$tag", "Long", "1725800000000"),
            Triple("float_$tag", "Float", "1.25"),
            Triple("bool_$tag", "Boolean", "true"),
        )
        for ((key, type, value) in expectedPrefs) {
            val pref = readPref(PREFS, key)
            assertEquals("true", pref["present"], "Preference '$key' was not restored: $pref")
            assertEquals(type, pref["type"], "Preference '$key' has the wrong type: $pref")
            assertEquals(value, pref["value"], "Preference '$key' has the wrong value: $pref")
        }
        val text = readFile("sync/$tag.json")
        assertEquals("{\"sync_version\": 1, \"status\": \"COMPLETED\"}", text["text"], "TextFile: $text")
        val binary = readFile("security/$tag.key")
        assertArrayEquals(BINARY, Base64.getDecoder().decode(binary["base64"].orEmpty()), "BinaryFile: $binary")
        val row = readDbRow(DB, TABLE, "sync_id", "id_$tag")
        assertEquals("1", row["row_count"], "Database row: $row")
        assertEquals("SUCCESS", row["col.status"], "Database row: $row")
        assertEquals("42", row["col.records"], "Database row: $row")
    }

    // ---------------------------------------------------------------------------------------------
    // Controller properties and constants
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("API-01 Controller exposes serialNumber, apiLevel, applicationId and the public constants")
    fun controllerProperties(@Device device: BackupRestoreController) {
        assertTrue(device.serialNumber in DeviceChecks.onlineSerials(), "Unknown serial ${device.serialNumber}")
        val sdk = DeviceChecks.shell(device.serialNumber, "getprop ro.build.version.sdk").stdout.trim()
        assertEquals(sdk.toInt(), device.apiLevel)
        assertEquals(APP_ID, device.applicationId)
        assertEquals("androidx.test.backup.actions.PopulateStorageAction", BackupRestoreController.ACTION_POPULATE_STORAGE)
        assertEquals("androidx.test.backup.actions.AssertStorageAction", BackupRestoreController.ACTION_ASSERT_STORAGE)
        assertEquals(listOf("-r", "-t", "-g"), BackupRestoreController.DEFAULT_INSTALL_OPTIONS)
    }

    // ---------------------------------------------------------------------------------------------
    // Declarative flows, one per transport, each verified independently
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("API-10 runBackupRestoreFlow(List) CLOUD_UNENCRYPTED restores every StorageDomain type")
    fun flowCloudUnencryptedAllDomains(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val returned = device.runBackupRestoreFlow(allDomains("cloud"), dir, BackupTransportMode.CLOUD_UNENCRYPTED)
        assertEquals(device.serialNumber, returned.serialNumber)
        device.assertAllDomainsRestored("cloud")
    }

    @Test
    @Order(11)
    @DisplayName("API-11 runBackupRestoreFlow(StorageDomain) LOCAL restores the domain")
    fun flowLocalSingleDomain(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        device.runBackupRestoreFlow(StorageDomain.Preference(PREFS, "local_key", "local_value"), dir, BackupTransportMode.LOCAL)
        val pref = device.readPref(PREFS, "local_key")
        assertEquals("local_value", pref["value"], "LOCAL restore: $pref")
    }

    @Test
    @Order(12)
    @DisplayName("API-12 runBackupRestoreFlowAsync(List) CLOUD_ENCRYPTED restores every StorageDomain type")
    fun flowAsyncCloudEncryptedAllDomains(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val future = device.runBackupRestoreFlowAsync(allDomains("encrypted"), dir, BackupTransportMode.CLOUD_ENCRYPTED)
        assertEquals(device.serialNumber, future.get(5, TimeUnit.MINUTES).serialNumber)
        device.assertAllDomainsRestored("encrypted")
    }

    @Test
    @Order(13)
    @DisplayName("API-13 runBackupRestoreFlowAsync(StorageDomain) DEVICE_TO_DEVICE restores the domain on one device")
    fun flowAsyncD2dSingleDomain(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val domain = StorageDomain.TextFile("d2d/manifest.txt", "device-to-device payload")
        device.runBackupRestoreFlowAsync(domain, dir, BackupTransportMode.DEVICE_TO_DEVICE).get(5, TimeUnit.MINUTES)
        val file = device.readFile("d2d/manifest.txt")
        assertEquals("device-to-device payload", file["text"], "D2D restore: $file")
    }

    // ---------------------------------------------------------------------------------------------
    // Step-by-step lifecycle
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("API-20 performBackup / clearAppData / performRestore (CLOUD_UNENCRYPTED) restore NiA's DataStore and Room data")
    fun manualStepsCloudWithAppData(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val seed = device.runOnDevice(SEED_ACTION, emptyMap(), Duration.ofMinutes(1), false)
        assertTrue(seed is BackupActionResult.Success, "Seed failed: ${seed.describe()}")
        device.stopApp()
        val archive = device.performBackup(BackupTransportMode.CLOUD_UNENCRYPTED, dir, Duration.ofMinutes(3))
        assertTrue(Files.size(archive) > 0, "Backup archive is empty: $archive")
        device.clearAppData()
        device.performRestore(archive, Duration.ofMinutes(3))
        val verify = device.runOnDevice(VERIFY_ACTION, emptyMap(), Duration.ofMinutes(1))
        assertTrue(verify is BackupActionResult.Success, "NiA data not restored: ${verify.describe()}")
    }

    @Test
    @Order(21)
    @DisplayName("API-21 Async steps: performBackupAsync / clearAppDataAsync / performRestoreAsync / runOnDeviceAsync (LOCAL)")
    fun manualStepsAsyncLocal(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val populate = device.runOnDeviceAsync(
            BackupRestoreController.ACTION_POPULATE_STORAGE,
            mapOf("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to "async_key", "value" to "async_value"),
        ).get(1, TimeUnit.MINUTES)
        assertEquals("success", populate.reportedStatus, "Populate: ${populate.describe()}")
        device.stopAppAsync().get(1, TimeUnit.MINUTES)
        val archive = device.performBackupAsync(BackupTransportMode.LOCAL, dir, Duration.ofMinutes(2)).get(3, TimeUnit.MINUTES)
        device.clearAppDataAsync().get(1, TimeUnit.MINUTES)
        assertEquals("false", device.readPref(PREFS, "async_key")["present"], "clearAppDataAsync kept the preference")
        device.performRestoreAsync(archive).get(3, TimeUnit.MINUTES)
        val read = device.runOnDeviceAsync(
            READ_ACTION,
            mapOf("kind" to "pref", "pref_name" to PREFS, "pref_key" to "async_key"),
            Duration.ofMinutes(1),
            false,
        ).get(2, TimeUnit.MINUTES)
        assertEquals("async_value", (read as BackupActionResult.Success).data["value"], "Restored: ${read.describe()}")
    }

    @Test
    @Order(22)
    @DisplayName("API-22 clearAppData removes everything the app stored")
    fun clearAppDataWipes(@Device device: BackupRestoreController): Unit = runBlocking {
        device.runOnDevice(
            BackupRestoreController.ACTION_POPULATE_STORAGE,
            mapOf("storage_type" to "FILES", "path" to "wipe/me.txt", "value" to "data"),
        )
        assertEquals("true", device.readFile("wipe/me.txt")["present"])
        device.clearAppData()
        assertEquals("false", device.readFile("wipe/me.txt")["present"], "clearAppData left the file behind")
    }

    // ---------------------------------------------------------------------------------------------
    // App lifecycle
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(30)
    @DisplayName("API-30 launchApp() starts the launcher activity and stopApp() kills the process")
    fun launchAndStop(@Device device: BackupRestoreController): Unit = runBlocking {
        DeviceChecks.grantNotifications(device.serialNumber)
        device.stopApp()
        assertEquals("", DeviceChecks.pidOf(device.serialNumber), "stopApp left the process running")
        device.launchApp()
        assertNotEquals("", DeviceChecks.pidOf(device.serialNumber), "launchApp did not start the app")
        assertTrue(DeviceChecks.resumedActivity(device.serialNumber).contains(APP_ID), DeviceChecks.resumedActivity(device.serialNumber))
        device.stopApp()
        assertEquals("", DeviceChecks.pidOf(device.serialNumber), "stopApp left the process running")
    }

    @Test
    @Order(31)
    @DisplayName("API-31 launchApp(activityClass, intentExtras, action) and every launchAppAsync / stopAppAsync overload")
    fun launchVariants(@Device device: BackupRestoreController): Unit = runBlocking {
        DeviceChecks.grantNotifications(device.serialNumber)
        val activity = "com.google.samples.apps.nowinandroid.MainActivity"
        device.launchApp(activity, mapOf("launch_source" to "teamfood", "note" to "a b \$c"), "android.intent.action.MAIN")
        val resumed = DeviceChecks.resumedActivity(device.serialNumber)
        assertTrue(resumed.contains("MainActivity"), resumed)
        val launches = listOf(
            { device.launchAppAsync() },
            { device.launchAppAsync(activity) },
            { device.launchAppAsync(activity, mapOf("k" to "v")) },
            { device.launchAppAsync(activity, mapOf("k" to "v"), "android.intent.action.MAIN") },
        )
        for (launch in launches) {
            device.stopAppAsync().get(1, TimeUnit.MINUTES)
            assertEquals("", DeviceChecks.pidOf(device.serialNumber), "stopAppAsync left the process running")
            launch().get(1, TimeUnit.MINUTES)
            assertNotEquals("", DeviceChecks.pidOf(device.serialNumber), "launchAppAsync did not start the app")
        }
    }

    @Test
    @Order(32)
    @DisplayName("API-32 installApk / installApkAsync reinstall the tested APK")
    fun installApk(@Device device: BackupRestoreController): Unit = runBlocking {
        val apk = testedApks().firstOrNull()
        assertNotNull(apk, "AGP did not pass TESTED_APKS to the suite")
        device.installApk(apk!!, BackupRestoreController.DEFAULT_INSTALL_OPTIONS)
        device.installApkAsync(apk).get(3, TimeUnit.MINUTES)
        device.installApkAsync(apk, listOf("-r", "-t")).get(3, TimeUnit.MINUTES)
        val path = DeviceChecks.shell(device.serialNumber, "pm path $APP_ID").stdout
        assertTrue(path.contains("package:"), "App not installed after installApk: $path")
    }

    // ---------------------------------------------------------------------------------------------
    // Files and logs
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("API-40 pullFile / pullFileAsync copy a device file to the host")
    fun pullFile(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val sync = dir.resolve("hosts_sync.txt")
        device.pullFile("/system/etc/hosts", sync)
        assertTrue(sync.toFile().readText().contains("localhost"))
        val async = dir.resolve("hosts_async.txt")
        device.pullFileAsync("/system/etc/hosts", async).get(1, TimeUnit.MINUTES)
        assertEquals(sync.toFile().readText(), async.toFile().readText())
    }

    @Test
    @Order(41)
    @DisplayName("API-41 pullFile of a missing device file throws IOException")
    fun pullMissingFile(@Device device: BackupRestoreController, @TempDir dir: Path) {
        assertThrows(IOException::class.java) {
            runBlocking { device.pullFile("/data/local/tmp/does_not_exist_${UUID.randomUUID()}", dir.resolve("missing")) }
        }
    }

    @Test
    @Order(42)
    @DisplayName("API-42 clearDeviceLogs / clearDeviceLogsAsync empty the logcat buffer")
    fun clearLogs(@Device device: BackupRestoreController): Unit = runBlocking {
        val before = "nia-before-clear-${UUID.randomUUID()}"
        DeviceChecks.log(device.serialNumber, "NiaBackupTest", before)
        device.clearDeviceLogs()
        assertFalse(DeviceChecks.shell(device.serialNumber, "logcat -d").stdout.contains(before), "clearDeviceLogs kept old logs")
        val beforeAsync = "nia-before-clear-async-${UUID.randomUUID()}"
        DeviceChecks.log(device.serialNumber, "NiaBackupTest", beforeAsync)
        device.clearDeviceLogsAsync().get(1, TimeUnit.MINUTES)
        assertFalse(DeviceChecks.shell(device.serialNumber, "logcat -d").stdout.contains(beforeAsync), "clearDeviceLogsAsync kept old logs")
    }

    @Test
    @Order(43)
    @DisplayName("API-43 fetchDeviceLogs / fetchDeviceLogsAsync save recent logcat entries to a host file")
    fun fetchLogs(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        device.clearDeviceLogs()
        val marker = "nia-fetch-${UUID.randomUUID()}"
        DeviceChecks.log(device.serialNumber, "NiaBackupTest", marker)
        val outputs = listOf(dir.resolve("default.log"), dir.resolve("explicit.log"), dir.resolve("async.log"), dir.resolve("async_explicit.log"))
        device.fetchDeviceLogs(outputs[0])
        device.fetchDeviceLogs(outputs[1], Duration.ofMinutes(2))
        device.fetchDeviceLogsAsync(outputs[2]).get(1, TimeUnit.MINUTES)
        device.fetchDeviceLogsAsync(outputs[3], Duration.ofMinutes(2)).get(1, TimeUnit.MINUTES)
        for (output in outputs) {
            assertTrue(Files.exists(output), "No log file at $output")
            assertTrue(output.toFile().readText().contains(marker), "${output.fileName} (${Files.size(output)} bytes) lacks the marker logged just before")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // runOnDevice and results
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("API-50 runOnDevice with ACTION_POPULATE_STORAGE / ACTION_ASSERT_STORAGE for prefs, files and databases")
    fun bundledActions(@Device device: BackupRestoreController): Unit = runBlocking {
        val cases = listOf(
            mapOf("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to "k", "value" to "41", "value_type" to "INT") to
                mapOf("expected" to "41"),
            mapOf("storage_type" to "FILES", "path" to "notes/a.txt", "value" to "hello") to
                mapOf("expected" to "hello"),
            mapOf("storage_type" to "DATABASE", "db_name" to DB, "table" to TABLE, "values" to "sync_id=r1&status=OK") to
                mapOf("key_col" to "sync_id", "key_val" to "r1", "expected_col" to "status", "expected_val" to "OK"),
        )
        for ((populateArgs, assertExtras) in cases) {
            val populate = device.runOnDevice(BackupRestoreController.ACTION_POPULATE_STORAGE, populateArgs)
            assertTrue(populate is BackupActionResult.Success && populate.reportedStatus == "success", "Populate $populateArgs: ${populate.describe()}")
            val verify = device.runOnDevice(BackupRestoreController.ACTION_ASSERT_STORAGE, populateArgs + assertExtras)
            assertTrue(verify is BackupActionResult.Success && verify.reportedStatus == "success", "Assert $populateArgs: ${verify.describe()}")
        }
    }

    @Test
    @Order(51)
    @DisplayName("API-51 runOnDevice returns a custom action's data map, through every runOnDeviceAsync overload")
    fun customActionData(@Device device: BackupRestoreController): Unit = runBlocking {
        val args = mapOf("mode" to "echo", "user" to "nia_user_42", "count" to "7")
        val sync = device.runOnDevice(PROBE_ACTION, args, Duration.ofMinutes(1), false)
        assertTrue(sync is BackupActionResult.Success && sync.isSuccess, sync.describe())
        assertEquals("nia_user_42", (sync as BackupActionResult.Success).data["arg.user"])
        assertEquals("7", sync.data["arg.count"])
        val unknownMode = device.runOnDeviceAsync(PROBE_ACTION).get(1, TimeUnit.MINUTES)
        assertTrue(unknownMode is BackupActionResult.Failure, "No mode must fail: ${unknownMode.describe()}")
        val overloads = listOf(
            { device.runOnDeviceAsync(PROBE_ACTION, args) },
            { device.runOnDeviceAsync(PROBE_ACTION, args, Duration.ofMinutes(1)) },
            { device.runOnDeviceAsync(PROBE_ACTION, args, Duration.ofMinutes(1), false) },
        )
        for (overload in overloads) {
            val result = overload().get(1, TimeUnit.MINUTES)
            assertEquals("nia_user_42", (result as BackupActionResult.Success).data["arg.user"], result.describe())
        }
    }

    @Test
    @Order(52)
    @DisplayName("API-52 runOnDevice reports a throwing action as Failure with its message and stack trace")
    fun throwingAction(@Device device: BackupRestoreController): Unit = runBlocking {
        val result = device.runOnDevice(PROBE_ACTION, mapOf("mode" to "throw", "message" to "nia-boom"))
        assertTrue(result is BackupActionResult.Failure, result.describe())
        result as BackupActionResult.Failure
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage.contains("nia-boom"), result.errorMessage)
        assertTrue(result.stackTrace.orEmpty().contains("NiaProbeAction"), "Stack trace: ${result.stackTrace}")
    }

    @Test
    @Order(53)
    @DisplayName("API-53 runOnDevice reports an unknown action class as Failure")
    fun unknownActionClass(@Device device: BackupRestoreController): Unit = runBlocking {
        val result = device.runOnDevice("com.example.DoesNotExistAction")
        assertTrue(result is BackupActionResult.Failure, result.describe())
        assertTrue((result as BackupActionResult.Failure).errorMessage.contains("com.example.DoesNotExistAction"), result.errorMessage)
    }

    @Test
    @Order(54)
    @DisplayName("API-54 BackupActionResult and StorageDomain value semantics and validation")
    fun valueTypes() {
        val success = BackupActionResult.Success(mapOf("k" to "v"))
        assertTrue(success.isSuccess)
        assertEquals(mapOf("k" to "v"), success.data)
        assertTrue(BackupActionResult.Success().data.isEmpty())
        val failure = BackupActionResult.Failure("broken", "trace")
        assertFalse(failure.isSuccess)
        assertEquals("broken", failure.errorMessage)
        assertEquals("trace", failure.stackTrace)

        assertThrows(IllegalArgumentException::class.java) { StorageDomain.Preference(PREFS, "double", 1.5) }
        assertThrows(IllegalArgumentException::class.java) {
            StorageDomain.Database(DB, TABLE, "id", "1", mapOf("blob" to byteArrayOf(1)))
        }
        val bytes = byteArrayOf(1, 2, 3)
        val binary = StorageDomain.BinaryFile("b.bin", bytes)
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), binary.content, "BinaryFile must copy its content")
        assertEquals(StorageDomain.Preference(PREFS, "k", 1), StorageDomain.Preference(PREFS, "k", 1))
        assertEquals(StorageDomain.TextFile("a", "b").hashCode(), StorageDomain.TextFile("a", "b").hashCode())
        assertEquals(StorageDomain.Preference(PREFS, "k").value, null)
    }

    // ---------------------------------------------------------------------------------------------
    // Isolation policies
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(60)
    @Isolation(IsolationPolicy.MANUAL)
    @DisplayName("API-60 @Isolation(MANUAL): seed a marker that the next tests inspect")
    fun isolationSeed(@Device device: BackupRestoreController): Unit = runBlocking {
        device.clearAppData()
        val result = device.runOnDevice(
            BackupRestoreController.ACTION_POPULATE_STORAGE,
            mapOf("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to ISOLATION_KEY, "value" to "kept"),
        )
        assertEquals("success", result.reportedStatus, result.describe())
    }

    @Test
    @Order(61)
    @Isolation(IsolationPolicy.MANUAL)
    @DisplayName("API-61 @Isolation(MANUAL) on a method keeps data from the previous test")
    fun isolationManualKeeps(@Device device: BackupRestoreController): Unit = runBlocking {
        assertEquals("kept", device.readPref(PREFS, ISOLATION_KEY)["value"], "MANUAL isolation cleared the app data")
    }

    @Test
    @Order(62)
    @DisplayName("API-62 @Isolation(AUTOMATIC) on the class clears data before each test")
    fun isolationAutomaticClears(@Device device: BackupRestoreController): Unit = runBlocking {
        assertEquals("false", device.readPref(PREFS, ISOLATION_KEY)["present"], "AUTOMATIC isolation kept the app data")
    }

    // ---------------------------------------------------------------------------------------------
    // Device qualifiers
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(70)
    @DisplayName("API-70 @Device(serial) and @Device(api) select matching devices")
    fun deviceQualifiers(
        @Device(serial = "emulator-5556") bySerial: BackupRestoreController,
        @Device(api = 34) byApi: BackupRestoreController,
    ) {
        assertEquals("emulator-5556", bySerial.serialNumber)
        assertEquals(34, byApi.apiLevel)
    }

    @Test
    @Order(71)
    @DisplayName("API-71 Two @Device parameters resolve to two distinct devices")
    fun twoDevices(@Device first: BackupRestoreController, @Device second: BackupRestoreController) {
        assertNotEquals(first.serialNumber, second.serialNumber)
        assertEquals(DeviceChecks.onlineSerials().take(2), listOf(first.serialNumber, second.serialNumber))
    }

    @Test
    @Order(72)
    @DisplayName("API-72 Cross-device DEVICE_TO_DEVICE: backup on one device, restore NiA data on another")
    fun crossDeviceD2d(
        @Device source: BackupRestoreController,
        @Device target: BackupRestoreController,
        @TempDir dir: Path,
    ): Unit = runBlocking {
        assertNotEquals(source.serialNumber, target.serialNumber)
        val seed = source.runOnDevice(SEED_ACTION, emptyMap(), Duration.ofMinutes(1))
        assertTrue(seed is BackupActionResult.Success, seed.describe())
        source.stopApp()
        val archive = source.performBackup(BackupTransportMode.DEVICE_TO_DEVICE, dir, Duration.ofMinutes(3))
        target.stopApp()
        target.performRestore(archive, Duration.ofMinutes(3))
        val verify = target.runOnDevice(VERIFY_ACTION, emptyMap(), Duration.ofMinutes(1))
        assertTrue(verify is BackupActionResult.Success, "Target device: ${verify.describe()}")
    }

    /** JUnit `@Nested` classes are a common way to group backup scenarios. */
    @Nested
    @DisplayName("API-80 @Nested test class inherits @BackupRestoreConfig and @Isolation from its outer class")
    inner class NestedScenarios {
        @Test
        @DisplayName("API-80 Nested test resolves a controller for the outer class's application")
        fun nestedResolves(@Device device: BackupRestoreController) {
            assertEquals(APP_ID, device.applicationId)
        }
    }
}
