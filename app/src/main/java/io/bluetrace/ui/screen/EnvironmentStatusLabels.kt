package io.bluetrace.ui.screen

import io.bluetrace.R
import io.bluetrace.shared.domain.RequirementId
import io.bluetrace.shared.domain.RequirementStatus

internal fun environmentStatusLabelRes(id: RequirementId, status: RequirementStatus): Int =
    when (status) {
        RequirementStatus.GRANTED ->
            if (id == RequirementId.BLUETOOTH_ON) R.string.env_status_enabled
            else R.string.env_status_granted
        RequirementStatus.MISSING -> R.string.env_status_missing
        RequirementStatus.BLOCKED -> R.string.env_status_blocked
        RequirementStatus.OFF -> R.string.env_status_off
    }
