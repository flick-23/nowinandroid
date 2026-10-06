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
import androidx.test.backup.host.Device
import androidx.test.backup.host.Isolation
import androidx.test.backup.host.IsolationPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Selects devices through the `androidx.test.backup.device.*` system properties. Test method
 * parameters are resolved after `@BeforeEach`, so each test sets its properties there.
 */
@ExtendWith(BackupRestoreExtension::class)
@BackupRestoreConfig(applicationId = APP_ID)
@Isolation(IsolationPolicy.MANUAL)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NiaAlpha01DeviceSelectionTest {

    private companion object {
        const val SERIAL = "androidx.test.backup.device.serial"
        const val SERIALS = "androidx.test.backup.device.serials"
        const val KEYED_SERIAL = "androidx.test.backup.device.serial.target"
        const val API = "androidx.test.backup.device.api"
        const val APIS = "androidx.test.backup.device.apis"
    }

    private val serials = DeviceChecks.onlineSerials()

    private val apiLevel: Int by lazy {
        DeviceChecks.shell(serials[0], "getprop ro.build.version.sdk").stdout.trim().toInt()
    }

    @BeforeEach
    fun setDeviceProperties(testInfo: TestInfo) {
        assumeTrue(serials.size >= 2, "Needs two online devices, found $serials")
        when (testInfo.testMethod.get().name) {
            "globalSerial" -> System.setProperty(SERIAL, serials[1])
            "serialList" -> System.setProperty(SERIALS, "${serials[1]},${serials[0]}")
            "keyedSerial" -> System.setProperty(KEYED_SERIAL, serials[1])
            "globalApi" -> System.setProperty(API, "$apiLevel")
            "apiList" -> System.setProperty(APIS, "$apiLevel,$apiLevel")
        }
    }

    @AfterEach
    fun clearDeviceProperties() {
        listOf(SERIAL, SERIALS, KEYED_SERIAL, API, APIS).forEach(System::clearProperty)
    }

    @Test
    @Order(1)
    @DisplayName("API-73 System property device.serial selects the device for a @Device parameter")
    fun globalSerial(@Device device: BackupRestoreController) {
        assertEquals(serials[1], device.serialNumber)
    }

    @Test
    @Order(2)
    @DisplayName("API-74 System property device.serials assigns devices to @Device parameters by position")
    fun serialList(@Device first: BackupRestoreController, @Device second: BackupRestoreController) {
        assertEquals(listOf(serials[1], serials[0]), listOf(first.serialNumber, second.serialNumber))
    }

    @Test
    @Order(3)
    @DisplayName("API-75 System property device.serial.<key> resolves @Device(serial = \"<key>\")")
    fun keyedSerial(@Device(serial = "target") device: BackupRestoreController) {
        assertEquals(serials[1], device.serialNumber)
    }

    @Test
    @Order(4)
    @DisplayName("API-76 System property device.api restricts @Device parameters to that API level")
    fun globalApi(@Device device: BackupRestoreController) {
        assertEquals(apiLevel, device.apiLevel)
    }

    @Test
    @Order(5)
    @DisplayName("API-77 System property device.apis assigns API levels to @Device parameters by position")
    fun apiList(@Device first: BackupRestoreController, @Device second: BackupRestoreController) {
        assertEquals(listOf(apiLevel, apiLevel), listOf(first.apiLevel, second.apiLevel))
        assertNotEquals(first.serialNumber, second.serialNumber)
    }
}
