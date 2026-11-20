package nl.user.pockettest

fun runThermalReadingTests() {
    val unavailable = ThermalReading(null, null, null)
    check(unavailable.batteryTemperatureLabel == "Battery temperature: unavailable")
    check(unavailable.thermalStatusLabel == "Thermal status: unavailable")
    check(unavailable.thermalLabel == "unavailable")
    check(
        unavailable.description ==
            "Battery temperature: unavailable; Thermal status: unavailable; Android battery overheat indication: unavailable",
    )
    check(!unavailable.shouldWarn)

    val temperature = ThermalReading(24.9, 0, false)
    check(temperature.batteryTemperatureLabel == "Battery temperature: 24.9 °C")
    check(temperature.thermalStatusLabel == "Thermal status: none (0)")
    check(temperature.thermalLabel == "none")
    check(!temperature.shouldWarn)

    val expectedLabels = listOf(
        1 to "Thermal status: light (1)",
        2 to "Thermal status: moderate (2)",
        3 to "Thermal status: severe (3)",
        4 to "Thermal status: critical (4)",
        5 to "Thermal status: emergency (5)",
        6 to "Thermal status: shutdown (6)",
    )
    for ((status, label) in expectedLabels) {
        val reading = ThermalReading(null, status, false)
        check(reading.thermalStatusLabel == label)
        check(reading.shouldWarn == (status >= 3))
    }

    val futureStatus = ThermalReading(null, 7, false)
    check(futureStatus.thermalStatusLabel == "Thermal status: unknown (7)")
    check(futureStatus.thermalLabel == "unknown (7)")
    check(futureStatus.shouldWarn)

    val batteryOverheat = ThermalReading(null, null, true)
    check(batteryOverheat.shouldWarn)
    check(
        batteryOverheat.description ==
            "Battery temperature: unavailable; Thermal status: unavailable; Android battery overheat indication: yes",
    )

    val highBatteryTemperature = ThermalReading(95.0, null, false)
    check(!highBatteryTemperature.shouldWarn)
    check(
        highBatteryTemperature.description ==
            "Battery temperature: 95.0 °C; Thermal status: unavailable; Android battery overheat indication: no",
    )
}
