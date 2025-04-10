
package pl.pw.geogame
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log

class ConnectionStateChangeReceiver(
    private val onBothEnabled: () -> Unit,
    private val onEitherDisabled: () -> Unit
) : BroadcastReceiver() {

    var isGpsEnabled = false
    var isBluetoothEnabled = false
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action == null) return

        when (intent.action) {
            LocationManager.PROVIDERS_CHANGED_ACTION -> {
                val locationManager =
                    context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                scanBLEDevices()
                Log.d("ConnectionReceiver", "GPS Enabled: $isGpsEnabled")
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                isBluetoothEnabled = state == BluetoothAdapter.STATE_ON
                scanBLEDevices()
                Log.d("ConnectionReceiver", "Bluetooth Enabled: $isBluetoothEnabled")
            }
        }
    }

    private fun scanBLEDevices() {
        if (isGpsEnabled && isBluetoothEnabled) {
            onBothEnabled()
        } else {
            onEitherDisabled()

        }
    }
}
