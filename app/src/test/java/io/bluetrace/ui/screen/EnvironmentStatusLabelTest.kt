package io.bluetrace.ui.screen

import io.bluetrace.R
import io.bluetrace.shared.domain.RequirementId
import io.bluetrace.shared.domain.RequirementStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentStatusLabelTest {
    @Test
    fun bluetoothSwitchUsesEnabledLabelWhenGranted() {
        assertEquals(
            R.string.env_status_enabled,
            environmentStatusLabelRes(RequirementId.BLUETOOTH_ON, RequirementStatus.GRANTED),
        )
    }

    @Test
    fun nonBluetoothGrantedRequirementsKeepGrantedLabel() {
        assertEquals(
            R.string.env_status_granted,
            environmentStatusLabelRes(RequirementId.LOCATION, RequirementStatus.GRANTED),
        )
    }
}
