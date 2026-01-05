package com.securesos.aegis

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// 1. SECURITY LAYER (HARDWARE BACKED)
// ============================================================================
data class Contact(val id: String, val name: String, val keyBase64: String)

object SecurityUtils {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val KEYSTORE_ALIAS = "AegisMasterKey"

    // Generate a standard AES key for communication
    fun generateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    // Encrypt Data (Payload)
    fun encrypt(data: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    // Decrypt Data (Payload)
    fun decrypt(data: ByteArray, key: SecretKey): String? {
        return try {
            val spec = GCMParameterSpec(128, data.copyOfRange(0, 12))
            val cipher = Cipher.getInstance(AES_MODE).apply { init(Cipher.DECRYPT_MODE, key, spec) }
            String(cipher.doFinal(data.copyOfRange(12, data.size)), StandardCharsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // --- HARDWARE KEYSTORE WRAPPERS (FIX FOR SECURITY CRITIQUE) ---
    // Encrypts the AES key using the Phone's Hardware RSA Key before saving to disk
    fun wrapKey(secretKey: SecretKey): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            kpg.initialize(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).build())
            kpg.generateKeyPair()
        }
        val pubKey = ks.getCertificate(KEYSTORE_ALIAS).publicKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.WRAP_MODE, pubKey)
        return Base64.encodeToString(cipher.wrap(secretKey), Base64.NO_WRAP)
    }

    // Decrypts the AES key using the Phone's Hardware RSA Key
    fun unwrapKey(wrappedKeyStr: String): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privKey = ks.getKey(KEYSTORE_ALIAS, null)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.UNWRAP_MODE, privKey)
            cipher.unwrap(Base64.decode(wrappedKeyStr, Base64.NO_WRAP), "AES", Cipher.SECRET_KEY) as SecretKey
        } catch (e: Exception) { null }
    }
}

// ============================================================================
// 2. BACKGROUND SERVICE (ROBUST ENGINE)
// ============================================================================
class AegisService : Service() {
    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    var isSosActive = false
    var isAckActive = false
    var isCodedPhySupported = false
    private val contacts = mutableListOf<Contact>()
    private val messageCache = mutableSetOf<String>()
    private var targetAckId: String? = null
    
    // Hardware State Machine (FIX FOR RADIO JAMMING)
    private var isRadioBusy = false 
    
    var onLog: ((String) -> Unit)? = null
    var onStatusChange: ((String) -> Unit)? = null
    var onHardwareStatus: ((String, Boolean) -> Unit)? = null
    var onMessageReceived: ((String, String, String, Double, Double, Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentContactIndex = 0

    private val rotationRunnable = object : Runnable {
        override fun run() {
            if (contacts.isEmpty()) return
            
            // FIX: Index Safety
            if (currentContactIndex >= contacts.size) currentContactIndex = 0
            
            // FIX: Don't command radio if it's busy stopping/starting
            if (isRadioBusy) {
                handler.postDelayed(this, 200) // Wait and retry
                return
            }

            if (isSosActive) {
                val loc = getLastKnownLocation()
                if (loc != null) log("GPS: Lock Acquired")
                
                // FIX: Stop -> Wait Callback -> Start
                stopAdvertising()
                // We wait for the stop to clear in the helper, then broadcast
                handler.postDelayed({
                    broadcastToNextContact("SOS", loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
                }, 200) // 200ms Hardware Cool-down
                
                handler.postDelayed(this, 3000)
            } else if (isAckActive && targetAckId != null) {
                val contact = contacts.find { it.id == targetAckId }
                if (contact != null) {
                    stopAdvertising()
                    handler.postDelayed({
                        broadcastPacket(contact, "ACK", 0.0, 0.0)
                    }, 200)
                    
                    // FIX: Extended ACK Duration (30s)
                    handler.postDelayed({ stopBroadcast() }, 30000)
                } else {
                    stopBroadcast()
                }
            }
        }
    }

    inner class LocalBinder : Binder() { fun getService(): AegisService = this@AegisService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        isCodedPhySupported = bluetoothAdapter?.isLeCodedPhySupported == true
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aegis:RadioLock")
        wakeLock?.acquire()
        
        loadContacts()
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
    }

    fun startEngine() {
        val channel = NotificationChannel("Aegis", "Aegis Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "Aegis").setContentTitle("Aegis Tactical").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build())
        startScanning()
        onHardwareStatus?.invoke("SVC", true)
        log("ENGINE: Online (WakeLock Active)")
        log("PHY: ${if(isCodedPhySupported) "CODED (LONG RANGE)" else "LEGACY (SHORT RANGE)"}")
    }

    fun startSos() {
        if (contacts.isEmpty()) { log("ABORT: No contacts!"); return }
        isSosActive = true
        isAckActive = false
        onStatusChange?.invoke("SOS")
        handler.post(rotationRunnable)
        log("SOS: Broadcasting...")
    }

    fun sendAck(targetId: String) {
        isSosActive = false
        isAckActive = true
        targetAckId = targetId
        onStatusChange?.invoke("ACK")
        handler.post(rotationRunnable)
        log("ACK: Responding...")
    }

    fun stopBroadcast() {
        isSosActive = false
        isAckActive = false
        handler.removeCallbacks(rotationRunnable)
        stopAdvertising()
        onStatusChange?.invoke("IDLE")
        log("Radio: Silent")
    }

    private fun broadcastToNextContact(type: String, lat: Double, lng: Double) {
        if (contacts.isEmpty()) return
        val contact = contacts[currentContactIndex]
        currentContactIndex = (currentContactIndex + 1) % contacts.size
        broadcastPacket(contact, type, lat, lng)
    }

    private fun broadcastPacket(contact: Contact, type: String, lat: Double, lng: Double) {
        try {
            isRadioBusy = true // Lock Radio
            val myId = getSharedPreferences("AegisPrefs", MODE_PRIVATE).getString("MY_ID", "UNK") ?: "UNK"
            val json = JSONObject().apply {
                put("t", type); put("s", myId); put("ts", System.currentTimeMillis())
                if (lat != 0.0) { put("l", lat); put("g", lng) }
            }
            
            // Decrypt the stored key using Hardware KeyStore, then use it
            val wrappedKey = contact.keyBase64
            val key = SecurityUtils.unwrapKey(wrappedKey) ?: SecretKeySpec(Base64.decode(wrappedKey, Base64.NO_WRAP), "AES") // Fallback for old keys
            
            val encrypted = SecurityUtils.encrypt(json.toString(), key)

            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false).setConnectable(false).setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .setPrimaryPhy(if (isCodedPhySupported) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(if (isCodedPhySupported) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M).build()

            val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))).addServiceData(ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb")), encrypted).build()
            
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            advertiser?.startAdvertisingSet(params, data, null, null, null, advCallback)
            
            log("TX: [$type] -> ${contact.name}")
        } catch (e: Exception) { 
            log("TX_ERR: ${e.message}")
            isRadioBusy = false // Unlock on error
        }
    }

    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertisingSet(advCallback)
        } catch (e: Exception) {}
        // We don't set isRadioBusy = false here, we wait for the callback or timeout
        Handler(Looper.getMainLooper()).postDelayed({ isRadioBusy = false }, 200) // Safety timeout
    }

    // FIX: Explicit Callback for Radio State
    private val advCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(s: AdvertisingSet?, p: Int, status: Int) {
            isRadioBusy = false // Unlock
            if (status == ADVERTISE_SUCCESS) onHardwareStatus?.invoke("BT", true)
            else {
                log("Radio Fail Code: $status")
                onHardwareStatus?.invoke("BT", false)
            }
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            isRadioBusy = false // Unlock
        }
    }

    private fun startScanning() {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED).build()
        scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(ct: Int, res: ScanResult?) {
                res?.scanRecord?.serviceData?.values?.firstOrNull()?.let { processPacket(it, res.rssi) }
            }
        })
    }

    private fun processPacket(data: ByteArray, rssi: Int) {
        val wrappedKey = getSharedPreferences("AegisPrefs", MODE_PRIVATE).getString("MY_PRIVATE_KEY", "") ?: ""
        val myKey = SecurityUtils.unwrapKey(wrappedKey) ?: return
        
        SecurityUtils.decrypt(data, myKey)?.let { jsonStr ->
            val json = JSONObject(jsonStr)
            val sender = json.getString("s")
            val ts = json.getLong("ts")
            if (System.currentTimeMillis() - ts < 60000 && messageCache.add("${sender}_$ts")) {
                val name = contacts.find { it.id == sender }?.name ?: "Unknown"
                Handler(Looper.getMainLooper()).post { 
                    onMessageReceived?.invoke(json.getString("t"), name, sender, json.optDouble("l", 0.0), json.optDouble("g", 0.0), rssi) 
                }
            }
            return
        }
        log("SIGNAL: Ghost Packet ($rssi dBm)")
    }

    private fun getLastKnownLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val loc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) onHardwareStatus?.invoke("GPS", true)
        return loc
    }

    fun addContact(c: Contact) { contacts.add(c); saveContacts() }
    fun getContactsList(): List<Contact> = contacts
    fun removeContact(c: Contact) { contacts.remove(c); saveContacts() }
    private fun loadContacts() { try { val jsonArray = JSONArray(getSharedPreferences("AegisPrefs", MODE_PRIVATE).getString("CONTACTS", "[]")); for (i in 0 until jsonArray.length()) { val obj = jsonArray.getJSONObject(i); contacts.add(Contact(obj.getString("id"), obj.getString("name"), obj.getString("keyBase64"))) } } catch (e: Exception) { } }
    private fun saveContacts() { val array = JSONArray(); contacts.forEach { array.put(JSONObject().apply { put("id", it.id); put("name", it.name); put("keyBase64", it.keyBase64) }) }; getSharedPreferences("AegisPrefs", MODE_PRIVATE).edit().putString("CONTACTS", array.toString()).apply() }
    private fun log(msg: String) { Handler(Looper.getMainLooper()).post { onLog?.invoke(msg) } }
}

// ============================================================================
// 3. MAIN ACTIVITY
// ============================================================================
class MainActivity : Activity() {
    private var service: AegisService? = null
    private var isBound = false
    private lateinit var myId: String
    private lateinit var consoleText: TextView
    private lateinit var sosBtn: Button
    private lateinit var pulseView: View
    private lateinit var contactsLayout: LinearLayout
    private lateinit var btIcon: View; private lateinit var gpsIcon: View; private lateinit var svcIcon: View
    private lateinit var trackerLayout: LinearLayout; private lateinit var trackerText: TextView; private lateinit var signalBar: ProgressBar; private lateinit var gpsText: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            service = (b as AegisService.LocalBinder).getService()
            isBound = true
            setupServiceCallbacks()
            refreshContacts()
            service?.startEngine()
        }
        override fun onServiceDisconnected(n: ComponentName?) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("AegisPrefs", MODE_PRIVATE)
        myId = prefs.getString("MY_ID", null) ?: UUID.randomUUID().toString().substring(0, 8).also { prefs.edit().putString("MY_ID", it).apply() }
        
        // FIX: Generate and WRAP the key using Hardware Keystore
        if (prefs.getString("MY_PRIVATE_KEY", null) == null) {
            val key = SecurityUtils.generateKey()
            val wrapped = SecurityUtils.wrapKey(key)
            prefs.edit().putString("MY_PRIVATE_KEY", wrapped).apply()
        }
        
        setupTacticalUI()
    }

    private fun setupTacticalUI() {
        val bg = Color.parseColor("#050505"); val acc = Color.parseColor("#00FF41"); val alrt = Color.parseColor("#FF3333")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(30, 50, 30, 30) }
        
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 30) }
        head.addView(TextView(this).apply { text = "AEGIS // TACTICAL"; textSize = 18f; typeface = Typeface.MONOSPACE; setTextColor(acc); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        btIcon = createPill("BT"); gpsIcon = createPill("GPS"); svcIcon = createPill("SVC")
        head.addView(btIcon); head.addView(gpsIcon); head.addView(svcIcon); root.addView(head)
        
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,0,0,20) }
        btnRow.addView(Button(this).apply { text = "BOOT SYSTEM"; setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE); setOnClickListener { checkPermissions(); visibility = View.GONE }; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        root.addView(btnRow)

        trackerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { setColor(Color.parseColor("#111111")); setStroke(2, alrt); cornerRadius = 10f }; setPadding(20, 20, 20, 20); visibility = View.GONE }
        trackerText = TextView(this).apply { text = "SIGNAL DETECTED"; setTextColor(alrt); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        gpsText = TextView(this).apply { text = "GPS: SEARCHING..."; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; textSize = 14f }
        signalBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; layoutParams = LinearLayout.LayoutParams(-1, 20) }
        trackerLayout.addView(trackerText); trackerLayout.addView(gpsText); trackerLayout.addView(signalBar); root.addView(trackerLayout)
        
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(500, 500).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 30, 0, 30) } }
        pulseView = View(this).apply { layoutParams = FrameLayout.LayoutParams(450, 450, Gravity.CENTER); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#33FF0000")) }; alpha = 0f }
        sosBtn = Button(this).apply { text = "SOS"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(alrt); layoutParams = FrameLayout.LayoutParams(350, 350, Gravity.CENTER); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1a1a1a")); setStroke(5, alrt) }; setOnClickListener { toggleSos() } }
        frame.addView(pulseView); frame.addView(sosBtn); root.addView(frame)
        
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(createSmallBtn("MY QR") { showQr() }); row.addView(createSmallBtn("SCAN") { scanQr() }); root.addView(row)
        contactsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; rootLayoutScroll(root).addView(contactsLayout)
        
        root.addView(TextView(this).apply { text = "> SYSTEM CONSOLE"; setTextColor(acc); textSize = 10f; setPadding(0, 20, 0, 5) })
        consoleText = TextView(this).apply { setTextColor(acc); textSize = 9f; typeface = Typeface.MONOSPACE; text = "AEGIS OS READY...\n" }
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 300); setBackgroundColor(Color.BLACK); setPadding(15, 15, 15, 15); addView(consoleText) }
        root.addView(scroll); setContentView(root)
    }

    private fun rootLayoutScroll(root: LinearLayout): LinearLayout {
        val s = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.addView(l); root.addView(s); return l
    }

    private fun createPill(t: String) = TextView(this).apply { text = t; textSize = 8f; setTextColor(Color.BLACK); setBackgroundColor(Color.RED); setPadding(10, 5, 10, 5); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(60, 40).apply { marginStart = 10 } }
    private fun createSmallBtn(t: String, a: () -> Unit) = Button(this).apply { text = t; textSize = 10f; setTextColor(Color.BLACK); background = GradientDrawable().apply { setColor(Color.LTGRAY); cornerRadius = 5f }; layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply { marginStart = 5; marginEnd = 5 }; setOnClickListener { a() } }

    private fun setupServiceCallbacks() {
        service?.onLog = { msg -> consoleText.append("[${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg\n"); (consoleText.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        service?.onHardwareStatus = { type, ok -> when(type){ "BT"->btIcon; "GPS"->gpsIcon; "SVC"->svcIcon; else->null }?.setBackgroundColor(if(ok) Color.GREEN else Color.RED) }
        
        service?.onStatusChange = { status ->
            when (status) {
                "SOS" -> { 
                    startPulse()
                    sosBtn.text = "STOP"
                    sosBtn.setTextColor(Color.BLACK)
                    sosBtn.background = createButtonBg(Color.RED, Color.RED)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                "ACK" -> { 
                    startPulse()
                    sosBtn.text = "ACK"
                    sosBtn.setTextColor(Color.BLACK)
                    sosBtn.background = createButtonBg(Color.BLUE, Color.BLUE)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                else -> { 
                    stopPulse()
                    sosBtn.text = "SOS"
                    sosBtn.setTextColor(Color.RED)
                    sosBtn.background = createButtonBg(Color.parseColor("#1a1a1a"), Color.RED)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        
        service?.onMessageReceived = { type, name, id, lat, lng, rssi ->
            if (type == "SOS") {
                trackerLayout.visibility = View.VISIBLE; trackerText.text = "SOS: $name"; gpsText.text = if(lat!=0.0) "GPS: $lat, $lng" else "GPS: NO LOCK"
                signalBar.progress = ((rssi + 100) * 1.6).toInt().coerceIn(0, 100)
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1))
                
                if (!sosBtn.text.contains("ACK")) {
                    AlertDialog.Builder(this).setTitle("⚠️ SOS RECEIVED").setMessage("From: $name\nSignal: $rssi dBm")
                        .setPositiveButton("RESPOND") { _, _ -> service?.sendAck(id) }
                        .setNegativeButton("IGNORE", null).show()
                }
            } else if (type == "ACK") {
                AlertDialog.Builder(this).setTitle("✅ HELP COMING").setMessage("$name is responding!").setPositiveButton("OK", null).show()
            }
        }
    }

    private fun createButtonBg(fill: Int, stroke: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(5, stroke) }
    private fun toggleSos() { if (service?.isSosActive == true) service?.stopBroadcast() else service?.startSos() }
    private fun startPulse() { ObjectAnimator.ofPropertyValuesHolder(pulseView, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.4f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.4f), PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)).apply { duration = 1000; repeatCount = ObjectAnimator.INFINITE; interpolator = AccelerateDecelerateInterpolator(); start() } }
    private fun stopPulse() { pulseView.animate().cancel(); pulseView.alpha = 0f }
    private fun refreshContacts() { contactsLayout.removeAllViews(); service?.getContactsList()?.forEach { c -> contactsLayout.addView(TextView(this).apply { text = "[+] ${c.name}"; setTextColor(Color.CYAN); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(10, 10, 10, 10); setOnClickListener { service?.removeContact(c); refreshContacts() } }) } }
    
    // FIX: Show the WRAPPED key in QR (so other phones can unwrap it if they have the logic, or we just share the raw key if we assume trust. 
    // NOTE: For QR sharing between devices, we usually share the RAW key because the other device has a DIFFERENT Hardware Keystore.
    // So we UNWRAP it before showing QR.
    private fun showQr() { 
        try { 
            val prefs = getSharedPreferences("AegisPrefs", MODE_PRIVATE)
            val wrapped = prefs.getString("MY_PRIVATE_KEY", "") ?: ""
            val rawKey = SecurityUtils.unwrapKey(wrapped)
            val rawKeyStr = Base64.encodeToString(rawKey?.encoded, Base64.NO_WRAP)
            
            val json = JSONObject().apply { put("id", myId); put("key", rawKeyStr) }
            val bmp = BarcodeEncoder().encodeBitmap(json.toString(), BarcodeFormat.QR_CODE, 500, 500)
            val iv = ImageView(this).apply { setImageBitmap(bmp) }
            AlertDialog.Builder(this).setView(iv).setTitle("MY IDENTITY").show()
        } catch (e: Exception) { } 
    }
    
    // FIX: When scanning, we WRAP the key before saving
    private fun scanQr() { try { val integrator = IntentIntegrator(this); integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE); integrator.setPrompt("SCAN CONTACT QR"); integrator.initiateScan() } catch (e: Exception) { } }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            try {
                val json = JSONObject(result.contents); val id = json.getString("id"); val keyStr = json.getString("key")
                val input = EditText(this)
                AlertDialog.Builder(this).setTitle("NAME CONTACT").setView(input).setPositiveButton("SAVE") { _, _ -> 
                    // Wrap the key before saving to contacts
                    val rawKey = SecretKeySpec(Base64.decode(keyStr, Base64.NO_WRAP), "AES")
                    val wrappedKey = SecurityUtils.wrapKey(rawKey)
                    service?.addContact(Contact(id, input.text.toString().ifEmpty { "UNIT" }, wrappedKey))
                    refreshContacts() 
                }.show()
            } catch (e: Exception) { }
        }
    }
    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) { perms.add(Manifest.permission.BLUETOOTH_SCAN); perms.add(Manifest.permission.BLUETOOTH_ADVERTISE); perms.add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) perms.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101) else startServiceSafe()
    }
    private fun startServiceSafe() { try { val intent = Intent(this, AegisService::class.java); startForegroundService(intent); bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (e: Exception) { } }
    override fun onDestroy() { super.onDestroy(); if (isBound) unbindService(connection) }
}
