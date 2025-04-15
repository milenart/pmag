package pl.pw.geogame

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.IntentFilter
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import pl.pw.geogame.data.model.ArchiveBeacon
import pl.pw.geogame.data.model.BeaconFile
import com.google.gson.Gson
import org.osmdroid.views.MapView
import org.osmdroid.config.Configuration
import android.preference.PreferenceManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.GeoPoint
import android.widget.CheckBox
import kotlin.math.*
import android.location.Location
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider

data class CalculatedMarker(
    val algorithmName: String, // Identifier for the algorithm
    var latitude: Double? = null,
    var longitude: Double? = null,
    var osmdroidMarker: Marker? = null // Make it nullable
)

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "pw.MainActivity"
    }
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var connectionStateReceiver: ConnectionStateChangeReceiver
    private var beaconManager: BeaconManager? = null
    private val archiveBeacon = mutableListOf<ArchiveBeacon>()
    private val region = Region("all-beacons-region", null, null, null)
    private var lastScannedBeacons: Collection<Beacon> = emptyList()
    private val activeCalculatedMarkers = mutableMapOf<String, CalculatedMarker>()
    private val mapView:MapView by lazy {
        findViewById(R.id.map_view)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.entries.any { !it.value }) {
                Toast.makeText(
                    this,
                    "Bez niezbędnych uprawnień aplikacja nie będzie działać prawidłowo.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                listenForConnectionChanges()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        // val mapView: MapView = findViewById(R.id.map_view)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        val mapController = mapView.controller
        mapController.setZoom(14.0)
        val startPoint = org.osmdroid.util.GeoPoint(52.220429, 21.010832)
        mapController.setCenter(startPoint)
        val locationProvider = GpsMyLocationProvider(this)
        locationOverlay = MyLocationNewOverlay(locationProvider, mapView)
        locationOverlay.isDrawAccuracyEnabled = false
        locationOverlay.isEnabled = false
        mapView.overlays.add(locationOverlay)

        setUpBeaconManager()
        setUpUI()
        setUpCheckboxes()
        requestRequiredPermissions()
        loadBeaconsFromAssets()
        Log.d(TAG, "Rchivalne becony :${archiveBeacon.count()}")

    }

    private fun setUpCheckboxes() {
        val checkboxAlgorithmMap = mapOf(
            R.id.checkbox_algorithm_1 to "Weighted Sum",
            R.id.checkbox_algorithm_2 to "Trilateration",
            R.id.checkbox_algorithm_3 to "GNSS"
        )

        val checkboxList = mutableListOf<CheckBox>()
        for ((id, algorithmName) in checkboxAlgorithmMap) { // Use destructuring
            val checkBox = findViewById<CheckBox>(id)
            checkboxList.add(checkBox)
            activeCalculatedMarkers[algorithmName] = CalculatedMarker(algorithmName)
            // Note: The osmdroidMarker for "GNSS" in activeCalculatedMarkers is now unused
        }

        checkboxList.forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                checkboxAlgorithmMap[checkBox.id]?.let { algorithmName ->
                    if (algorithmName == "GNSS") {
                        // --- Handle GNSS Checkbox ---
                        if (isChecked) {
                            // Enable the MyLocationNewOverlay (makes its marker visible)
                            locationOverlay.setEnabled(true)
                            // Optionally re-enable follow location if desired when checked
                            // locationOverlay.enableFollowLocation()
                            Log.d(TAG, "GNSS Overlay Enabled")
                        } else {
                            // Disable the MyLocationNewOverlay (hides its marker)
                            locationOverlay.setEnabled(false)
                            // Optionally disable follow location
                            // locationOverlay.disableFollowLocation()
                            Log.d(TAG, "GNSS Overlay Disabled")
                        }
                        // We don't manage a separate Marker for GNSS anymore
                        activeCalculatedMarkers["GNSS"]?.latitude = null
                        activeCalculatedMarkers["GNSS"]?.longitude = null

                    } else {
                        // --- Handle Other Algorithm Checkboxes (Weighted Sum, Trilateration) ---
                        val calculatedMarker = activeCalculatedMarkers[algorithmName]
                        calculatedMarker?.let { markerData ->
                            if (isChecked) {
                                // Create and add marker for other algorithms
                                if (markerData.osmdroidMarker == null) { // Create only if null
                                    markerData.osmdroidMarker = Marker(mapView).apply {
                                        title = algorithmName
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        // Use ContextCompat for loading drawables safely
                                        icon = when (algorithmName) {
                                            "Weighted Sum" -> ContextCompat.getDrawable(this@MainActivity, R.drawable.red_marker_rsized)
                                            "Trilateration" -> ContextCompat.getDrawable(this@MainActivity, R.drawable.black_marker_rsized)
                                            // Remove GNSS case - its icon is handled by locationOverlay
                                            else -> ContextCompat.getDrawable(this@MainActivity, org.osmdroid.library.R.drawable.marker_default)
                                        }
                                    }
                                    mapView.overlays.add(markerData.osmdroidMarker)
                                    Log.d(TAG, "Marker added for $algorithmName")
                                }
                                // Trigger position calculation immediately
                                recalculatePositions(lastScannedBeacons)
                            } else {
                                // Remove marker for other algorithms
                                markerData.osmdroidMarker?.let {
                                    mapView.overlays.remove(it)
                                    Log.d(TAG, "Marker removed for $algorithmName")
                                }
                                markerData.osmdroidMarker = null
                                markerData.latitude = null
                                markerData.longitude = null
                            }
                        } // end let markerData
                    } // end else (not GNSS)

                    mapView.invalidate() // Redraw map after changes
                } // end let algorithmName
            } // end setOnCheckedChangeListener
        } // end forEach
    }

    private fun recalculatePositions(beacons: Collection<Beacon>) {
        var needsRedraw = false
        for ((algorithmName, markerData) in activeCalculatedMarkers) {
            // Skip GNSS - its position is handled by MyLocationNewOverlay directly
            if (algorithmName == "GNSS") {
                // Optionally, you *could* call calculateDevicePositionByGNSS() here
                // just to update the stored lat/lon in markerData for logging,
                // but it's not needed for display.
                // calculateDevicePositionByGNSS()
                continue // Move to the next algorithm
            }

            // Process other algorithms (Weighted Sum, Trilateration)
            // Check if their marker exists (implicitly means checkbox is checked)
            if (markerData.osmdroidMarker != null && mapView.overlays.contains(markerData.osmdroidMarker)) {
                var positionUpdated = false
                val newPosition: Pair<Double, Double>? = when (algorithmName) {
                    "Weighted Sum" -> calculateDevicePositionByWeightedSum(beacons)
                    "Trilateration" -> calculateDevicePositionByTrilateration(beacons)
                    else -> null // Should not happen based on current setup
                }

                newPosition?.let {
                    // Update stored data and marker position
                    markerData.latitude = it.first
                    markerData.longitude = it.second
                    markerData.osmdroidMarker?.position = GeoPoint(it.first, it.second)
                    positionUpdated = true
                }

                if (positionUpdated) needsRedraw = true
            }
        } // end for

        if (needsRedraw) {
            mapView.invalidate() // Update the map only if non-GNSS positions changed
        }
    }

    private fun setUpBeaconManager() {
        if (beaconManager == null) {
            beaconManager = BeaconManager.getInstanceForApplication(this)
            listOf(
                BeaconParser.EDDYSTONE_UID_LAYOUT,
                BeaconParser.EDDYSTONE_TLM_LAYOUT,
                BeaconParser.EDDYSTONE_URL_LAYOUT,
            ).forEach {
                beaconManager?.beaconParsers?.add(BeaconParser().setBeaconLayout(it))
            }
            beaconManager?.addRangeNotifier { beacons, _ ->
                //calculateDevicePositionByWeightedSum(beacons)
                lastScannedBeacons = beacons
                recalculatePositions(beacons)
                Log.d(TAG, "num of becones:${beacons.count()}")
            }
        }
    }

    private fun scanForBeacons() {
        //beaconManager.getRegionViewModel(region).rangedBeacons.observe(this, rangingObserver)
        beaconManager?.startRangingBeacons(region)
    }

    /**
     * Gets the fine location currently available from the MyLocationNewOverlay,
     * with a fallback to LocationManager's last known location.
     */
    private fun calculateDevicePositionByGNSS(): Pair<Double, Double>? {
        // Check for fine location permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Access the location directly from the overlay instance
            if (::locationOverlay.isInitialized) { // Check if the overlay variable has been initialized
                locationOverlay.myLocation?.let { lastKnownFineLocation ->
                    // Location from overlay is available
                    Log.d(TAG, "GNSS Position from LocationOverlay: Lat=${lastKnownFineLocation.latitude}, Lon=${lastKnownFineLocation.longitude}")
                    // Store it in our data structure if needed for other purposes
                    activeCalculatedMarkers["GNSS"]?.latitude = lastKnownFineLocation.latitude
                    activeCalculatedMarkers["GNSS"]?.longitude = lastKnownFineLocation.longitude
                    return Pair(lastKnownFineLocation.latitude, lastKnownFineLocation.longitude)
                } ?: run {
                    // Overlay is initialized, but hasn't received a location fix yet
                    Log.w(TAG, "GNSS Position: LocationOverlay has no current fix. Trying LastKnownLocation fallback...")
                    // Fallback using LocationManager
                    try {
                        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
                        val fallbackLocation: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                        fallbackLocation?.let {
                            Log.d(TAG, "GNSS Position from Fallback (LastKnownLocation): Lat=${it.latitude}, Lon=${it.longitude}")
                            // Store fallback if needed
                            activeCalculatedMarkers["GNSS"]?.latitude = it.latitude
                            activeCalculatedMarkers["GNSS"]?.longitude = it.longitude
                            return Pair(it.latitude, it.longitude)
                        } ?: Log.w(TAG, "GNSS Position: Fallback (LastKnownLocation) also returned null.")

                    } catch (se: SecurityException) {
                        Log.e(TAG, "GNSS Position: SecurityException during fallback check.", se)
                    }
                    // Return null if overlay has no fix and fallback failed
                    return null
                }
            } else {
                Log.e(TAG, "GNSS Position: LocationOverlay is not initialized.")
                return null
            }
        } else {
            Log.w(TAG, "GNSS Position: ACCESS_FINE_LOCATION permission not granted.")
            return null
        }
    }

    private fun calculateDevicePositionByWeightedSum(beacons: Collection<Beacon>): Pair<Double, Double>?{
        if (beacons.isEmpty()) {
            Log.d(TAG, "Brak wykrytych beaconów")
            return null
        }

        var weightedLatitudeSum = 0.0
        var weightedLongitudeSum = 0.0
        var weightSum = 0.0

        beacons.forEach { beacon ->
            val realBeacon = archiveBeacon.find { it.beaconUid == beacon.bluetoothAddress }
            if (realBeacon != null && beacon.distance > 0) {
                val weight = 1.0 / beacon.distance
                weightedLatitudeSum += realBeacon.latitude * weight
                weightedLongitudeSum += realBeacon.longitude * weight
                weightSum += weight
            }
            //Log.d(TAG, "Beacon UID: ${beacon.bluetoothAddress}, Distance: ${beacon.distance}, Tx Power: ${beacon.txPower}")
        }

        return if (weightSum > 0) {
            val estimatedLatitude = weightedLatitudeSum / weightSum
            val estimatedLongitude = weightedLongitudeSum / weightSum
            Log.d(TAG, "Szacowana pozycja: Latitude: $estimatedLatitude, Longitude: $estimatedLongitude")
            //updateMarker(estimatedLatitude, estimatedLongitude)
            Pair(estimatedLatitude, estimatedLongitude)
        } else {
            Log.d(TAG, "Nie można oszacować pozycji - brak poprawnych danych.")
            null
        }
    }

    // Earth radius in meters
    private var EARTH_RADIUS_METERS = 6371000.0

    /**
     * Converts geographic coordinates (latitude, longitude) to local Cartesian coordinates (x, y) in meters,
     * relative to a given origin point (latOrigin, lonOrigin).
     * Uses an equirectangular projection approximation, suitable for small distances.
     *
     * @param lat Point's latitude in degrees.
     * @param lon Point's longitude in degrees.
     * @param latOrigin Origin's latitude in degrees.
     * @param lonOrigin Origin's longitude in degrees.
     * @return Pair(x, y) in meters relative to the origin.
     */
    private fun geoToCartesian(lat: Double, lon: Double, latOrigin: Double, lonOrigin: Double): Pair<Double, Double> {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val latOriginRad = Math.toRadians(latOrigin)
        val lonOriginRad = Math.toRadians(lonOrigin)

        val x = (lonRad - lonOriginRad) * cos((latOriginRad + latRad) / 2.0)
        val y = (latRad - latOriginRad)

        // Scale to meters
        return Pair(x * EARTH_RADIUS_METERS, y * EARTH_RADIUS_METERS)
    }

    /**
     * Converts local Cartesian coordinates (x, y) in meters, relative to a given origin point (latOrigin, lonOrigin),
     * back to geographic coordinates (latitude, longitude).
     * Inverse of the equirectangular projection approximation.
     *
     * @param x X coordinate in meters relative to the origin.
     * @param y Y coordinate in meters relative to the origin.
     * @param latOrigin Origin's latitude in degrees.
     * @param lonOrigin Origin's longitude in degrees.
     * @return Pair(latitude, longitude) in degrees.
     */
    private fun cartesianToGeo(x: Double, y: Double, latOrigin: Double, lonOrigin: Double): Pair<Double, Double> {
        val latOriginRad = Math.toRadians(latOrigin)
        val lonOriginRad = Math.toRadians(lonOrigin)

        val latRad = (y / EARTH_RADIUS_METERS) + latOriginRad
        val lonRad = (x / (EARTH_RADIUS_METERS * cos((latOriginRad + latRad) / 2.0))) + lonOriginRad

        val finalLat = Math.toDegrees(latRad)
        val finalLon = Math.toDegrees(lonRad)

        return Pair(finalLat, finalLon)
    }

    private fun calculateDevicePositionByTrilateration(beacons: Collection<Beacon>): Pair<Double, Double>? {
        val minRequiredBeacons = 3
        val epsilon = 1e-6 // Small value for floating point comparison

        // 1. Filter for valid beacons with known positions and positive distance
        val validBeacons = beacons.mapNotNull { beacon ->
            // IMPORTANT: Double-check if beaconUid truly corresponds to bluetoothAddress!
            // If using Eddystone/iBeacon, match using beacon.id1, beacon.id2, etc.
            val archiveData = archiveBeacon.find { it.beaconUid == beacon.bluetoothAddress }

            if (archiveData?.latitude != null && archiveData.longitude != null && beacon.distance > 0) {
                // Pair: (ArchiveBeacon data, estimated distance in meters)
                archiveData to beacon.distance
            } else {
                null
            }
        }

        // 2. Check if we have enough valid beacons
        if (validBeacons.size < minRequiredBeacons) {
            //Log.w(TAG, "Trilateration requires at least $minRequiredBeacons known beacons, found ${validBeacons.size}")
            Toast.makeText(this, "Not enough known beacons for trilateration (${validBeacons.size}/$minRequiredBeacons)", Toast.LENGTH_SHORT).show()
            return null
        }

        // 3. Select the three closest beacons (simplest approach, not always geometrically optimal)
        val sortedBeacons = validBeacons.sortedBy { it.second }.take(minRequiredBeacons) // sortowanie przez odległość i wybieranie 3 najbliższych

        val beacon1Data = sortedBeacons[0].first
        val r1 = sortedBeacons[0].second // Distance in meters
        val beacon2Data = sortedBeacons[1].first
        val r2 = sortedBeacons[1].second // Distance in meters
        val beacon3Data = sortedBeacons[2].first
        val r3 = sortedBeacons[2].second // Distance in meters

        // Use non-nullable latitudes/longitudes (already checked in filter)
        val lat1 = beacon1Data.latitude
        val lon1 = beacon1Data.longitude
        val lat2 = beacon2Data.latitude
        val lon2 = beacon2Data.longitude
        val lat3 = beacon3Data.latitude
        val lon3 = beacon3Data.longitude

        // 4. Convert Geo coordinates to a local Cartesian plane (meters)
        // Beacon 1 is the origin (0, 0)
        val (x2, y2) = geoToCartesian(lat2, lon2, lat1, lon1)
        val (x3, y3) = geoToCartesian(lat3, lon3, lat1, lon1)

        // 5. Perform trilateration calculation in the Cartesian plane (all units are meters)
        // Formulas derived from circle intersection equations:
        // (x - x1)^2 + (y - y1)^2 = r1^2  => x^2 + y^2 = r1^2  (since x1=0, y1=0)
        // (x - x2)^2 + (y - y2)^2 = r2^2
        // (x - x3)^2 + (y - y3)^2 = r3^2

        val A = 2 * x2 // Simplified: 2*x2 - 2*x1 = 2*x2 - 0
        val B = 2 * y2 // Simplified: 2*y2 - 2*y1 = 2*y2 - 0
        val C = r1.pow(2) - r2.pow(2) + x2.pow(2) + y2.pow(2) // Simplified: r1^2 - r2^2 - x1^2 + x2^2 - y1^2 + y2^2
        val D = 2 * (x3 - x2) // 2*x3 - 2*x2
        val E = 2 * (y3 - y2) // 2*y3 - 2*y2
        val F = r2.pow(2) - r3.pow(2) - x2.pow(2) + x3.pow(2) - y2.pow(2) + y3.pow(2)

        val det = A * E - B * D
        if (abs(det) < epsilon) {
            Log.w(TAG, "Trilateration failed: Beacons are likely collinear (determinant is near zero: $det)")
            Toast.makeText(this, "Trilateration failed: Beacon positions might be collinear.", Toast.LENGTH_SHORT).show()
            return null
        }

        // Calculated position (x, y) in meters relative to beacon1
        val x = (C * E - F * B) / det
        val y = (F * A - C * D) / det

        // 6. Convert the calculated Cartesian position (x, y) back to geographic coordinates
        val (estimatedLatitude, estimatedLongitude) = cartesianToGeo(x, y, lat1, lon1)

        Log.d(TAG, "Estimated Position (Trilateration): Lat=$estimatedLatitude, Lon=$estimatedLongitude (relative to B1 @ $lat1, $lon1 with offset x=$x, y=$y)")
        return Pair(estimatedLatitude, estimatedLongitude)
    }

    private fun loadBeaconsFromAssets() {
        val assetManager = assets
        val beaconFiles: List<String> = assetManager.list("")?.filter {
            it.startsWith("beacons_") && it.endsWith(".txt")
        } ?: emptyList()

        val gson = Gson()
        val allBeacons = mutableListOf<ArchiveBeacon>()

        for (fileName in beaconFiles) {
            assetManager.open(fileName).use { inputStream ->
                val jsonText = inputStream.bufferedReader().use { it.readText() }
                val beaconFile = gson.fromJson(jsonText, BeaconFile::class.java)
                beaconFile.items?.forEach { item ->
                    if (item.beaconUid != null) {
                        allBeacons.add(
                            ArchiveBeacon(
                                beaconUid = item.beaconUid,
                                latitude = item.latitude,
                                longitude = item.longitude
                            )
                        )
                    }
                }

            }
        }

        archiveBeacon.clear()
        archiveBeacon.addAll(allBeacons)
    }

    private fun requestRequiredPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        if (allPermissionsGranted(permissions)) {
            listenForConnectionChanges()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun allPermissionsGranted(permissions: Array<String>): Boolean {
        permissions.forEach { permissionName ->
            if (ContextCompat.checkSelfPermission(
                    this,
                    permissionName
                ) == PackageManager.PERMISSION_DENIED
            ) {
                return false
            }
        }
        return true
    }

    private fun listenForConnectionChanges() {
        Toast.makeText(
            this,
            "Upewnij się, że masz włączony GPS oraz Bluetooth.",
            Toast.LENGTH_SHORT
        ).show()
        connectionStateReceiver = ConnectionStateChangeReceiver(
            onBothEnabled = { startScanningIfPossible() },
            onEitherDisabled = { cleanupBeaconManager() }
        )

        val intentFilter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(connectionStateReceiver, intentFilter)

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothEnabled = bluetoothManager.adapter.isEnabled
        if (gpsEnabled && bluetoothEnabled) {
            startScanningIfPossible()
        }
    }

    private fun startScanningIfPossible() {
        Toast.makeText(this, "Skanowanie rozpoczęte :)", Toast.LENGTH_SHORT).show()
        scanForBeacons()

    }

    private fun setUpUI() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }


    override fun onResume() {
        super.onResume()
        mapView.onResume()
        locationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationOverlay.disableMyLocation()
    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(connectionStateReceiver)
        cleanupBeaconManager()

    }
    private fun cleanupBeaconManager() {
        beaconManager?.stopRangingBeacons(region)
    }

}

