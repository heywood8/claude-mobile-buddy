package dev.heywood8.claudebuddy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * The crab reacting to the phone itself.
 *
 * Everything else it does is a rendering of what the bridge said. This is the phone being
 * picked up, shaken, or put down face-first, which no host will ever know about — and it runs
 * only while the dashboard is on screen, because a pet is not worth a sensor in your pocket.
 */
@Composable
fun HandlingEffects() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            onDispose {}
        } else {
            val listener = object : SensorEventListener {
                private var faceDownSince = 0L
                private var lastShake = 0L

                override fun onSensorChanged(event: SensorEvent) {
                    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                    val now = System.currentTimeMillis()

                    // Gravity alone is 9.8, so this is the phone being moved hard rather than
                    // held. Rate-limited, or one shake becomes twenty.
                    val force = sqrt(x * x + y * y + z * z)
                    if (force > SHAKE_FORCE && now - lastShake > 2_000) {
                        lastShake = now
                        PetMood.show(PetState.DIZZY, 3)
                    }

                    // Face down for a moment, not merely passing through it on the way to the
                    // table. Z goes negative when the screen points at the floor.
                    if (z < FACE_DOWN_Z) {
                        if (faceDownSince == 0L) faceDownSince = now
                        if (now - faceDownSince > 1_200) PetMood.show(PetState.SLEEP, 3)
                    } else {
                        faceDownSince = 0L
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }

            // The slowest rate Android offers. This is a mood, not a pedometer.
            manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            onDispose { manager.unregisterListener(listener) }
        }
    }
}

private const val SHAKE_FORCE = 22f
private const val FACE_DOWN_Z = -8.5f
