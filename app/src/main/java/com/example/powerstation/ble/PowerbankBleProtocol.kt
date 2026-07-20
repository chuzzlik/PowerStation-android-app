package com.example.powerstation.ble

import java.util.UUID

object PowerStationBleProtocol {
    val SERVICE_UUID: UUID =
        UUID.fromString("6f2a0001-5a3d-4e2c-9a73-1b21d9b00001")

    val STATUS_CHAR_UUID: UUID =
        UUID.fromString("6f2a0002-5a3d-4e2c-9a73-1b21d9b00001")

    val COMMAND_CHAR_UUID: UUID =
        UUID.fromString("6f2a0003-5a3d-4e2c-9a73-1b21d9b00001")

    val SETTINGS_CHAR_UUID: UUID =
        UUID.fromString("6f2a0004-5a3d-4e2c-9a73-1b21d9b00001")
}
