package com.tcrrry.desktopcast.safety

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Read-only adapter for the public Automotive gear properties.
 *
 * Some Android 9 car firmwares expose the property manager but omit the SDK
 * callback nested class. Reflection keeps that optional platform surface from
 * being resolved while the app starts, and the short read interval still
 * detects a gear change promptly without touching vehicle controls. The target
 * S56_HQX profile is handled as a fallback because its VHAL exposes selector
 * events but does not publish a usable CURRENT_GEAR event.
 */
class IcarDrivingStateMonitor(
    context: Context,
    private val onStateChanged: (DrivingState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var car: Any? = null
    private var propertyManager: Any? = null
    private var lastState: DrivingState? = null
    private var lastReadingSignature: String? = null
    private var lastReadErrorSignature: String? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!started) return
            if (propertyManager == null) acquirePropertyManager()
            publish(readState())
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // Car can deliver this callback before createCar() returns. Post the
            // setup so the field assignment in start() has completed first.
            mainHandler.post { initializePropertyManager() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            propertyManager = null
            publish(DrivingState.UNAVAILABLE)
            if (started) mainHandler.post(poller)
        }
    }

    fun start() {
        if (started) return
        started = true
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            publish(DrivingState.UNAVAILABLE)
            return
        }
        runCatching {
            val carClass = Class.forName(CAR_CLASS_NAME)
            val createCar = carClass.getMethod(
                "createCar",
                Context::class.java,
                ServiceConnection::class.java,
            )
            val connectedCar = createCar.invoke(null, appContext, serviceConnection)
                ?: error("Automotive Car.createCar returned null")
            car = connectedCar
            connectedCar.javaClass.getMethod("connect").invoke(connectedCar)
            // Also cover firmwares that connect without dispatching the callback
            // on the application's ServiceConnection in time.
            mainHandler.post { initializePropertyManager() }
        }.onFailure { error ->
            Log.w(TAG, "Unable to connect to Automotive service", unwrap(error))
            publish(DrivingState.UNAVAILABLE)
            mainHandler.removeCallbacks(poller)
            mainHandler.postDelayed(poller, POLL_INTERVAL_MS)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.removeCallbacks(poller)
        propertyManager = null
        car?.let { connectedCar ->
            runCatching { connectedCar.javaClass.getMethod("disconnect").invoke(connectedCar) }
        }
        car = null
        lastState = null
        lastReadingSignature = null
        lastReadErrorSignature = null
    }

    private fun readState(): DrivingState {
        val manager = propertyManager ?: return DrivingState.UNAVAILABLE
        val currentGearId = propertyId("CURRENT_GEAR", FALLBACK_CURRENT_GEAR)
        val selectorGearId = propertyId("GEAR_SELECTION", FALLBACK_GEAR_SELECTION)
        val gearPark = staticInt(VEHICLE_GEAR_CLASS_NAME, "GEAR_PARK", FALLBACK_GEAR_PARK)
        val currentGear = readIntProperty(manager, currentGearId, "CURRENT_GEAR")
        val currentState = DrivingGearStateMapper.fromCurrentGear(currentGear, gearPark)
        if (currentState != DrivingState.UNAVAILABLE) {
            logReading(currentGear, null, currentState)
            return currentState
        }

        // S56_HQX exposes the selector event (11 in P) but no CURRENT_GEAR
        // event. Use it only as an explicit target compatibility fallback.
        val selectorGear = readIntProperty(manager, selectorGearId, "GEAR_SELECTION")
        val selectorState = DrivingGearStateMapper.fromGearSelection(selectorGear, gearPark)
        logReading(currentGear, selectorGear, selectorState)
        return selectorState
    }

    private fun initializePropertyManager() {
        if (!started) return
        acquirePropertyManager()
        if (propertyManager == null) {
            publish(DrivingState.UNAVAILABLE)
        } else {
            publish(readState())
        }
        mainHandler.removeCallbacks(poller)
        mainHandler.post(poller)
    }

    private fun acquirePropertyManager(): Boolean {
        if (propertyManager != null) return true
        val connectedCar = car ?: return false
        propertyManager = runCatching {
            connectedCar.javaClass
                .getMethod("getCarManager", String::class.java)
                .invoke(connectedCar, PROPERTY_SERVICE)
        }.onFailure { error ->
            Log.w(TAG, "Unable to acquire Automotive property manager", unwrap(error))
        }.getOrNull()
        return propertyManager != null
    }

    private fun readIntProperty(manager: Any, propertyId: Int, name: String): Int? = runCatching {
        manager.javaClass
            .getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, propertyId, GLOBAL_AREA_ID) as? Int
    }.onFailure { error ->
        val cause = unwrap(error)
        val signature = "$name/$propertyId/${cause.javaClass.name}/${cause.message}"
        if (signature != lastReadErrorSignature) {
            lastReadErrorSignature = signature
            Log.w(TAG, "Unable to read int property $name ($propertyId)", cause)
        }
    }.getOrNull()

    private fun logReading(currentGear: Int?, selectorGear: Int?, state: DrivingState) {
        val signature = "$currentGear/$selectorGear/$state"
        if (signature == lastReadingSignature) return
        lastReadingSignature = signature
        Log.i(
            TAG,
            "Gear readings current=$currentGear selector=$selectorGear mapped=$state",
        )
    }

    private fun publish(state: DrivingState) {
        if (!started || state == lastState) return
        lastState = state
        Log.i(TAG, "Driving state changed to $state")
        onStateChanged(state)
    }

    private fun staticInt(className: String, fieldName: String, fallback: Int): Int = runCatching {
        Class.forName(className).getField(fieldName).getInt(null)
    }.getOrDefault(fallback)

    private fun propertyId(fieldName: String, fallback: Int): Int = staticInt(
        VEHICLE_PROPERTY_IDS_CLASS_NAME,
        fieldName,
        fallback,
    )

    private fun unwrap(error: Throwable): Throwable = when (error) {
        is InvocationTargetException -> error.targetException ?: error
        else -> error
    }

    private companion object {
        const val TAG = "CastDrivingState"
        const val CAR_CLASS_NAME = "android.car.Car"
        const val VEHICLE_PROPERTY_IDS_CLASS_NAME = "android.car.VehiclePropertyIds"
        const val VEHICLE_GEAR_CLASS_NAME = "android.car.VehicleGear"
        const val PROPERTY_SERVICE = "property"
        const val GLOBAL_AREA_ID = 0
        const val POLL_INTERVAL_MS = 500L
        const val FALLBACK_CURRENT_GEAR = 289408001
        const val FALLBACK_GEAR_SELECTION = 289408000
        const val FALLBACK_GEAR_PARK = 4
    }
}
