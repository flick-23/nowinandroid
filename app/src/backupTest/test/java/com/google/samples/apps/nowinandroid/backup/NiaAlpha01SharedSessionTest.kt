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
import androidx.test.backup.host.Device
import androidx.test.backup.host.Isolation
import androidx.test.backup.host.IsolationPolicy
import com.android.adblib.AdbSession
import com.android.adblib.tools.createStandaloneSession
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Uses `BackupRestoreExtension(AdbSession)`, whose contract is that the caller owns the session:
 * the library must never close it.
 */
@BackupRestoreConfig(applicationId = APP_ID)
@Isolation(IsolationPolicy.MANUAL)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaAlpha01SharedSessionTest {

    companion object {
        private val sharedSession: AdbSession = createStandaloneSession()

        @JvmField
        @RegisterExtension
        val extension = BackupRestoreExtension(sharedSession)

        @JvmStatic
        @AfterAll
        fun closeSharedSession() {
            runCatching { sharedSession.close() }
        }
    }

    private suspend fun BackupRestoreController.echo(value: String): String? =
        (runOnDevice(PROBE_ACTION, mapOf("mode" to "echo", "who" to value)) as? BackupActionResult.Success)?.data?.get("arg.who")

    @Test
    @Order(1)
    @DisplayName("API-95 BackupRestoreExtension(AdbSession) resolves a working controller on the caller's session")
    fun sharedSessionWorks(@Device device: BackupRestoreController): Unit = runBlocking {
        assertEquals("shared", device.echo("shared"))
    }

    @Test
    @Order(2)
    @DisplayName("KI-22 BackupRestoreController.close() leaves the caller's AdbSession open")
    fun closeKeepsSharedSession(@Device device: BackupRestoreController) {
        device.close()
        assertTrue(sharedSession.scope.isActive, "close() closed the AdbSession owned by the test")
    }

    @Test
    @Order(3)
    @DisplayName("KI-22b A later test still resolves a working controller on the caller's session")
    fun sessionUsableAfterClose(@Device device: BackupRestoreController): Unit = runBlocking {
        assertEquals("after-close", device.echo("after-close"))
    }
}
