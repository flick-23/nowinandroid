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
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile

/**
 * Scenarios that `androidx.test.backup` gets wrong in some release.
 *
 * Every test asserts the correct behavior, so it fails on a release that has the issue and passes
 * once the issue is fixed. Stored data is re-read with [NiaReadStorageAction], independently of
 * the library's own verification.
 */
@ExtendWith(BackupRestoreExtension::class)
@BackupRestoreConfig(applicationId = APP_ID)
@Isolation(IsolationPolicy.AUTOMATIC)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaAlpha01KnownIssuesTest {

    private companion object {
        const val PREFS = "nia_known_issues_prefs"
        const val DB = "nia_known_issues.db"
        const val TABLE = "notes"

        var installTimeAfterFirstResolution: String? = null
    }

    private suspend fun BackupRestoreController.populate(vararg args: Pair<String, String>) =
        runOnDevice(BackupRestoreController.ACTION_POPULATE_STORAGE, mapOf(*args))

    private suspend fun BackupRestoreController.assertStorage(vararg args: Pair<String, String>) =
        runOnDevice(BackupRestoreController.ACTION_ASSERT_STORAGE, mapOf(*args))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------------------------------
    // Reported results
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("KI-01 ACTION_ASSERT_STORAGE reports a value mismatch as Failure")
    fun assertMismatchIsFailure(@Device device: BackupRestoreController): Unit = runBlocking {
        val pref = arrayOf("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to "color")
        device.populate(*pref, "value" to "blue")
        val result = device.assertStorage(*pref, "expected" to "red")
        assertTrue(result is BackupActionResult.Failure, "Stored 'blue', asserted 'red', got ${result.describe()}")
    }

    @Test
    @Order(20)
    @DisplayName("KI-02 runBackupRestoreFlow fails when a seeded file is not restored")
    fun flowFailsWhenDataIsNotRestored(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        // The platform never backs up files in no_backup/.
        val path = "/data/user/0/$APP_ID/no_backup/excluded.txt"
        val flow = runCatching {
            device.runBackupRestoreFlow(StorageDomain.TextFile(path, "never restored"), dir, BackupTransportMode.CLOUD_UNENCRYPTED)
        }
        val file = device.readFile(path)
        assertEquals("false", file["present"], "Precondition: no_backup/ must not be restored: $file")
        assertTrue(flow.exceptionOrNull() is IOException, "The file was not restored, yet the flow ended with ${flow.exceptionOrNull() ?: "success"}")
    }

    @Test
    @Order(30)
    @DisplayName("KI-03 Actions that report status=failure without throwing are reported as Failure")
    fun inBandFailuresAreFailures(@Device device: BackupRestoreController): Unit = runBlocking {
        val populate = device.populate(
            "storage_type" to "PREFS",
            "pref_name" to PREFS,
            "pref_key" to "count",
            "value" to "not-a-number",
            "value_type" to "INT",
        )
        val custom = device.runOnDevice(PROBE_ACTION, mapOf("mode" to "in_band_failure", "error" to "nia-in-band"))
        assertAll(
            { assertTrue(populate is BackupActionResult.Failure, "Populate INT 'not-a-number': ${populate.describe()}") },
            { assertTrue(custom is BackupActionResult.Failure, "Custom action with status=failure: ${custom.describe()}") },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Argument transport
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("KI-04 runOnDevice delivers argument values exactly, without shell expansion")
    fun argumentsAreNotShellExpanded(@Device device: BackupRestoreController): Unit = runBlocking {
        val args = mapOf(
            "price" to "cost=\$5 home=\$HOME",
            "backticks" to "user=`whoami`",
            "subshell" to "\$(echo injected)",
            "quotes" to "it's \"quoted\"",
        )
        val result = device.runOnDevice(PROBE_ACTION, args + ("mode" to "echo"))
        val echoed = (result as? BackupActionResult.Success)?.data.orEmpty()
        assertAll(args.map { (key, value) -> Executable { assertEquals(value, echoed["arg.$key"], "Argument '$key': ${result.describe()}") } })
    }

    @Test
    @Order(41)
    @DisplayName("KI-04b runOnDevice accepts an argument value that ends in a backslash")
    fun trailingBackslashArgument(@Device device: BackupRestoreController): Unit = withinSeconds(120) {
        val value = "C:\\Users\\nia\\"
        val result = device.runOnDevice(PROBE_ACTION, mapOf("mode" to "echo", "path" to value))
        assertEquals(value, (result as? BackupActionResult.Success)?.data?.get("arg.path"), result.describe())
    }

    @Test
    @Order(50)
    @DisplayName("KI-05 runBackupRestoreFlow restores TextFile content with dollar signs and backticks exactly")
    fun flowKeepsShellCharacters(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val content = "Total: \$5 for \$HOME, run `id` now"
        device.runBackupRestoreFlow(StorageDomain.TextFile("notes/shell.txt", content), dir, BackupTransportMode.LOCAL)
        val file = device.readFile("notes/shell.txt")
        assertEquals(content, file["text"], "Restored file: $file")
    }

    // ---------------------------------------------------------------------------------------------
    // Timeouts and debugging
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("KI-06 runOnDevice returns Failure once its timeout expires")
    fun runOnDeviceHonorsTimeout(@Device device: BackupRestoreController): Unit = withinSeconds(120) {
        val start = System.nanoTime()
        val result = device.runOnDevice(SLOW_ACTION, mapOf("sleep_ms" to "5000"), Duration.ofMillis(500))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertAll(
            { assertTrue(result is BackupActionResult.Failure, "A 5 s action with a 500 ms timeout returned ${result.describe()}") },
            { assertTrue(elapsedMs < 3_000, "runOnDevice returned after $elapsedMs ms") },
        )
    }

    @Test
    @Order(70)
    @DisplayName("KI-07 performBackup and performRestore throw IOException once their timeout expires")
    fun backupAndRestoreHonorTimeout(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = withinSeconds(300) {
        device.populate("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to "k", "value" to "v")
        device.stopApp()
        val backup = runCatching { device.performBackup(BackupTransportMode.LOCAL, dir, Duration.ofMillis(200)) }
        val archive = backup.getOrNull() ?: device.performBackup(BackupTransportMode.LOCAL, dir)
        val restore = runCatching { device.performRestore(archive, Duration.ofMillis(200)) }
        assertAll(
            { assertTrue(backup.exceptionOrNull() is IOException, "performBackup(200 ms): ${backup.exceptionOrNull() ?: "returned $archive"}") },
            { assertTrue(restore.exceptionOrNull() is IOException, "performRestore(200 ms): ${restore.exceptionOrNull() ?: "returned normally"}") },
        )
    }

    @Test
    @Order(80)
    @DisplayName("KI-08 runOnDevice(waitForDebugger = true) waits for a debugger before running the action")
    fun waitForDebuggerWaits(@Device device: BackupRestoreController) {
        try {
            withinSeconds(90) {
                val start = System.nanoTime()
                val result = device.runOnDevice(PROBE_ACTION, mapOf("mode" to "echo"), Duration.ofSeconds(10), waitForDebugger = true)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertTrue(
                    result is BackupActionResult.Failure,
                    "No debugger attached, yet the action ran and returned after $elapsedMs ms: ${result.describe()}",
                )
            }
        } finally {
            DeviceChecks.shell(device.serialNumber, "am force-stop $APP_ID")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Seeding and verification
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(90)
    @DisplayName("KI-09 Preference(value = null) removes the key, so it is absent after restore")
    fun nullPreferenceIsRemoved(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        device.populate("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to "legacy_token", "value" to "stale")
        val flow = runCatching {
            device.runBackupRestoreFlow(
                listOf(StorageDomain.Preference(PREFS, "legacy_token", null), StorageDomain.Preference(PREFS, "theme", "dark")),
                dir,
                BackupTransportMode.LOCAL,
            )
        }
        val legacy = device.readPref(PREFS, "legacy_token")
        assertAll(
            { assertNull(flow.exceptionOrNull(), "Flow failed: ${flow.exceptionOrNull()}") },
            { assertEquals("false", legacy["present"], "legacy_token after restore: $legacy") },
        )
    }

    @Test
    @Order(100)
    @DisplayName("KI-10 Seeding the same database primary key twice leaves one row with the latest values")
    fun reseedingReplacesRow(@Device device: BackupRestoreController): Unit = runBlocking {
        val row = arrayOf("storage_type" to "DATABASE", "db_name" to DB, "table" to TABLE, "key_col" to "id", "key_val" to "row1")
        device.populate(*row, "values" to "id=row1&title=first")
        device.populate(*row, "values" to "id=row1&title=second")
        val stored = device.readDbRow(DB, TABLE, "id", "row1")
        assertAll(
            { assertEquals("1", stored["row_count"], "Rows with id=row1: $stored") },
            { assertEquals("second", stored["col.title"], "Row with id=row1: $stored") },
        )
    }

    @Test
    @Order(110)
    @DisplayName("KI-11 runBackupRestoreFlow restores database values containing + = & and %")
    fun flowKeepsDatabaseSpecialCharacters(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        val note = "a+b=c&d 50% off"
        val flow = runCatching {
            device.runBackupRestoreFlow(
                StorageDomain.Database(DB, TABLE, "id", "row1", mapOf("id" to "row1", "note" to note)),
                dir,
                BackupTransportMode.LOCAL,
            )
        }
        val stored = device.readDbRow(DB, TABLE, "id", "row1")
        assertAll(
            { assertNull(flow.exceptionOrNull(), "Flow failed: ${flow.exceptionOrNull()}") },
            { assertEquals(note, stored["col.note"], "Restored row: $stored") },
        )
    }

    @Test
    @Order(111)
    @DisplayName("KI-11b A null StorageDomain.Database column is stored as SQL NULL")
    fun nullDatabaseColumnIsNull(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        device.runBackupRestoreFlow(
            StorageDomain.Database(DB, TABLE, "id", "row2", mapOf("id" to "row2", "deleted_at" to null)),
            dir,
            BackupTransportMode.LOCAL,
        )
        val stored = device.readDbRow(DB, TABLE, "id", "row2")
        assertEquals("<null>", stored["col.deleted_at"], "Restored row: $stored")
    }

    @Test
    @Order(120)
    @DisplayName("KI-12 ACTION_ASSERT_STORAGE checks every column in 'values', not only the first")
    fun assertChecksEveryColumn(@Device device: BackupRestoreController): Unit = runBlocking {
        val table = arrayOf("storage_type" to "DATABASE", "db_name" to DB, "table" to TABLE)
        device.populate(*table, "values" to "id=row1&city=Paris")
        val result = device.assertStorage(*table, "key_col" to "id", "key_val" to "row1", "values" to "id=row1&city=London")
        assertNotEquals("success", result.reportedStatus, "Seeded city=Paris, asserted city=London: ${result.describe()}")
    }

    @Test
    @Order(130)
    @DisplayName("KI-13 ACTION_POPULATE_STORAGE rejects an invalid BOOLEAN instead of storing false")
    fun invalidBooleanIsRejected(@Device device: BackupRestoreController): Unit = runBlocking {
        val result = device.populate(
            "storage_type" to "PREFS",
            "pref_name" to PREFS,
            "pref_key" to "enabled",
            "value" to "yes",
            "value_type" to "BOOLEAN",
        )
        val stored = device.readPref(PREFS, "enabled")
        assertNotEquals("success", result.reportedStatus, "BOOLEAN 'yes' was accepted and stored as $stored")
    }

    @Test
    @Order(140)
    @DisplayName("KI-14 StorageDomain.Database rejects empty columnValues at construction")
    fun emptyDatabaseColumnsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageDomain.Database(DB, TABLE, "id", "row1", emptyMap())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Payload size
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(150)
    @DisplayName("KI-15a runOnDevice delivers a 100 KiB argument")
    fun largeArgument(@Device device: BackupRestoreController): Unit = withinSeconds(120) {
        val value = "n".repeat(100 * 1024)
        val result = runCatching { device.runOnDevice(PROBE_ACTION, mapOf("mode" to "echo", "blob" to value)) }
        val echoed = (result.getOrNull() as? BackupActionResult.Success)?.data?.get("arg.blob")
        assertEquals(value.length, echoed?.length, "100 KiB argument: ${result.exceptionOrNull() ?: result.getOrNull()?.describe()}")
    }

    @Test
    @Order(151)
    @DisplayName("KI-15b runBackupRestoreFlow restores a 40 KiB TextFile")
    fun largeTextFileFlow(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = withinSeconds(300) {
        val content = "0123456789abcdef".repeat(40 * 1024 / 16)
        val flow = runCatching {
            device.runBackupRestoreFlow(StorageDomain.TextFile("notes/large.txt", content), dir, BackupTransportMode.LOCAL)
        }
        val file = device.readFile("notes/large.txt")
        assertAll(
            { assertNull(flow.exceptionOrNull(), "Flow failed: ${flow.exceptionOrNull()?.message?.take(300)}") },
            { assertEquals(sha256(content.toByteArray()), file["sha256"], "Restored file: $file") },
        )
    }

    private suspend fun BackupRestoreController.assertLargeResult(sizeKb: Int) {
        val result = runOnDevice(PROBE_ACTION, mapOf("mode" to "large_result", "size_kb" to "$sizeKb"))
        val blob = (result as? BackupActionResult.Success)?.data?.get("blob")
        assertEquals(sizeKb * 1024, blob?.length, "$sizeKb KiB result: ${result.describe()}")
    }

    @Test
    @Order(160)
    @DisplayName("KI-16a runOnDevice returns a 480 KiB action result")
    fun largeResultBelowOverflowThreshold(@Device device: BackupRestoreController): Unit = withinSeconds(180) {
        device.assertLargeResult(480)
    }

    @Test
    @Order(161)
    @DisplayName("KI-16b runOnDevice returns a 600 KiB action result, above the 500 KB overflow threshold")
    fun largeResultAboveOverflowThreshold(@Device device: BackupRestoreController): Unit = withinSeconds(180) {
        device.assertLargeResult(600)
    }

    @Test
    @Order(170)
    @DisplayName("KI-17 runOnDevice reports an action that throws with a 1 MiB message as Failure with that message")
    fun hugeExceptionMessage(@Device device: BackupRestoreController): Unit = withinSeconds(180) {
        val result = device.runOnDevice(PROBE_ACTION, mapOf("mode" to "throw", "message" to "nia-huge-failure", "message_kb" to "1024"))
        assertTrue(result is BackupActionResult.Failure, result.describe())
        assertTrue((result as BackupActionResult.Failure).errorMessage.contains("nia-huge-failure"), "errorMessage: ${result.errorMessage.take(500)}")
    }

    // ---------------------------------------------------------------------------------------------
    // Extension and packaging
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(190)
    @DisplayName("KI-19 backup-host ships its version resource, so telemetry reports the library version")
    fun versionResourcePresent() {
        val resource = BackupRestoreController::class.java.getResource("/META-INF/androidx.test.backup_backup-host.version")
        assertNotNull(resource, "META-INF/androidx.test.backup_backup-host.version is missing, so BackupRestore.libraryVersion is 'unknown'")
    }

    @Test
    @Order(200)
    @DisplayName("KI-20 Two @Device parameters separated by a @TempDir parameter resolve to two devices")
    fun devicesSeparatedByTempDir(
        @Device source: BackupRestoreController,
        @Suppress("UNUSED_PARAMETER") @TempDir dir: Path,
        @Device target: BackupRestoreController,
    ) {
        assertNotEquals(source.serialNumber, target.serialNumber, "Both parameters resolved to ${source.serialNumber}")
    }

    @Test
    @Order(201)
    @DisplayName("KI-20b A single @Device parameter after a @TempDir parameter resolves to the first device")
    fun deviceAfterTempDir(@Suppress("UNUSED_PARAMETER") @TempDir dir: Path, @Device device: BackupRestoreController) {
        assertEquals(DeviceChecks.onlineSerials().first(), device.serialNumber)
    }

    @Test
    @Order(210)
    @DisplayName("KI-21a Record the install time after a @Device parameter is resolved")
    fun recordInstallTime(@Device device: BackupRestoreController) {
        val installTime = DeviceChecks.lastUpdateTime(device.serialNumber)
        assertTrue(installTime.isNotEmpty(), "No lastUpdateTime for $APP_ID")
        installTimeAfterFirstResolution = installTime
    }

    @Test
    @Order(211)
    @DisplayName("KI-21 Resolving another @Device parameter does not reinstall the app")
    fun noReinstallPerParameter(@Device device: BackupRestoreController) {
        assertNotNull(installTimeAfterFirstResolution, "KI-21a did not run")
        assertEquals(installTimeAfterFirstResolution, DeviceChecks.lastUpdateTime(device.serialNumber), "The app was reinstalled")
    }

    @Test
    @Order(230)
    @DisplayName("KI-23 performBackup(LOCAL) returns an archive that contains the backup data")
    fun localArchiveHasData(@Device device: BackupRestoreController, @TempDir dir: Path): Unit = runBlocking {
        device.populate("storage_type" to "PREFS", "pref_name" to PREFS, "pref_key" to "k", "value" to "v")
        device.stopApp()
        val archive = device.performBackup(BackupTransportMode.LOCAL, dir)
        val entries = ZipFile(archive.toFile()).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(entries.any { it != "token.txt" }, "${archive.fileName} (${Files.size(archive)} bytes) contains only $entries")
    }

    // ---------------------------------------------------------------------------------------------
    // Error contracts
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(240)
    @DisplayName("KI-24 launchApp throws IOException when the activity does not exist")
    fun launchMissingActivity(@Device device: BackupRestoreController) {
        assertThrows(IOException::class.java) { runBlocking { device.launchApp(".DoesNotExistActivity") } }
    }

    @Test
    @Order(250)
    @DisplayName("KI-25 installApk throws IOException for a file that is not an APK")
    fun installInvalidApk(@Device device: BackupRestoreController, @TempDir dir: Path) {
        val apk = dir.resolve("not_an_app.apk")
        apk.toFile().writeText("not an APK")
        assertThrows(IOException::class.java) { runBlocking { device.installApk(apk) } }
    }

    @Test
    @Order(260)
    @DisplayName("KI-26 performRestore throws IOException for a file that is not a backup")
    fun restoreInvalidFile(@Device device: BackupRestoreController, @TempDir dir: Path) {
        val file = dir.resolve("corrupt_backup.zip")
        file.toFile().writeText("not a backup")
        assertThrows(IOException::class.java) { runBlocking { device.performRestore(file) } }
    }

    @Test
    @Order(270)
    @DisplayName("KI-27 backup-host does not ship classes of other artifacts (com.android.tools.environment.Logger)")
    fun noForeignClassesInBackupHost() {
        val copies = javaClass.classLoader.getResources("com/android/tools/environment/Logger.class").toList().map { it.toString() }
        assertFalse(copies.any { "backup-host" in it }, "com.android.tools.environment.Logger is provided by: $copies")
    }
}
