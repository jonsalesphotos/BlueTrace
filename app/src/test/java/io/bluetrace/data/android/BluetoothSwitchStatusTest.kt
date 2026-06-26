package io.bluetrace.data.android

import io.bluetrace.shared.domain.RequirementStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothSwitchStatusTest {
    @Test
    fun usesGlobalBluetoothSettingWhenAdapterStateIsUnreadable() {
        assertEquals(
            RequirementStatus.GRANTED,
            bluetoothSwitchStatus(hasAdapter = true, adapterEnabled = null, globalBluetoothOn = true),
        )
    }

    @Test
    fun trustsReadableAdapterOffStateOverGlobalSetting() {
        assertEquals(
            RequirementStatus.OFF,
            bluetoothSwitchStatus(hasAdapter = true, adapterEnabled = false, globalBluetoothOn = true),
        )
    }

    @Test
    fun missingAdapterIsOffEvenWhenGlobalSettingIsOn() {
        assertEquals(
            RequirementStatus.OFF,
            bluetoothSwitchStatus(hasAdapter = false, adapterEnabled = null, globalBluetoothOn = true),
        )
    }
}
