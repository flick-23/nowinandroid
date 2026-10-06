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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.concurrent.TimeUnit

/**
 * Production-Grade End-to-End Backup & Restore Test Suite for Now in Android.
 *
 * Demonstrates the complete surface area of the AndroidX Backup Test framework:
 *
 * 1. Configuration & Isolation Annotations:
 *    - [@BackupRestoreConfig]: Global application target declaration (`samples.apps.nowinandroid.demo.debug`).
 *    - [@ExtendWith(BackupRestoreExtension::class)]: JUnit 5 parameter resolution and lifecycle extension.
 *    - [@Isolation(IsolationPolicy.AUTOMATIC)]: Default sandbox isolation between tests (`pm clear`).
 *    - [@Isolation(IsolationPolicy.MANUAL)]: Method-level override to preserve sandbox state across steps.
 *
 * 2. Parameter Qualifiers on [@Device]:
 *    - Unqualified [@Device]: Resolves default online emulator/device.
 *    - Role-based [@Device(role = "source")] and [@Device(role = "target")]: Multi-device orchestration.
 *    - Hardware/Serial qualifier [@Device(serial = "emulator-5554")]: Explicit hardware targeting.
 *    - API-level qualifier [@Device(api = 34)]: Minimum or exact Android platform SDK targeting.
 *
 * 3. All [StorageDomain] Types:
 *    - [StorageDomain.Preference]: Strongly typed entries (String, Boolean, Int, Long, Float).
 *    - [StorageDomain.TextFile]: Relative internal sandbox text files.
 *    - [StorageDomain.BinaryFile]: Raw binary payloads (byte arrays) such as security/sync keys.
 *    - [StorageDomain.Database]: Structured SQLite / Room database table records.
 *
 * 4. All [BackupTransportMode] Options:
 *    - [BackupTransportMode.CLOUD_UNENCRYPTED]: Standard Google Drive cloud backup transport.
 *    - [BackupTransportMode.CLOUD_ENCRYPTED]: Client-side end-to-end encrypted cloud backup transport.
 *    - [BackupTransportMode.DEVICE_TO_DEVICE]: Direct peer-to-peer / USB / Wi-Fi migration transport.
 *    - [BackupTransportMode.LOCAL]: Offline, low-latency filesystem backup transport.
 *
 * 5. Controller Operations ([BackupRestoreController]):
 *    - High-level declarative flows ([BackupRestoreController.runBackupRestoreFlow]).
 *    - Low-level step-by-step lifecycle ([performBackup], [performRestore], [clearAppData], [stopApp], [launchApp]).
 *    - Inspection and diagnostics ([fetchDeviceLogs], [clearDeviceLogs], [pullFile]).
 *    - Custom on-device actions ([runOnDevice], [androidx.test.backup.BackupDeviceAction]).
 *    - Asynchronous execution ([runBackupRestoreFlowAsync], [runOnDeviceAsync]).
 */
@ExtendWith(BackupRestoreExtension::class)
@BackupRestoreConfig(applicationId = "samples.apps.nowinandroid.demo.debug")
@Isolation(IsolationPolicy.AUTOMATIC)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaBackupRestoreTest {

    private companion object {
        const val SEED_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaSeedDataAction"
        const val VERIFY_ACTION = "com.google.samples.apps.nowinandroid.backup.NiaVerifyDataAction"
    }

    // =========================================================================================
    // 1. DECLARATIVE MULTI-DOMAIN FLOW (Preferences, Text Files, Binary Keys & Database)
    // =========================================================================================

    @Test
    @Order(1)
    @DisplayName("1. Declarative Multi-Domain Flow: Typed Preferences, Files, Binary Keys & Database")
    fun testDeclarativeMultiDomainBackupRestore(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        val binarySessionToken = byteArrayOf(0x4E, 0x49, 0x41, 0x5F, 0x54, 0x4F, 0x4B, 0x45, 0x4E, 0x5F, 0x30, 0x31)

        val storages = listOf(
            // String Preference: User ID / session identifier
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "user_id",
                value = "nia_production_user_42",
            ),
            // Boolean Preference: Dark mode preference
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "dark_theme_enabled",
                value = true,
            ),
            // Int Preference: Count of followed topics
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "followed_topics_count",
                value = 5,
            ),
            // Long Preference: Last sync epoch timestamp
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "last_sync_timestamp",
                value = 1725800000000L,
            ),
            // Float Preference: UI font scale factor
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "ui_font_scale",
                value = 1.25f,
            ),
            // Text File: Offline sync manifest
            StorageDomain.TextFile(
                path = "sync/sync_manifest.json",
                content = "{\"sync_version\": 1, \"status\": \"COMPLETED\", \"author\": \"AndroidX Backup\"}",
            ),
            // Binary File: Cryptographic sync session key
            StorageDomain.BinaryFile(
                path = "security/session.key",
                content = binarySessionToken,
            ),
            // SQLite Database Record: Custom offline sync log table
            StorageDomain.Database(
                dbName = "nia_offline_cache.db",
                table = "sync_log",
                primaryKeyCol = "sync_id",
                primaryKeyVal = "sync_001",
                columnValues = mapOf(
                    "status" to "SUCCESS",
                    "records_synced" to 42,
                    "sync_id" to "sync_001",
                ),
            ),
        )

        // runBackupRestoreFlow automatically seeds, backs up, wipes, restores, and validates
        val controller = device.runBackupRestoreFlow(
            storages = storages,
            outputDir = tempDir,
            mode = BackupTransportMode.CLOUD_UNENCRYPTED,
        )
        assertNotNull(controller, "Controller instance returned by flow must not be null")

        // Launch restored application to verify UI process resiliency, then stop before the next test's pm clear
        device.launchApp()
        device.stopApp()
    }

    // =========================================================================================
    // 2. SINGLE-STORAGE OVERLOAD & CLOUD TRANSPORT FLOW
    // =========================================================================================

    @Test
    @Order(2)
    @DisplayName("2. Single-Storage Overload & Cloud Transport Flow")
    fun testSingleDomainCloudTransportFlow(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        val singleStorage = StorageDomain.Preference(
            prefName = "nia_user_settings",
            key = "user_handle",
            value = "@android_dev_expert",
        )

        // Demonstrates the single StorageDomain overload with BackupTransportMode.CLOUD_UNENCRYPTED
        val resultController = device.runBackupRestoreFlow(
            storage = singleStorage,
            outputDir = tempDir,
            mode = BackupTransportMode.CLOUD_UNENCRYPTED,
        )
        assertEquals(device.serialNumber, resultController.serialNumber)
    }

    // =========================================================================================
    // 3. DEVICE QUALIFIERS (serial, api) & ASYNCHRONOUS EXECUTION
    // =========================================================================================

    @Test
    @Order(3)
    @DisplayName("3. Device Qualification Parameters (serial & api) & Asynchronous Cloud Encrypted Flow")
    fun testAsynchronousCloudEncryptedFlowWithDeviceQualifiers(
        @Device(serial = "emulator-5554", api = 34) device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        assertEquals("emulator-5554", device.serialNumber)
        assertTrue(device.apiLevel >= 31, "Device API level must be at least 31")

        val storages = listOf(
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "encrypted_cloud_user",
                value = "nia_secure_user@example.com",
            ),
            StorageDomain.Preference(
                prefName = "nia_user_settings",
                key = "cloud_sync_enabled",
                value = true,
            ),
            StorageDomain.TextFile(
                path = "sync/encrypted_token.jwt",
                content = "HEADER.ENCRYPTED_PAYLOAD_NIA_777888.SIGNATURE",
            ),
        )

        // Demonstrates runBackupRestoreFlowAsync returning a ListenableFuture
        val future = device.runBackupRestoreFlowAsync(
            storages = storages,
            outputDir = tempDir,
            mode = BackupTransportMode.CLOUD_ENCRYPTED,
        )

        val completedController = future.get(5, TimeUnit.MINUTES)
        assertEquals(device.serialNumber, completedController.serialNumber)
    }

    // =========================================================================================
    // 4. MANUAL ISOLATION POLICY & GRANULAR CONTROLLER LIFECYCLE APIS
    // =========================================================================================

    @Test
    @Order(4)
    @Isolation(IsolationPolicy.MANUAL)
    @DisplayName("4. Manual Isolation Policy & Granular Controller Lifecycle APIs (Logs, Pull, Launch with Extras)")
    fun testGranularControllerLifecycleWithManualIsolation(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        // 1. Diagnostics: Clear device logcat buffer
        device.clearDeviceLogs()

        // 2. Launch app with explicit activity class, action, and intent extras
        device.launchApp(
            activityClass = "com.google.samples.apps.nowinandroid.MainActivity",
            intentExtras = mapOf("launch_source" to "manual_isolation_audit", "test_mode" to "enabled"),
            action = "android.intent.action.MAIN",
        )

        // 3. Stop app before seeding
        device.stopApp()

        // 4. Seed test state via custom on-device action with timeout and debugger flags
        val seedResult = device.runOnDevice(
            actionClassName = SEED_ACTION,
            args = emptyMap(),
            timeout = Duration.ofSeconds(60),
            waitForDebugger = false,
        )
        assertTrue(seedResult is BackupActionResult.Success, "Seed action must succeed")

        // 5. Low-level backup pass with custom timeout
        val backupArchive = device.performBackup(
            mode = BackupTransportMode.CLOUD_UNENCRYPTED,
            outputDir = tempDir,
            timeout = Duration.ofMinutes(1),
        )
        assertTrue(Files.exists(backupArchive), "Backup archive must be written to tempDir")

        // 6. Clear app data (sandbox wipe)
        device.clearAppData()

        // 7. Low-level restore pass with custom timeout
        device.performRestore(
            backupFile = backupArchive,
            timeout = Duration.ofMinutes(3),
        )

        // 8. Verify restored state via custom on-device action
        val verifyResult = device.runOnDevice(
            actionClassName = VERIFY_ACTION,
            args = emptyMap(),
            timeout = Duration.ofSeconds(60),
        )
        if (verifyResult !is BackupActionResult.Success) {
            val errorMsg = (verifyResult as? BackupActionResult.Failure)?.errorMessage ?: verifyResult.toString()
            error("Verification failed: $errorMsg")
        }

        // 9. Diagnostics: Fetch recent logcat window to host file
        val deviceLogDestination = tempDir.resolve("nia_device_audit.log")
        device.fetchDeviceLogs(
            destinationPath = deviceLogDestination,
            duration = Duration.ofSeconds(15),
        )
        assertTrue(Files.exists(deviceLogDestination), "Device logs file must exist on host")

        // 10. File pull: Pull device configuration file to host for forensic verification
        val pulledHostsDestination = tempDir.resolve("pulled_device_hosts.txt")
        device.pullFile(
            devicePath = "/system/etc/hosts",
            hostDestination = pulledHostsDestination,
        )
        assertTrue(Files.exists(pulledHostsDestination), "Pulled device file must exist on host")
        val pulledContent = String(Files.readAllBytes(pulledHostsDestination), Charsets.UTF_8)
        assertTrue(pulledContent.contains("localhost"), "Pulled hosts file must contain localhost")
    }

    // =========================================================================================
    // 5. MULTI-DEVICE D2D MIGRATION: ROLE-BASED ORCHESTRATION (Source -> Target)
    // =========================================================================================

    @Test
    @Order(5)
    @DisplayName("5. Multi-Device D2D Migration: Role-Based Orchestration (Source -> Target)")
    fun testMultiDeviceD2DMigration(
        @Device(role = "source") sourceDevice: BackupRestoreController,
        @Device(role = "target") targetDevice: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        check(sourceDevice.serialNumber != targetDevice.serialNumber) {
            "Multi-device migration requires 2 distinct connected emulators/devices, but got source=${sourceDevice.serialNumber} and target=${targetDevice.serialNumber}"
        }

        // 1. Establish clean baseline on both devices
        sourceDevice.clearAppData()
        targetDevice.clearAppData()

        // 2. START ON SOURCE DEVICE: Seed Proto DataStore, Room SQLite, FTS indexes, cache
        val seedResult = sourceDevice.runOnDevice(
            actionClassName = SEED_ACTION,
            args = emptyMap(),
            timeout = Duration.ofSeconds(60),
        )
        assertTrue(seedResult is BackupActionResult.Success, "Seeding on source device failed: $seedResult")

        sourceDevice.launchApp()

        // 3. CAPTURE D2D BACKUP FROM SOURCE DEVICE
        sourceDevice.stopApp()
        val d2dArchive = sourceDevice.performBackup(
            mode = BackupTransportMode.DEVICE_TO_DEVICE,
            outputDir = tempDir,
            timeout = Duration.ofMinutes(3),
        )
        assertTrue(Files.exists(d2dArchive), "D2D backup archive must exist on host")

        // 4. RESTORE D2D BACKUP ONTO TARGET DEVICE
        targetDevice.stopApp()
        targetDevice.performRestore(backupFile = d2dArchive, timeout = Duration.ofMinutes(3))

        // Verify restored state on target device
        val verifyResult = targetDevice.runOnDevice(
            actionClassName = VERIFY_ACTION,
            args = emptyMap(),
            timeout = Duration.ofSeconds(60),
        )
        if (verifyResult !is BackupActionResult.Success) {
            val errMsg = (verifyResult as? BackupActionResult.Failure)?.errorMessage ?: verifyResult.toString()
            error("Verification failed on target device: $errMsg")
        }

        // 5. END ON TARGET DEVICE: Launch app on target device directly into restored UI
        targetDevice.launchApp()
    }

    // =========================================================================================
    // 6. LOCAL TRANSPORT: OFFLINE BACKUP AND RESTORE ACROSS APP RESETS
    // =========================================================================================

    @Test
    @Order(6)
    @DisplayName("6. Local Transport: Offline Backup & Restore Across App Resets")
    fun testLocalBackupAndRestore(
        @Device device: BackupRestoreController,
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        // 1. Clean sandbox and seed user state
        device.clearAppData()
        device.stopApp()
        val seedResult = device.runOnDevice(SEED_ACTION, timeout = Duration.ofSeconds(60))
        assertTrue(seedResult is BackupActionResult.Success, "Failed to seed user data: $seedResult")

        // 2. Perform backup with LOCAL transport mode
        device.stopApp()
        val backupArchive = device.performBackup(
            mode = BackupTransportMode.LOCAL,
            outputDir = tempDir,
            timeout = Duration.ofMinutes(3),
        )
        assertTrue(Files.exists(backupArchive), "Local backup archive was not created: $backupArchive")

        // 3. Reset sandbox
        device.clearAppData()
        device.stopApp()

        // 4. Restore backup and verify data fidelity
        device.performRestore(backupFile = backupArchive, timeout = Duration.ofMinutes(3))
        val verifyResult = device.runOnDevice(VERIFY_ACTION, timeout = Duration.ofSeconds(60))
        assertTrue(verifyResult is BackupActionResult.Success, "Data verification failed after LOCAL restore: $verifyResult")

        // 5. Verify app launches cleanly with restored data
        device.launchApp()
    }
}
