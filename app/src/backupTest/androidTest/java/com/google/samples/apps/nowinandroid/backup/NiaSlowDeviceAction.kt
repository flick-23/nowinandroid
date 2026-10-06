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

/**
 * On-device test action that sleeps for a configurable duration (`sleep_ms` in [args], defaulting
 * to 5000 ms) to deterministically exercise host-side timeout enforcement on `runOnDevice`.
 */
class NiaSlowDeviceAction : BackupDeviceAction {
    override val phase: Int = BackupDeviceAction.PHASE_VERIFY

    override fun execute(context: Context, args: BackupDeviceActionArgs): BackupDeviceActionResult {
        val sleepMs = args.payload["sleep_ms"]?.toLongOrNull() ?: 5_000L
        Thread.sleep(sleepMs)
        return BackupDeviceActionResult(
            mapOf("status" to "success", "slept_ms" to sleepMs.toString()),
        )
    }
}
