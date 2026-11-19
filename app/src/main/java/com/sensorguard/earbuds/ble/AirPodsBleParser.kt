package com.sensorguard.earbuds.ble

import com.sensorguard.earbuds.data.EarbudBatteryState

/**
 * Parses Apple Continuity Proximity Pairing BLE manufacturer frames (Company ID 0x004C).
 *
 * MODEL COMPATIBILITY NOTES:
 * - Known to work reliably with standard Proximity Pairing messages (type 0x07):
 *   - AirPods (1st Gen)        -> Model ID 0x0220
 *   - AirPods (2nd Gen)        -> Model ID 0x0F20
 *   - AirPods (3rd Gen)        -> Model ID 0x1320
 *   - AirPods Pro (1st Gen)    -> Model ID 0x0E20
 *   - AirPods Pro (2nd Gen)    -> Model ID 0x1420 / 0x2420 (Lightning / USB-C)
 *   - Powerbeats Pro           -> Model ID 0x0B20
 *   - Beats Fit Pro            -> Model ID 0x1220
 * - Unreliable / unsupported models for L/R/Case split parsing:
 *   - Over-ear single-battery headsets (AirPods Max 0x0A20) or truncated/non-standard
 *     Continuity frames, and clone earbuds that broadcast fixed dummy nibbles.
 *   - For any unrecognized model ID, malformed payload length, or invalid nibbles, this parser
 *     marks `isReliableModel = false` so the UI and notification show "unavailable".
 */
object AirPodsBleParser {

    const val APPLE_COMPANY_ID = 0x004C
    private const val PROXIMITY_PAIRING_TYPE = 0x07
    private const val MIN_PROXIMITY_PAYLOAD_BYTES = 16

    private val SUPPORTED_DUAL_EARBUD_MODELS = mapOf(
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0B20 to "Powerbeats Pro",
        0x1220 to "Beats Fit Pro"
    )

    private val KNOWN_SINGLE_HEADSET_MODELS = mapOf(
        0x0A20 to "AirPods Max"
    )

    fun parseAppleManufacturerData(rawBytes: ByteArray, nowMillis: Long): EarbudBatteryState? {
        if (rawBytes.size < 2) return null
        val messageType = rawBytes[0].toInt() and 0xFF
        if (messageType != PROXIMITY_PAIRING_TYPE) return null

        if (rawBytes.size < MIN_PROXIMITY_PAYLOAD_BYTES) {
            return EarbudBatteryState(
                modelName = "Truncated BLE Frame",
                isReliableModel = false,
                lastUpdatedMillis = nowMillis
            )
        }

        val modelCode = ((rawBytes[3].toInt() and 0xFF) shl 8) or (rawBytes[4].toInt() and 0xFF)

        if (KNOWN_SINGLE_HEADSET_MODELS.containsKey(modelCode)) {
            return EarbudBatteryState(
                modelName = "${KNOWN_SINGLE_HEADSET_MODELS[modelCode]} (Single-Battery)",
                isReliableModel = false,
                lastUpdatedMillis = nowMillis
            )
        }

        val modelLabel = SUPPORTED_DUAL_EARBUD_MODELS[modelCode]
            ?: return EarbudBatteryState(
                modelName = String.format("Unverified Model (0x%04X)", modelCode),
                isReliableModel = false,
                lastUpdatedMillis = nowMillis
            )

        val statusByte = rawBytes[5].toInt() and 0xFF
        val podsFlipped = (statusByte and 0x20) != 0

        val podsBatteryByte = rawBytes[6].toInt() and 0xFF
        val highNibble = (podsBatteryByte ushr 4) and 0x0F
        val lowNibble = podsBatteryByte and 0x0F

        val rawLeftNibble = if (podsFlipped) lowNibble else highNibble
        val rawRightNibble = if (podsFlipped) highNibble else lowNibble

        val flagsAndCaseByte = rawBytes[7].toInt() and 0xFF
        val rawCaseNibble = flagsAndCaseByte and 0x0F
        val chargingFlags = (flagsAndCaseByte ushr 4) and 0x0F

        val leftChg = if (podsFlipped) (chargingFlags and 0x01) != 0 else (chargingFlags and 0x02) != 0
        val rightChg = if (podsFlipped) (chargingFlags and 0x02) != 0 else (chargingFlags and 0x01) != 0
        val caseChg = (chargingFlags and 0x04) != 0

        val leftPct = decodeBatteryNibble(rawLeftNibble)
        val rightPct = decodeBatteryNibble(rawRightNibble)
        val casePct = decodeBatteryNibble(rawCaseNibble)

        if (leftPct == null && rightPct == null && casePct == null) {
            return EarbudBatteryState(
                modelName = modelLabel,
                isReliableModel = false,
                lastUpdatedMillis = nowMillis
            )
        }

        return EarbudBatteryState(
            modelName = modelLabel,
            isReliableModel = true,
            leftPercent = leftPct,
            rightPercent = rightPct,
            casePercent = casePct,
            leftCharging = leftChg,
            rightCharging = rightChg,
            caseCharging = caseChg,
            lastUpdatedMillis = nowMillis
        )
    }

    private fun decodeBatteryNibble(nibble: Int): Int? {
        return if (nibble in 0..10) nibble * 10 else null
    }
}
