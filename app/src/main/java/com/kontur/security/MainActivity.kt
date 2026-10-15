package com.kontur.security

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // Параметры Fake GPS
    private var mockActive = false
    private var mockLat = 55.7558
    private var mockLon = 37.6173
    private var mockAlt = 155.0
    private var mockSpeed = 0.0f
    private var mockBearing = 0.0f
    private var mockJitter = true
    private var mockMoveMode = false

    private val mockRunnable = object : Runnable {
        override fun run() {
            if (mockActive) {
                pushMockLocation()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    if (url.startsWith("tg://") || url.startsWith("https://t.me/")) {
                        return try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            true
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Telegram не установлен", Toast.LENGTH_SHORT).show()
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
                    startActivityForResult(Intent.createChooser(intent, "Выберите фото или камеру"), 1002)
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

    private fun requestNecessaryPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
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
            val distMeters = mockSpeed.toDouble()
            val rad = Math.toRadians(mockBearing.toDouble())
            mockLat += (distMeters * cos(rad)) / 111320.0
            mockLon += (distMeters * sin(rad)) / (111320.0 * cos(Math.toRadians(mockLat)))
        }

        val jitterLat = if (mockJitter) (Random.nextDouble(-0.000012, 0.000012)) else 0.0
        val jitterLon = if (mockJitter) (Random.nextDouble(-0.000012, 0.000012)) else 0.0

        for (provider in providers) {
            try {
                try {
                    lm.addTestProvider(
                        provider,
                        false, false, false, false, true, true, true,
                        1, 1
                    )
                } catch (_: Exception) {}

                lm.setTestProviderEnabled(provider, true)

                val loc = Location(provider).apply {
                    latitude = mockLat + jitterLat
                    longitude = mockLon + jitterLon
                    altitude = mockAlt
                    accuracy = if (mockJitter) Random.nextFloat() * 2.5f + 2.5f else 3.0f
                    speed = mockSpeed
                    bearing = mockBearing
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 2.5f
                        speedAccuracyMetersPerSecond = 0.5f
                        bearingAccuracyDegrees = 5.0f
                    }
                }
                lm.setTestProviderLocation(provider, loc)
            } catch (e: SecurityException) {
                mockActive = false
                return
            } catch (_: Exception) {}
        }
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

    private var pendingVpnPackages: ArrayList<String> = arrayListOf()
    private var pendingVpnDns: String = "1.1.1.1"

    private fun startKonturVpnService() {
        val intent = Intent(this, KonturVpnService::class.java).apply {
            action = "START_VPN"
            putStringArrayListExtra("PACKAGES", pendingVpnPackages)
            putExtra("DNS", pendingVpnDns)
        }
        startService(intent)
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun copyToClipboard(text: String): Boolean {
            return try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("КОНТУР-М", text))
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
        fun getMockCoordinates(): String {
            val obj = JSONObject()
            obj.put("active", mockActive)
            obj.put("lat", mockLat)
            obj.put("lon", mockLon)
            obj.put("alt", mockAlt)
            return obj.toString()
        }

        @JavascriptInterface
        fun openDeveloperSettings() {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        }

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
                    val intent = Intent(this@MainActivity, KonturVpnService::class.java).apply {
                        action = "STOP_VPN"
                    }
                    startService(intent)
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
        fun openTelegramLink(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
            }
        }

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
                Toast.makeText(this@MainActivity, "User-Agent подменён!", Toast.LENGTH_SHORT).show()
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
