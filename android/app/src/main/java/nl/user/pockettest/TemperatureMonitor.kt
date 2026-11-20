package nl.user.pockettest

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import java.io.Closeable

class TemperatureMonitor(
    context: Context,
    private val onReading: (ThermalReading) -> Unit,
    private val onError: (Throwable) -> Unit = { error ->
        Log.e(TAG, "Temperature monitoring failed", error)
    },
) : Closeable {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var closed = false

    private val poll = object : Runnable {
        override fun run() {
            val sample = sample()
            synchronized(lock) {
                if (closed) return
                if (sample.errors.isNotEmpty()) {
                    val error = sample.errors.first()
                    for (additional in sample.errors.drop(1)) error.addSuppressed(additional)
                    onError(error)
                    return
                }
                try {
                    onReading(sample.reading)
                } catch (error: Exception) {
                    onError(IllegalStateException("Temperature reading callback failed", error))
                    return
                }
                if (!closed) handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    fun start() {
        synchronized(lock) {
            check(!closed) { "Temperature monitor is closed" }
            if (thread != null) return
            val newThread = HandlerThread("pocket-temperature-monitor")
            newThread.start()
            thread = newThread
            handler = Handler(newThread.looper).also { it.post(poll) }
        }
    }

    private fun sample(): Sample {
        var batteryCelsius: Double? = null
        var batteryOverheated: Boolean? = null
        val errors = mutableListOf<Throwable>()
        try {
            val battery = applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            if (battery != null) {
                if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                    batteryCelsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                }
                if (battery.hasExtra(BatteryManager.EXTRA_HEALTH)) {
                    val health = battery.getIntExtra(
                        BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN,
                    )
                    if (health != BatteryManager.BATTERY_HEALTH_UNKNOWN) {
                        batteryOverheated = health == BatteryManager.BATTERY_HEALTH_OVERHEAT
                    }
                }
            }
        } catch (error: Exception) {
            errors.add(IllegalStateException("Battery temperature sample failed", error))
        }

        var thermalStatus: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                thermalStatus = applicationContext
                    .getSystemService(PowerManager::class.java)
                    .currentThermalStatus
            } catch (error: Exception) {
                errors.add(IllegalStateException("System thermal status sample failed", error))
            }
        }
        return Sample(
            ThermalReading(batteryCelsius, thermalStatus, batteryOverheated),
            errors,
        )
    }

    override fun close() {
        val threadToJoin: HandlerThread?
        synchronized(lock) {
            if (closed) return
            closed = true
            handler?.removeCallbacksAndMessages(null)
            threadToJoin = thread
            threadToJoin?.quitSafely()
            handler = null
            thread = null
        }
        if (threadToJoin != null && Thread.currentThread() !== threadToJoin) {
            try {
                threadToJoin.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private data class Sample(
        val reading: ThermalReading,
        val errors: List<Throwable>,
    )

    companion object {
        private const val TAG = "PocketTemperature"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
