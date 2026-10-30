package com.debayandatta.walkie.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Platform location (no Play Services). Used only during an SOS. */
class Locator(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var tracking: LocationListener? = null

    val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers() = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = 6000): Location? {
        if (!allowed) return null
        val recent = providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 30_000 }.minByOrNull { it.accuracy }
        if (recent != null) return recent
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val l = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(loc)
                    }
                    @Deprecated("needed on API < 29") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                providers().forEach { lm.requestLocationUpdates(it, 0L, 0f, l, Looper.getMainLooper()) }
                cont.invokeOnCancellation { lm.removeUpdates(l) }
            }
        } ?: providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(onUpdate: (Location) -> Unit) {
        if (!allowed) return
        stopTracking()
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = onUpdate(loc)
            @Deprecated("needed on API < 29") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        tracking = l
        providers().forEach { lm.requestLocationUpdates(it, 30_000L, 25f, l, Looper.getMainLooper()) }
    }

    fun stopTracking() {
        tracking?.let { lm.removeUpdates(it) }
        tracking = null
    }
}
