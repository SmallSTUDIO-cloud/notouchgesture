package com.samin.notouchgesture.control

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Debounced shake detector used only when the user explicitly enables the flashlight gesture. */
class ShakeFlashlightController(
    context: Context,
    private val dispatcher: TouchlessActionDispatcher,
    private val threshold: Float = 12.5f,
) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastTrigger = Long.MIN_VALUE
    private var lastMagnitude = 9.8f
    private var started = false

    fun start() {
        if (started) return
        val s = sensor ?: return
        if (manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI) == true) started = true
    }

    fun stop() {
        if (!started) return
        manager?.unregisterListener(this)
        started = false
        dispatcher.stopFlashlight()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        )
        val jerk = kotlin.math.abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        val now = SystemClock.elapsedRealtime()
        if (magnitude >= threshold && jerk >= 3.0f && (lastTrigger == Long.MIN_VALUE || now - lastTrigger >= 1200L)) {
            lastTrigger = now
            dispatcher.toggleFlashlight()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
