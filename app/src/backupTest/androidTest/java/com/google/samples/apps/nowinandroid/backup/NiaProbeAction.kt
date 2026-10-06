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
 * On-device action that exercises the `runOnDevice` transport itself, selected by the `mode` arg:
 *
 * - `echo`: returns every received argument as `arg.<key>`, so the host can compare what arrived
 *   on the device with what it sent.
 * - `in_band_failure`: returns `status=failure` and `error=<error>` without throwing.
 * - `throw`: throws an [IllegalStateException] with `message`, padded to `message_kb` KiB.
 * - `large_result`: returns a `blob` of `size_kb` KiB.
 *
 * Results use the map constructor and literal keys so the action compiles against every
 * `androidx.test.backup` release.
 */
class NiaProbeAction : BackupDeviceAction {
    override val phase: Int = BackupDeviceAction.PHASE_VERIFY

    override fun execute(context: Context, args: BackupDeviceActionArgs): BackupDeviceActionResult {
        val payload = args.payload
        return when (val mode = payload["mode"]) {
            "echo" -> BackupDeviceActionResult(
                payload.mapKeys { "arg.${it.key}" } + ("status" to "success"),
            )

            "in_band_failure" -> BackupDeviceActionResult(
                mapOf(
                    "status" to "failure",
                    "error" to (payload["error"] ?: "NiaProbeAction reported a failure"),
                ),
            )

            "throw" -> {
                val paddingKb = payload["message_kb"]?.toInt() ?: 0
                val message = (payload["message"] ?: "NiaProbeAction failure") +
                    "x".repeat(paddingKb * 1024)
                throw IllegalStateException(message)
            }

            "large_result" -> {
                val sizeBytes = requireNotNull(payload["size_kb"]).toInt() * 1024
                BackupDeviceActionResult(
                    mapOf(
                        "status" to "success",
                        "size" to sizeBytes.toString(),
                        "blob" to "a".repeat(sizeBytes),
                    ),
                )
            }

            else -> throw IllegalArgumentException("Unknown NiaProbeAction mode: $mode")
        }
    }
}
