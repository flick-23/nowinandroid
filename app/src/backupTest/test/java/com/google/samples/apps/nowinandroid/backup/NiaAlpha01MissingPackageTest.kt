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

import androidx.test.backup.host.BackupRestoreConfig
import androidx.test.backup.host.BackupRestoreController
import androidx.test.backup.host.BackupRestoreExtension
import androidx.test.backup.host.BackupTransportMode
import androidx.test.backup.host.Device
import androidx.test.backup.host.Isolation
import androidx.test.backup.host.IsolationPolicy
import androidx.test.backup.host.StorageDomain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path

private const val MISSING_APP_ID = "com.example.backup.notinstalled"

/**
 * Targets an application that is not installed, so every device command fails. Each operation
 * must surface that failure as an [IOException] instead of reporting success.
 */
@ExtendWith(BackupRestoreExtension::class)
@BackupRestoreConfig(applicationId = MISSING_APP_ID)
@Isolation(IsolationPolicy.MANUAL)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaAlpha01MissingPackageTest {

    @Test
    @Order(1)
    @DisplayName("KI-18 clearAppData throws IOException when pm clear fails")
    fun clearAppDataFailure(@Device device: BackupRestoreController) {
        val pmClear = DeviceChecks.shell(device.serialNumber, "pm clear $MISSING_APP_ID")
        assertNotEquals(0, pmClear.exitCode, "Precondition: pm clear must fail for $MISSING_APP_ID: ${pmClear.stdout}")
        assertThrows(IOException::class.java) { runBlocking { device.clearAppData() } }
    }

    @Test
    @Order(2)
    @DisplayName("KI-24b launchApp throws IOException when the app is not installed")
    fun launchMissingApp(@Device device: BackupRestoreController) {
        assertThrows(IOException::class.java) { runBlocking { device.launchApp() } }
    }

    @Test
    @Order(3)
    @DisplayName("API-90 runBackupRestoreFlow fails at seeding with IOException when the app is not installed")
    fun flowFailsForMissingApp(@Device device: BackupRestoreController, @TempDir dir: Path) {
        val error = assertThrows(IOException::class.java) {
            runBlocking {
                device.runBackupRestoreFlow(StorageDomain.Preference("prefs", "k", "v"), dir, BackupTransportMode.LOCAL)
            }
        }
        assertTrue(error.message.orEmpty().contains("PopulateStorageAction"), error.message)
    }

    @Test
    @Order(4)
    @DisplayName("KI-26b performBackup throws IOException when the app is not installed")
    fun backupMissingApp(@Device device: BackupRestoreController, @TempDir dir: Path) {
        assertThrows(IOException::class.java) {
            runBlocking { device.performBackup(BackupTransportMode.CLOUD_UNENCRYPTED, dir) }
        }
    }
}
