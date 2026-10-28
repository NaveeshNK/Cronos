package com.cronos.camera.enhance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Tracks device motion during burst. Used for capture early-stop and merge gate.
 */
class MotionGate(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile private var peak = 0f
    @Volatile private var listening = false

    fun start() {
        peak = 0f
        if (accel == null) return
        listening = true
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (listening) {
            runCatching { sm.unregisterListener(this) }
            listening = false
        }
    }

    /** Peak linear accel magnitude since start (m/s^2-ish). */
    fun peakMotion(): Float = peak

    /** True if hand moved enough that a 2nd frame is risky. */
    fun isTooMuchMotion(threshold: Float = 1.8f): Boolean = peak >= threshold

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        if (mag > peak) peak = mag
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
