package nl.user.pockettest

data class ThermalReading(
    val batteryCelsius: Double?,
    val thermalStatus: Int?,
    val batteryOverheated: Boolean?,
) {
    val shouldWarn: Boolean
        get() = batteryOverheated == true || (thermalStatus != null && thermalStatus >= SEVERE)

    val batteryTemperatureLabel: String
        get() = batteryCelsius?.let { "Battery temperature: $it °C" }
            ?: "Battery temperature: unavailable"

    val thermalStatusLabel: String
        get() = when (thermalStatus) {
            null -> "Thermal status: unavailable"
            NONE -> "Thermal status: none (0)"
            LIGHT -> "Thermal status: light (1)"
            MODERATE -> "Thermal status: moderate (2)"
            SEVERE -> "Thermal status: severe (3)"
            CRITICAL -> "Thermal status: critical (4)"
            EMERGENCY -> "Thermal status: emergency (5)"
            SHUTDOWN -> "Thermal status: shutdown (6)"
            else -> "Thermal status: unknown ($thermalStatus)"
        }

    val thermalLabel: String
        get() = when (thermalStatus) {
            null -> "unavailable"
            NONE -> "none"
            LIGHT -> "light"
            MODERATE -> "moderate"
            SEVERE -> "severe"
            CRITICAL -> "critical"
            EMERGENCY -> "emergency"
            SHUTDOWN -> "shutdown"
            else -> "unknown ($thermalStatus)"
        }

    val description: String
        get() = "$batteryTemperatureLabel; $thermalStatusLabel; " +
            when (batteryOverheated) {
                true -> "Android battery overheat indication: yes"
                false -> "Android battery overheat indication: no"
                null -> "Android battery overheat indication: unavailable"
            }

    companion object {
        const val NONE = 0
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4
        const val EMERGENCY = 5
        const val SHUTDOWN = 6
    }
}
