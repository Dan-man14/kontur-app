package com.kontur.security

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.*
import android.util.Base64
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // Состояние Fake GPS
    private var mockActive = false
    private var mockLat = 55.75582
    private var mockLon = 37.61730
    private var mockAlt = 158.0
    private var mockSpeed = 0.0f
    private var mockBearing = 45.0f
    private var mockJitter = true
    private var mockMoveMode = false

    private var pendingVpnPackages: ArrayList<String> = arrayListOf()
    private var pendingVpnDns: String = "1.1.1.1"

    private val mockRunnable = object : Runnable {
        override fun run() {
            if (mockActive) {
                pushMockLocation()
                mainHandler.postDelayed(this, 600)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Принудительное включение 120 Гц (High Refresh Rate) и аппаратного ускорения GPU
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        enableHighRefreshRate()

        requestNecessaryPermissions()

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("tg://") || url.startsWith("https://t.me/") || url.startsWith("https://wa.me/")) {
                        return try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            true
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Приложение не найдено", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { request.grant(request.resources) }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    startActivityForResult(Intent.createChooser(intent, "Выберите изображение или камеру"), 1002)
                    return true
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun enableHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val display = windowManager.defaultDisplay
                val modes = display.supportedModes
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = maxMode.modeId
                    window.attributes = params
                }
            }
        } catch (_: Exception) {}
    }

    private fun requestNecessaryPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA
        )
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    @Suppress("DEPRECATION")
    private fun pushMockLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        if (mockMoveMode && mockSpeed > 0f) {
            val distMeters = mockSpeed.toDouble() * 0.6
            val rad = Math.toRadians(mockBearing.toDouble())
            mockLat += (distMeters * cos(rad)) / 111320.0
            mockLon += (distMeters * sin(rad)) / (111320.0 * cos(Math.toRadians(mockLat)))
        }

        val jitterLat = if (mockJitter) Random.nextDouble(-0.000010, 0.000010) else 0.0
        val jitterLon = if (mockJitter) Random.nextDouble(-0.000010, 0.000010) else 0.0

        for (provider in providers) {
            try {
                try {
                    lm.addTestProvider(provider, false, false, false, false, true, true, true, 1, 1)
                } catch (_: Exception) {}
                lm.setTestProviderEnabled(provider, true)

                val loc = Location(provider).apply {
                    latitude = mockLat + jitterLat
                    longitude = mockLon + jitterLon
                    altitude = mockAlt
                    accuracy = if (mockJitter) Random.nextFloat() * 1.8f + 1.5f else 2.0f
                    speed = mockSpeed
                    bearing = mockBearing
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 1.5f
                        speedAccuracyMetersPerSecond = 0.2f
                        bearingAccuracyDegrees = 2.0f
                    }
                }
                lm.setTestProviderLocation(provider, loc)
            } catch (e: SecurityException) {
                mockActive = false
                return
            } catch (_: Exception) {}
        }
    }

    private fun startKonturVpnService() {
        val intent = Intent(this, KonturVpnService::class.java).apply {
            action = "START_VPN"
            putStringArrayListExtra("PACKAGES", pendingVpnPackages)
            putExtra("DNS", pendingVpnDns)
        }
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002) {
            val result = if (resultCode == Activity.RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileUploadCallback?.onReceiveValue(result)
            fileUploadCallback = null
        } else if (requestCode == 1003 && resultCode == Activity.RESULT_OK) {
            startKonturVpnService()
        }
    }

    inner class AndroidBridge {

        // 1. БИОМЕТРИЧЕСКИЙ СКАНЕР ОТПЕЧАТКА ПАЛЬЦА
        @JavascriptInterface
        fun authenticateBiometric() {
            runOnUiThread {
                try {
                    val executor = ContextCompat.getMainExecutor(this@MainActivity)
                    val prompt = BiometricPrompt(
                        this@MainActivity,
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                vibrate(40)
                                webView.evaluateJavascript("window.onBiometricSuccess && window.onBiometricSuccess()", null)
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                super.onAuthenticationError(errorCode, errString)
                                webView.evaluateJavascript("window.onBiometricError && window.onBiometricError('${errString}')", null)
                            }

                            override fun onAuthenticationFailed() {
                                super.onAuthenticationFailed()
                                vibrate(120)
                            }
                        }
                    )

                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("КОНТУР-М // СПЕЦДОПУСК")
                        .setSubtitle("Приложите отпечаток пальца для разблокировки терминала")
                        .setNegativeButtonText("Ввести ПИН-код")
                        .build()

                    prompt.authenticate(info)
                } catch (e: Exception) {
                    webView.evaluateJavascript("window.onBiometricError && window.onBiometricError('Биометрия недоступна')", null)
                }
            }
        }

        // 2. ТАКТИЛЬНАЯ ВИБРАЦИЯ
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun vibrate(ms: Long) {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v.vibrate(ms)
                }
            } catch (_: Exception) {}
        }

        // 3. НАТИВНЫЙ HTTP-МОСТ (ОБХОД ЛЮБЫХ БЛОКИРОВОК CORS ДЛЯ SHODAN, ADS-B, CRT.SH, OSINT)
        @JavascriptInterface
        fun httpGet(urlStr: String): String {
            return try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) KonturOSINT/4.0")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val reader = BufferedReader(InputStreamReader(stream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                reader.close()
                sb.toString().trim()
            } catch (e: Exception) {
                "ERR:${e.message}"
            }
        }

        // 4. НАТИВНЫЙ МНОГОПОТОЧНЫЙ СКАНЕР TCP-ПОРТОВ
        @JavascriptInterface
        fun scanTcpPorts(host: String, portsCsv: String): String {
            val ports = portsCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
            val pool = Executors.newFixedThreadPool(12)
            val tasks = ports.map { port ->
                Callable {
                    val t0 = System.currentTimeMillis()
                    try {
                        val sock = Socket()
                        sock.connect(InetSocketAddress(host.trim(), port), 450)
                        sock.close()
                        val rtt = System.currentTimeMillis() - t0
                        JSONObject().apply {
                            put("port", port)
                            put("open", true)
                            put("ms", rtt)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val arr = JSONArray()
            try {
                val results = pool.invokeAll(tasks)
                for (r in results) {
                    val obj = r.get()
                    if (obj != null) arr.put(obj)
                }
            } catch (_: Exception) {}
            pool.shutdown()
            return arr.toString()
        }

        // 5. АППАРАТНАЯ КРИМИНАЛИСТИКА УСТРОЙСТВА И WI-FI
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun getHardwareForensics(): String {
            val obj = JSONObject()
            try {
                obj.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                obj.put("board", "${Build.BOARD} / ${Build.HARDWARE}")
                obj.put("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                obj.put("patch", Build.VERSION.SECURITY_PATCH)
                obj.put("fingerprint", Build.FINGERPRINT)
                obj.put("abis", Build.SUPPORTED_ABIS.joinToString(", "))

                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ipInt = wm.connectionInfo.ipAddress
                val localIp = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                obj.put("localIp", localIp)
                obj.put("rssi", "${wm.connectionInfo.rssi} dBm")
                obj.put("linkSpeed", "${wm.connectionInfo.linkSpeed} Mbps")
            } catch (_: Exception) {}
            return obj.toString()
        }

        // 6. БУФЕР ОБМЕНА
        @JavascriptInterface
        fun copyToClipboard(text: String): Boolean {
            return try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("КОНТУР-М", text))
                vibrate(25)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun pasteFromClipboard(): String {
            return try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
            } catch (e: Exception) {
                ""
            }
        }

        // 7. FAKE GPS (СИСТЕМНАЯ ПОДМЕНА КООРДИНАТ)
        @JavascriptInterface
        fun startFakeGps(
            lat: Double,
            lon: Double,
            alt: Double,
            speedKmh: Double,
            bearing: Double,
            jitter: Boolean,
            moveMode: Boolean
        ): String {
            mockLat = lat
            mockLon = lon
            mockAlt = alt
            mockSpeed = (speedKmh / 3.6).toFloat()
            mockBearing = bearing.toFloat()
            mockJitter = jitter
            mockMoveMode = moveMode

            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                try {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 1, 1)
                } catch (_: Exception) {}
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

                mockActive = true
                mainHandler.removeCallbacks(mockRunnable)
                mainHandler.post(mockRunnable)
                vibrate(50)
                "OK"
            } catch (e: SecurityException) {
                mockActive = false
                "ERR_DEV_SETTINGS"
            } catch (e: Exception) {
                mockActive = false
                "ERR: ${e.message}"
            }
        }

        @JavascriptInterface
        fun stopFakeGps(): String {
            mockActive = false
            mainHandler.removeCallbacks(mockRunnable)
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.setTestProviderEnabled(p, false)
                    lm.removeTestProvider(p)
                } catch (_: Exception) {}
            }
            return "STOPPED"
        }

        @JavascriptInterface
        fun openDeveloperSettings() {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        // 8. ПЕРЕХВАТ БАЗОВЫХ СТАНЦИЙ МОДЕМА (BTS)
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun getNativeCellTowers(): String {
            val arr = JSONArray()
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val cells = tm.allCellInfo ?: return arr.toString()
                for (cell in cells) {
                    val obj = JSONObject()
                    when (cell) {
                        is CellInfoLte -> {
                            obj.put("type", "LTE (4G)")
                            obj.put("cid", cell.cellIdentity.ci)
                            obj.put("tac", cell.cellIdentity.tac)
                            obj.put("pci", cell.cellIdentity.pci)
                            obj.put("dbm", cell.cellSignalStrength.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                        is CellInfoGsm -> {
                            obj.put("type", "GSM (2G)")
                            obj.put("cid", cell.cellIdentity.cid)
                            obj.put("tac", cell.cellIdentity.lac)
                            obj.put("pci", cell.cellIdentity.bsic)
                            obj.put("dbm", cell.cellSignalStrength.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                        is CellInfoWcdma -> {
                            obj.put("type", "WCDMA (3G)")
                            obj.put("cid", cell.cellIdentity.cid)
                            obj.put("tac", cell.cellIdentity.lac)
                            obj.put("pci", cell.cellIdentity.psc)
                            obj.put("dbm", cell.cellSignalStrength.dbm)
                            obj.put("registered", cell.isRegistered)
                        }
                    }
                    if (obj.has("type")) arr.put(obj)
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        // 9. СПИСОК ПРИЛОЖЕНИЙ И SPLIT-VPN
        @JavascriptInterface
        fun getInstalledApps(): String {
            val arr = JSONArray()
            try {
                val pm = packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in apps) {
                    val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            app.packageName.contains("youtube") ||
                            app.packageName.contains("telegram") ||
                            app.packageName.contains("chrome") ||
                            app.packageName.contains("vending")
                    if (isUserApp) {
                        val obj = JSONObject()
                        obj.put("name", pm.getApplicationLabel(app).toString())
                        obj.put("pkg", app.packageName)
                        arr.put(obj)
                    }
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface
        fun launchApp(pkg: String): Boolean {
            return try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    startActivity(intent)
                    true
                } else false
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun toggleVpn(enable: Boolean, packagesCsv: String, dns: String): String {
            return try {
                if (!enable) {
                    startService(Intent(this@MainActivity, KonturVpnService::class.java).setAction("STOP_VPN"))
                    "VPN_STOPPED"
                } else {
                    pendingVpnPackages = ArrayList(packagesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    pendingVpnDns = dns.ifEmpty { "1.1.1.1" }
                    val prep = VpnService.prepare(this@MainActivity)
                    if (prep != null) {
                        startActivityForResult(prep, 1003)
                        "VPN_PERMISSION_REQUESTED"
                    } else {
                        startKonturVpnService()
                        "VPN_STARTED"
                    }
                }
            } catch (e: Exception) {
                "ERR: ${e.message}"
            }
        }

        @JavascriptInterface
        fun openExternalLink(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }

        // 10. СОХРАНЕНИЕ СПЕЦ-ФОТО С GPS ШТАМПОМ В ГАЛЕРЕЮ И ОТПРАВКА
        @JavascriptInterface
        fun savePhotoToGallery(base64Data: String, shareImmediately: Boolean): String {
            return try {
                val cleanBase64 = base64Data.substringAfter(",")
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val fileName = "KONTUR_GPS_${System.currentTimeMillis()}.jpg"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Kontur-M")
                    }
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return "ERR_URI"

                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }

                vibrate(60)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Сохранено в Галерею: DCIM/Kontur-M/$fileName", Toast.LENGTH_LONG).show()
                }

                if (shareImmediately) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Отправить спец-фото КОHТУР-М"))
                }
                "OK:$fileName"
            } catch (e: Exception) {
                "ERR:${e.message}"
            }
        }

        @JavascriptInterface
        fun setCustomUserAgent(ua: String) {
            runOnUiThread {
                webView.settings.userAgentString = ua
                Toast.makeText(this@MainActivity, "User-Agent изменен", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class KonturVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            stopSelf()
            return START_NOT_STICKY
        }

        try { vpnInterface?.close() } catch (_: Exception) {}

        val packages = intent?.getStringArrayListExtra("PACKAGES") ?: arrayListOf()
        val dns = intent?.getStringExtra("DNS") ?: "1.1.1.1"

        try {
            val builder = Builder()
                .setSession("КОНТУР-М // СПЕЦТУННЕЛЬ")
                .setMtu(1280)
                .addAddress("10.255.255.1", 30)
                .addRoute("10.255.255.0", 30)
                .addDnsServer(dns)
                .addDnsServer("8.8.8.8")

            for (pkg in packages) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: Exception) {}
            }

            vpnInterface = builder.establish()
        } catch (_: Exception) {}

        return START_STICKY
    }

    override fun onDestroy() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
