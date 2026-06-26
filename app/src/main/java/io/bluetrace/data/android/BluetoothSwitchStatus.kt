package io.bluetrace.data.android

import io.bluetrace.shared.domain.RequirementStatus

internal fun bluetoothSwitchStatus(
    hasAdapter: Boolean,
    adapterEnabled: Boolean?,
    globalBluetoothOn: Boolean,
): RequirementStatus {
    if (!hasAdapter) return RequirementStatus.OFF
    return when (adapterEnabled) {
        true -> RequirementStatus.GRANTED
        false -> RequirementStatus.OFF
        null -> if (globalBluetoothOn) RequirementStatus.GRANTED else RequirementStatus.OFF
    }
}
