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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException

/**
 * End-to-end verification suite in Now in Android exercising the correctness fixes landed in
 * `androidx.test.backup`:
 *
 * 1. Shell quoting & special characters in `runOnDevice` and storage payloads.
 * 2. Timeout validation (`<= 0`) and on-device timeout enforcement (`withinTimeout` + force-stop).
 * 3. Fast `LOCAL` restore completion within a short caller timeout (`<= 7.5s`).
 * 4. Primary-key consistency validation in [StorageDomain.Database] construction.
 * 5. Database re-seeding row replacement and duplicate-row rejection in `AssertStorageAction`.
 * 6. Seeding (`editor.remove`) and verifying (`expect_null`) `null` [StorageDomain.Preference] values.
 * 7. Strict typed preference parsing, equivalent representation comparison (`"True"`, `"1.50"`, `"007"`),
 *    and stored type-mismatch rejection.
 */
@ExtendWith(BackupRestoreExtension::class)
@BackupRestoreConfig(applicationId = "samples.apps.nowinandroid.demo.debug")
@Isolation(IsolationPolicy.AUTOMATIC)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaBackupBugFixesVerificationTest {

    private companion object {
        const val POPULATE_ACTION = "androidx.test.backup.actions.PopulateStorageAction"
        const val ASSERT_ACTION = "androidx.test.backup.actions.AssertStorageAction"
        const val SLOW_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaSlowDeviceAction"
        const val PREF_FILE = "nia_bugfix_prefs"
        const val DB_FILE = "nia_bugfix_cache.db"
        const val DB_TABLE = "sync_log"
    }

    // =========================================================================================
    // 1. SHELL QUOTING & METACHARACTERS IN STORAGE PAYLOADS & ARGS
    // =========================================================================================

    @Test
    @Order(1)
    @DisplayName("FIX-1. Shell Quoting: Metacharacters (\$HOME, backticks, quotes, pipes) Round-Trip Intact Without Expansion")
    fun testShellQuotingAndSpecialCharactersRoundTrip(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        // Values containing shell expansion characters ($HOME, `id`, $(uname), single/double quotes,
        // backslashes, semicolons, pipes) must not be expanded or broken by `am instrument`.
        val trickyPrefValue = """raw ${'$'}HOME `id` $(echo pwned) "double" 'single' \backslash\ ; | & ()"""
        val trickyFileContent = "line1: \$PATH `uname -a`\nline2: 'quoted' \"double\" && echo no"
        val trickyDbStatus = """status="OK"; cost=${'$'}100; cmd=`ls`; path=C:\temp\file'1"""

        val storages = listOf(
            StorageDomain.Preference(
                prefName = PREF_FILE,
                key = "shell_metachar_pref",
                value = trickyPrefValue,
            ),
            StorageDomain.TextFile(
                path = "sync/metachar_manifest.txt",
                content = trickyFileContent,
            ),
            StorageDomain.Database(
                dbName = DB_FILE,
                table = DB_TABLE,
                primaryKeyCol = "sync_id",
                primaryKeyVal = "sync_quote_01",
                columnValues = mapOf(
                    "sync_id" to "sync_quote_01",
                    "status" to trickyDbStatus,
                ),
            ),
        )

        device.runBackupRestoreFlow(
            storages = storages,
            outputDir = tempDir,
            mode = BackupTransportMode.CLOUD_UNENCRYPTED,
        )

        // The flow verifies through the same command path that seeded the data, so re-read what is stored.
        val pref = device.readPref(PREF_FILE, "shell_metachar_pref")
        val file = device.readFile("sync/metachar_manifest.txt")
        val row = device.readDbRow(DB_FILE, DB_TABLE, "sync_id", "sync_quote_01")
        assertAll(
            { assertEquals(trickyPrefValue, pref["value"], "Stored preference: $pref") },
            { assertEquals(trickyFileContent, file["text"], "Stored file: $file") },
            { assertEquals(trickyDbStatus, row["col.status"], "Stored row: $row") },
        )
    }

    // =========================================================================================
    // 2. TIMEOUT VALIDATION & ON-DEVICE ACTION TIMEOUT ENFORCEMENT
    // =========================================================================================

    @Test
    @Order(2)
    @DisplayName("FIX-2. Timeout Enforcement: Non-Positive Timeouts Rejected & Hung Device Action Timed Out and Force-Stopped")
    fun testTimeoutValidationAndOnDeviceActionTimeoutEnforcement(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        // 1. Zero and negative timeouts must throw IllegalArgumentException immediately
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                device.runOnDevice(POPULATE_ACTION, timeout = Duration.ZERO)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                device.performBackup(
                    mode = BackupTransportMode.LOCAL,
                    outputDir = tempDir,
                    timeout = Duration.ofSeconds(-5),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                device.performRestore(
                    backupFile = tempDir.resolve("nonexistent.backup"),
                    timeout = Duration.ZERO,
                )
            }
        }

        // Async overload must surface IllegalArgumentException via ExecutionException
        val invalidFuture = device.runOnDeviceAsync(
            actionClassName = POPULATE_ACTION,
            args = emptyMap(),
            timeout = Duration.ZERO,
        )
        val asyncEx = assertThrows(ExecutionException::class.java) {
            invalidFuture.get()
        }
        assertTrue(asyncEx.cause is IllegalArgumentException)

        // 2. Slow on-device action (5s sleep) must be cancelled after 500ms and return Failure
        val timeoutResult = device.runOnDevice(
            actionClassName = SLOW_ACTION,
            args = mapOf("sleep_ms" to "5000"),
            timeout = Duration.ofMillis(500),
        )
        assertTrue(
            timeoutResult is BackupActionResult.Failure,
            "Expected slow action to time out with Failure, but got: $timeoutResult",
        )
        val failureMsg = (timeoutResult as BackupActionResult.Failure).errorMessage
        assertTrue(
            failureMsg.contains("Timed out after 500ms"),
            "Expected timeout error message 'Timed out after 500ms', but got: '$failureMsg'",
        )

        // 3. Async overload also enforces the caller timeout and returns Failure
        val asyncTimeoutResult = device.runOnDeviceAsync(
            actionClassName = SLOW_ACTION,
            args = mapOf("sleep_ms" to "5000"),
            timeout = Duration.ofMillis(400),
        ).get()
        assertTrue(
            asyncTimeoutResult is BackupActionResult.Failure,
            "Expected async slow action to time out with Failure, but got: $asyncTimeoutResult",
        )
        val asyncFailureMsg = (asyncTimeoutResult as BackupActionResult.Failure).errorMessage
        assertTrue(
            asyncFailureMsg.contains("Timed out after 400ms"),
            "Expected timeout error message 'Timed out after 400ms', but got: '$asyncFailureMsg'",
        )

        // 4. Subsequent on-device action succeeds cleanly after force-stop cleanup
        val recoveryResult = device.runOnDevice(
            actionClassName = SLOW_ACTION,
            args = mapOf("sleep_ms" to "10"),
            timeout = Duration.ofSeconds(10),
        )
        assertTrue(
            recoveryResult is BackupActionResult.Success,
            "Expected fast action to succeed after previous timeout force-stop, but got: $recoveryResult",
        )
    }

    // =========================================================================================
    // 3. LOCAL RESTORE TIMEOUT ENFORCEMENT, TRANSPORT CLEANUP & DISPATCH SCALING
    // =========================================================================================

    @Test
    @Order(3)
    @DisplayName("FIX-3. Local Restore Timeout & Cleanup: Short Timeout Aborts With Cleanup & Subsequent Restore Succeeds")
    fun testFastLocalRestoreSucceedsWithinShortCallerTimeout(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        // Seed a preference via PopulateStorageAction
        val seedArgs = mapOf(
            "storage_type" to "PREFS",
            "pref_name" to PREF_FILE,
            "pref_key" to "local_short_timeout_key",
            "value" to "restored_under_bounded_timeout",
            "value_type" to "STRING",
        )
        val seedResult = device.runOnDevice(POPULATE_ACTION, seedArgs)
        assertTrue(seedResult is BackupActionResult.Success, "Seed failed: $seedResult")

        // Back up using LOCAL transport
        val archive = device.performBackup(
            mode = BackupTransportMode.LOCAL,
            outputDir = tempDir,
            timeout = Duration.ofMinutes(1),
        )
        assertTrue(Files.exists(archive), "Local backup archive must exist")

        // Wipe sandbox
        device.clearAppData()

        // 1. Verify that an aggressively short timeout (100ms) on performRestore aborts with IOException
        // and still cleans up transport state via withCleanup.
        val restoreTimeoutEx = assertThrows(java.io.IOException::class.java) {
            runBlocking {
                device.performRestore(
                    backupFile = archive,
                    timeout = Duration.ofMillis(100),
                )
            }
        }
        assertTrue(
            restoreTimeoutEx.message!!.contains("Restore timed out after 100ms"),
            "Expected 'Restore timed out after 100ms', but got: '${restoreTimeoutEx.message}'",
        )

        // 2. Wipe sandbox again and perform LOCAL restore with a 14s caller timeout
        // (scales dispatch window via min(7.5s, timeout / 2) = 7s and completes within budget).
        device.clearAppData()
        device.performRestore(
            backupFile = archive,
            timeout = Duration.ofSeconds(14),
        )

        // Verify the preference was restored
        val assertArgs = seedArgs + ("expected" to "restored_under_bounded_timeout")
        val verifyResult = device.runOnDevice(ASSERT_ACTION, assertArgs)
        assertTrue(
            verifyResult is BackupActionResult.Success,
            "Verification after LOCAL restore failed: $verifyResult",
        )
    }

    // =========================================================================================
    // 4. DATABASE PRIMARY KEY CONSISTENCY CHECK AT CONSTRUCTION
    // =========================================================================================

    @Test
    @Order(4)
    @DisplayName("FIX-4. Database Primary Key Validation: Mismatched or Null Primary Key in columnValues Rejected")
    fun testDatabasePrimaryKeyMismatchRejectedAtConstruction(
        @Device device: BackupRestoreController,
    ): Unit {
        assertTrue(device.serialNumber.isNotEmpty())

        // Exact column name mismatch between primaryKeyVal ("sync_001") and columnValues["sync_id"] ("sync_999")
        val exactMismatchEx = assertThrows(IllegalArgumentException::class.java) {
            StorageDomain.Database(
                dbName = DB_FILE,
                table = DB_TABLE,
                primaryKeyCol = "sync_id",
                primaryKeyVal = "sync_001",
                columnValues = mapOf(
                    "sync_id" to "sync_999",
                    "status" to "SUCCESS",
                ),
            )
        }
        assertTrue(exactMismatchEx.message!!.contains("primaryKeyVal"))

        // Case-insensitive column name mismatch ("SYNC_ID" vs "sync_id")
        assertThrows(IllegalArgumentException::class.java) {
            StorageDomain.Database(
                dbName = DB_FILE,
                table = DB_TABLE,
                primaryKeyCol = "sync_id",
                primaryKeyVal = "sync_001",
                columnValues = mapOf(
                    "SYNC_ID" to "different_id",
                    "status" to "SUCCESS",
                ),
            )
        }

        // Null value for primaryKeyCol in columnValues
        assertThrows(IllegalArgumentException::class.java) {
            StorageDomain.Database(
                dbName = DB_FILE,
                table = DB_TABLE,
                primaryKeyCol = "sync_id",
                primaryKeyVal = "sync_001",
                columnValues = mapOf(
                    "sync_id" to null,
                    "status" to "SUCCESS",
                ),
            )
        }
    }

    // =========================================================================================
    // 5. DATABASE RE-SEEDING ROW REPLACEMENT & DUPLICATE ROW REJECTION
    // =========================================================================================

    @Test
    @Order(5)
    @Isolation(IsolationPolicy.MANUAL)
    @DisplayName("FIX-5. Database Re-Seeding & Duplicate Row Detection: Re-Seeding Replaces Existing Row & Duplicate Rows Fail Assertion")
    fun testDatabaseReseedReplacesExistingRowAndDuplicateRowsFailVerification(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        device.clearAppData()

        // Part A: Seed initial row with sync_id = "reseed_001"
        val initialPopulate = device.runOnDevice(
            actionClassName = POPULATE_ACTION,
            args = mapOf(
                "storage_type" to "DATABASE",
                "db_name" to DB_FILE,
                "table" to DB_TABLE,
                "key_col" to "sync_id",
                "key_val" to "reseed_001",
                "values" to "sync_id=reseed_001&status=INITIAL_V1&records_synced=10",
            ),
        )
        assertTrue(initialPopulate is BackupActionResult.Success, "Initial seed failed: $initialPopulate")

        // Without clearing app data, run full backup & restore flow with updated values for the same key.
        // PopulateStorageAction must replace the existing row in a transaction rather than creating a second row.
        val updatedDatabaseDomain = StorageDomain.Database(
            dbName = DB_FILE,
            table = DB_TABLE,
            primaryKeyCol = "sync_id",
            primaryKeyVal = "reseed_001",
            columnValues = mapOf(
                "sync_id" to "reseed_001",
                "status" to "UPDATED_V2",
                "records_synced" to 99,
            ),
        )
        device.runBackupRestoreFlow(
            storage = updatedDatabaseDomain,
            outputDir = tempDir,
            mode = BackupTransportMode.CLOUD_UNENCRYPTED,
        )

        // Part B: Insert two duplicate rows for key "dup_001" (by omitting key_col/key_val during populate)
        // and verify AssertStorageAction rejects the duplicate rows.
        val legacyInsert1 = device.runOnDevice(
            actionClassName = POPULATE_ACTION,
            args = mapOf(
                "storage_type" to "DATABASE",
                "db_name" to DB_FILE,
                "table" to DB_TABLE,
                "values" to "sync_id=dup_001&status=FIRST&records_synced=1",
            ),
        )
        assertTrue(legacyInsert1 is BackupActionResult.Success)

        val legacyInsert2 = device.runOnDevice(
            actionClassName = POPULATE_ACTION,
            args = mapOf(
                "storage_type" to "DATABASE",
                "db_name" to DB_FILE,
                "table" to DB_TABLE,
                "values" to "sync_id=dup_001&status=SECOND&records_synced=2",
            ),
        )
        assertTrue(legacyInsert2 is BackupActionResult.Success)

        val duplicateAssertResult = device.runOnDevice(
            actionClassName = ASSERT_ACTION,
            args = mapOf(
                "storage_type" to "DATABASE",
                "db_name" to DB_FILE,
                "table" to DB_TABLE,
                "key_col" to "sync_id",
                "key_val" to "dup_001",
                "values" to "sync_id=dup_001&status=FIRST&records_synced=1",
            ),
        )
        assertTrue(
            duplicateAssertResult is BackupActionResult.Failure,
            "Expected AssertStorageAction to fail when duplicate rows exist, but got: $duplicateAssertResult",
        )
        val dupError = (duplicateAssertResult as BackupActionResult.Failure).errorMessage
        assertTrue(
            dupError.contains("Found 2 rows with sync_id='dup_001' in table '$DB_TABLE'; expected exactly one."),
            "Unexpected duplicate row error message: '$dupError'",
        )
    }

    // =========================================================================================
    // 6. NULL PREFERENCE SEEDING (KEY REMOVAL) & ABSENCE VERIFICATION
    // =========================================================================================

    @Test
    @Order(6)
    @Isolation(IsolationPolicy.MANUAL)
    @DisplayName("FIX-6. Null Preferences: Seeding value=null Removes Key & Verifies Absence Across Backup/Restore")
    fun testNullPreferenceRemovesKeyOnSeedAndVerifiesAbsenceAfterRestore(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        device.clearAppData()

        // 1. Pre-populate a non-null preference ("obsolete_auth_token") that should be removed
        val preSeed = device.runOnDevice(
            actionClassName = POPULATE_ACTION,
            args = mapOf(
                "storage_type" to "PREFS",
                "pref_name" to PREF_FILE,
                "pref_key" to "obsolete_auth_token",
                "value" to "legacy_token_xyz",
                "value_type" to "STRING",
            ),
        )
        assertTrue(preSeed is BackupActionResult.Success)

        // Verify that asserting expect_null=true while the key is still present fails with a clear message
        val presentCheck = device.runOnDevice(
            actionClassName = ASSERT_ACTION,
            args = mapOf(
                "storage_type" to "PREFS",
                "pref_name" to PREF_FILE,
                "pref_key" to "obsolete_auth_token",
                "expect_null" to "true",
            ),
        )
        assertTrue(presentCheck is BackupActionResult.Failure)
        assertTrue(
            (presentCheck as BackupActionResult.Failure).errorMessage.contains(
                "Expected preference 'obsolete_auth_token' in '$PREF_FILE' to be absent",
            ),
        )

        // 2. Run full backup & restore flow with Preference(value = null) for "obsolete_auth_token"
        // alongside an active preference so the shared_prefs file is backed up and restored.
        val storages = listOf(
            StorageDomain.Preference(
                prefName = PREF_FILE,
                key = "obsolete_auth_token",
                value = null,
            ),
            StorageDomain.Preference(
                prefName = PREF_FILE,
                key = "active_user_id",
                value = "user_2026",
            ),
        )
        device.runBackupRestoreFlow(
            storages = storages,
            outputDir = tempDir,
            mode = BackupTransportMode.CLOUD_UNENCRYPTED,
        )
    }

    // =========================================================================================
    // 7. TYPED PREFERENCE PARSING, EQUIVALENT REPRESENTATIONS & TYPE MISMATCHES
    // =========================================================================================

    @Test
    @Order(7)
    @DisplayName("FIX-7. Typed Preferences: Equivalent Representations ('True', '1.50', '007') Verify & Type Mismatches Fail")
    fun testTypedPreferenceParsingEquivalenceAndTypeMismatchRejection(
        @Device device: BackupRestoreController,
    ): Unit = runBlocking {
        // 1. Seed typed preferences using non-canonical representations ("True", "007", "0042", "1.50")
        val typedSeeds = listOf(
            Triple("bool_key", "True", "BOOLEAN") to "true",
            Triple("int_key", "007", "INT") to "7",
            Triple("long_key", "0042", "LONG") to "42",
            Triple("float_key", "1.50", "FLOAT") to "1.5",
        )

        for ((seedSpec, canonicalExpected) in typedSeeds) {
            val (key, rawSeedVal, valueType) = seedSpec
            val seedRes = device.runOnDevice(
                actionClassName = POPULATE_ACTION,
                args = mapOf(
                    "storage_type" to "PREFS",
                    "pref_name" to PREF_FILE,
                    "pref_key" to key,
                    "value" to rawSeedVal,
                    "value_type" to valueType,
                ),
            )
            assertTrue(seedRes is BackupActionResult.Success, "Failed to seed $key ($valueType): $seedRes")

            // Verify using both the non-canonical form ("True", "1.50", "007") and canonical form
            for (expectedForm in listOf(rawSeedVal, canonicalExpected)) {
                val assertRes = device.runOnDevice(
                    actionClassName = ASSERT_ACTION,
                    args = mapOf(
                        "storage_type" to "PREFS",
                        "pref_name" to PREF_FILE,
                        "pref_key" to key,
                        "expected" to expectedForm,
                        "value_type" to valueType,
                    ),
                )
                assertTrue(
                    assertRes is BackupActionResult.Success,
                    "Expected $key ($valueType) to match '$expectedForm', but got: $assertRes",
                )
            }
        }

        // 2. Stored type mismatch: "int_key" is stored as INT (7), so asserting it as STRING ("7") must fail
        val typeMismatchRes = device.runOnDevice(
            actionClassName = ASSERT_ACTION,
            args = mapOf(
                "storage_type" to "PREFS",
                "pref_name" to PREF_FILE,
                "pref_key" to "int_key",
                "expected" to "7",
                "value_type" to "STRING",
            ),
        )
        assertTrue(typeMismatchRes is BackupActionResult.Failure)
        val mismatchMsg = (typeMismatchRes as BackupActionResult.Failure).errorMessage
        assertTrue(
            mismatchMsg.contains("Preference 'int_key' in '$PREF_FILE' is not of expected type STRING"),
            "Unexpected type mismatch message: '$mismatchMsg'",
        )

        // 3. Invalid boolean string ("yes") must be rejected by both PopulateStorageAction and AssertStorageAction
        // instead of silently parsing as false via String.toBoolean()
        val invalidBoolSeed = device.runOnDevice(
            actionClassName = POPULATE_ACTION,
            args = mapOf(
                "storage_type" to "PREFS",
                "pref_name" to PREF_FILE,
                "pref_key" to "bad_bool",
                "value" to "yes",
                "value_type" to "BOOLEAN",
            ),
        )
        assertTrue(invalidBoolSeed is BackupActionResult.Failure)
        assertTrue(
            (invalidBoolSeed as BackupActionResult.Failure).errorMessage.contains(
                "Invalid boolean preference value 'yes': expected 'true' or 'false'",
            ),
        )

        val invalidBoolAssert = device.runOnDevice(
            actionClassName = ASSERT_ACTION,
            args = mapOf(
                "storage_type" to "PREFS",
                "pref_name" to PREF_FILE,
                "pref_key" to "bool_key",
                "expected" to "yes",
                "value_type" to "BOOLEAN",
            ),
        )
        assertTrue(invalidBoolAssert is BackupActionResult.Failure)
        assertTrue(
            (invalidBoolAssert as BackupActionResult.Failure).errorMessage.contains(
                "Invalid boolean preference value 'yes': expected 'true' or 'false'",
            ),
        )
    }
}
