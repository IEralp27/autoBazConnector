package com.ieralp27.autobazconnector

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.*
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class BandSpec(
    val name: String,
    val capacityBonus: Int,
    val isHighSpeedBand: Boolean
)

data class TowerInfo(
    val pci: Int,
    val earfcn: Int,
    val enbId: Int,
    val sectorId: Int,
    val band: BandSpec,
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val cqi: Int,
    val speedScore: Int,
    val isRegistered: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var autoSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var isAutoMonitoring = false

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isAutoMonitoring) {
                scanTowers(autoTrigger = true)
                handler.postDelayed(this, 10_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1419"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 48, 44, 56)
        }

        val titleText = TextView(this).apply {
            text = "autoBazConnector • Redmi 12C"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        }

        val subTitleText = TextView(this).apply {
            text = "MediaTek Helio G85 LTE Bant & SINR/RSRQ Hız Optimizasyonu"
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 6, 0, 28)
        }

        val scanBtn = createStyledButton("1. Tara ve En Hızlı Kuleye Geç", "#0284C7")
        val radioInfoBtn = createStyledButton("2. Gizli Radyo Menüsü (*#*#4636#*#*)", "#1E293B")
        val mtkBandBtn = createStyledButton("3. MediaTek Bant Kilidi (EngineerMode)", "#1E293B")
        val lteOnlyRootBtn = createStyledButton("4. Root: Sadece 4.5G (LTE Only) Zorla", "#334155")
        val speedTestBtn = createStyledButton("5. Hücresel Hız & Ping Testi (Mbps)", "#0D9488")

        autoSwitch = Switch(this).apply {
            text = "Canlı Takip Modu (10 sn'de bir kontrol et)"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(8, 20, 8, 20)
        }

        speedText = TextView(this).apply {
            text = "Hız Testi: Henüz ölçülmedi"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5EEAD4"))
            setBackgroundColor(Color.parseColor("#16202A"))
            setPadding(28, 22, 28, 22)
        }

        statusText = TextView(this).apply {
            text = "Hazır. Konum (GPS) servisinin açık olduğundan emin olup taramayı başlatın."
            textSize = 13.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 28, 0, 0)
            setLineSpacing(6f, 1.12f)
        }

        layout.addView(titleText)
        layout.addView(subTitleText)
        layout.addView(scanBtn)
        layout.addView(radioInfoBtn)
        layout.addView(mtkBandBtn)
        layout.addView(lteOnlyRootBtn)
        layout.addView(speedTestBtn)
        layout.addView(autoSwitch)
        layout.addView(speedText)
        layout.addView(statusText)

        scrollView.addView(layout)
        setContentView(scrollView)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        scanBtn.setOnClickListener { checkPermissionsAndScan(autoTrigger = false) }
        radioInfoBtn.setOnClickListener { openHiddenRadioInfo() }
        mtkBandBtn.setOnClickListener { openMediaTekBandMode() }
        lteOnlyRootBtn.setOnClickListener { forceLteOnlyRoot() }
        speedTestBtn.setOnClickListener { runCellularSpeedTest() }

        autoSwitch.setOnCheckedChangeListener { _, isChecked ->
            isAutoMonitoring = isChecked
            handler.removeCallbacks(monitorRunnable)
            if (isChecked) {
                checkPermissionsAndScan(autoTrigger = true)
                handler.postDelayed(monitorRunnable, 10_000L)
            }
        }
    }

    private fun createStyledButton(label: String, bgHex: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(36, 26, 36, 26)
            setBackgroundColor(Color.parseColor(bgHex))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
    }

    private fun checkPermissionsAndScan(autoTrigger: Boolean) {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 101)
            return
        }
        scanTowers(autoTrigger)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanTowers(autoTrigger = false)
        } else {
            statusText.text = "HATA: MIUI/Android baz istasyonu verilerini okumak için Tam Konum ve Telefon izinleri zorunludur."
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanTowers(autoTrigger: Boolean) {
        if (!autoTrigger) {
            statusText.text = "MediaTek modem üzerinden çevredeki LTE hücreleri taranıyor..."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfoList: MutableList<CellInfo>) {
                    processCellList(cellInfoList, autoTrigger)
                }
            })
        } else {
            @Suppress("DEPRECATION")
            processCellList(telephonyManager.allCellInfo ?: emptyList(), autoTrigger)
        }
    }

    private fun decodeLteBand(earfcn: Int): BandSpec {
        return when (earfcn) {
            in 0..599 -> BandSpec("B1 (2100 MHz)", capacityBonus = 18, isHighSpeedBand = true)
            in 1200..1949 -> BandSpec("B3 (1800 MHz)", capacityBonus = 22, isHighSpeedBand = true)
            in 2750..3449 -> BandSpec("B7 (2600 MHz)", capacityBonus = 28, isHighSpeedBand = true)
            in 3450..3799 -> BandSpec("B8 (900 MHz)", capacityBonus = 6, isHighSpeedBand = false)
            in 6150..6449 -> BandSpec("B20 (800 MHz)", capacityBonus = 4, isHighSpeedBand = false)
            in 9210..9659 -> BandSpec("B28 (700 MHz)", capacityBonus = 8, isHighSpeedBand = false)
            in 37750..38249 -> BandSpec("B38 (2600 TDD)", capacityBonus = 24, isHighSpeedBand = true)
            in 38650..39649 -> BandSpec("B40 (2300 TDD)", capacityBonus = 24, isHighSpeedBand = true)
            in 39650..41589 -> BandSpec("B41 (2500 TDD)", capacityBonus = 26, isHighSpeedBand = true)
            else -> BandSpec("EARFCN $earfcn", capacityBonus = 10, isHighSpeedBand = false)
        }
    }

    private fun processCellList(cells: List<CellInfo>, autoTrigger: Boolean) {
        val towers = mutableListOf<TowerInfo>()

        for (cell in cells) {
            if (cell is CellInfoLte) {
                val id = cell.cellIdentity
                val sig = cell.cellSignalStrength

                val pci = if (id.pci != CellInfo.UNAVAILABLE) id.pci else -1
                val earfcn = if (id.earfcn != CellInfo.UNAVAILABLE) id.earfcn else -1
                val ci = if (id.ci != CellInfo.UNAVAILABLE) id.ci else -1
                val enbId = if (ci > 0) ci shr 8 else -1
                val sectorId = if (ci > 0) ci and 0xFF else -1

                val rsrp = if (sig.rsrp != CellInfo.UNAVAILABLE) sig.rsrp else sig.dbm
                val rsrq = if (sig.rsrq != CellInfo.UNAVAILABLE) sig.rsrq else -12
                val rawSinr = if (sig.rssnr != CellInfo.UNAVAILABLE) sig.rssnr else 0
                val sinr = if (rawSinr > 40 || rawSinr < -40) rawSinr / 10 else rawSinr
                val cqi = if (sig.cqi != CellInfo.UNAVAILABLE) sig.cqi else -1

                val band = decodeLteBand(earfcn)

                // Gercek veri hizi odakli puanlama:
                // Yuksek bant genisligi (B7/B3/B1) + dusuk yogunluk (RSRQ) + temiz sinyal (SINR)
                val weakSignalPenalty = if (rsrp < -116) -35 else if (rsrp < -110) -15 else 0
                val rawScore = (rsrp + 140) + (rsrq * 2) + (sinr * 2) + band.capacityBonus + weakSignalPenalty
                val normalizedScore = rawScore.coerceIn(0, 100)

                towers.add(
                    TowerInfo(
                        pci = pci,
                        earfcn = earfcn,
                        enbId = enbId,
                        sectorId = sectorId,
                        band = band,
                        rsrp = rsrp,
                        rsrq = rsrq,
                        sinr = sinr,
                        cqi = cqi,
                        speedScore = normalizedScore,
                        isRegistered = cell.isRegistered
                    )
                )
            }
        }

        if (towers.isEmpty()) {
            statusText.text = "Hücre verisi alınamadı.\n• Kontrol 1: Üst panelden Konum (GPS) açık olmalı.\n• Kontrol 2: Mobil veri / SIM aktif olmalı."
            return
        }

        val sorted = towers.sortedByDescending { it.speedScore }
        val best = sorted.first()
        val current = sorted.find { it.isRegistered }

        val sb = StringBuilder()
        sb.append("EN YÜKSEK HIZ POTANSİYELİ OLAN HÜCRE:\n")
        sb.append("• Bant: ${best.band.name} | PCI: ${best.pci} | EARFCN: ${best.earfcn}\n")
        if (best.enbId > 0) {
            sb.append("• Fiziksel Kule (eNB): ${best.enbId} (Sektör: ${best.sectorId})\n")
        }
        sb.append("• RSRP: ${best.rsrp} dBm | RSRQ: ${best.rsrq} dB | SINR: ${best.sinr} dB\n")
        sb.append("• Hız Kalite Skoru: ${best.speedScore} / 100\n\n")

        val shouldSwitch = current != null &&
            (current.pci != best.pci || current.earfcn != best.earfcn) &&
            (best.speedScore - current.speedScore >= 6)

        if (shouldSwitch && current != null) {
            val sameMastNote = if (current.enbId > 0 && current.enbId == best.enbId) {
                " (Aynı kuledeki daha hızlı ${best.band.name} frekansı tespit edildi!)"
            } else {
                ""
            }
            sb.append("DURUM: Şu an daha yavaş bir hücredesiniz (${current.band.name}, PCI: ${current.pci}, Skor: ${current.speedScore}/100).$sameMastNote\n")
            sb.append("Daha hızlı hücreye geçiş tetikleniyor...\n\n")
            statusText.text = sb.toString()
            triggerTowerHandover(best, autoTrigger)
        } else {
            sb.append("DURUM: Redmi 12C şu an çevredeki en uygun/hızlı hücreye bağlı.\n\n")
            statusText.text = sb.toString()
        }

        sb.append("--- Çevredeki Tüm LTE Hücreleri (${sorted.size} adet) ---\n")
        sorted.forEachIndexed { idx, t ->
            val tag = if (t.isRegistered) " [BAĞLI]" else ""
            val cqiPart = if (t.cqi >= 0) " | CQI:${t.cqi}" else ""
            sb.append("${idx + 1}. ${t.band.name} | PCI:${t.pci} | RSRP:${t.rsrp}dBm | RSRQ:${t.rsrq}dB | SINR:${t.sinr}dB$cqiPart | Skor:${t.speedScore}$tag\n")
        }
        statusText.text = sb.toString()
    }

    private fun triggerTowerHandover(best: TowerInfo, autoTrigger: Boolean) {
        executor.execute {
            val rootSuccess = tryRootRadioRefresh()
            handler.post {
                if (rootSuccess) {
                    statusText.append("
[ROOT MODU] MediaTek radyo modülü yenilendi. PCI ${best.pci} (${best.band.name}) hücresine geçiş tetiklendi.
")
                } else {
                    statusText.append("
[ROOTSUZ MOD] Android güvenlik kısıtı nedeniyle doğrudan zorunlu kilit yapılamadı. ")
                    if (!autoTrigger) {
                        statusText.append("Hızlı geçiş için '2. Gizli Radyo Menüsü' ekranı açılıyor — oradan 'Mobil radyo gücü' anahtarını kapatıp açın (Wi-Fi kopmaz).
")
                        openHiddenRadioInfo()
                    } else {
                        statusText.append("Geçiş yapmak için '2. Gizli Radyo Menüsü' butonuna dokunun.
")
                    }
                }
            }
        }
    }

    private fun tryRootRadioRefresh(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("svc data disable
")
            os.writeBytes("cmd connectivity airplane-mode enable
")
            os.writeBytes("sleep 1
")
            os.writeBytes("cmd connectivity airplane-mode disable
")
            os.writeBytes("svc data enable
")
            os.writeBytes("exit
")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun forceLteOnlyRoot() {
        executor.execute {
            val success = try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                // 11 = NETWORK_MODE_LTE_ONLY
                os.writeBytes("settings put global preferred_network_mode 11
")
                os.writeBytes("settings put global preferred_network_mode1 11
")
                os.writeBytes("cmd connectivity airplane-mode enable
")
                os.writeBytes("sleep 1
")
                os.writeBytes("cmd connectivity airplane-mode disable
")
                os.writeBytes("exit
")
                os.flush()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
            handler.post {
                if (success) {
                    statusText.append("
[ROOT] Cihaz 'Sadece 4.5G (LTE Only)' moduna kilitlendi ve radyo yenilendi.
")
                } else {
                    statusText.append("
[BİLGİ] Cihazda Root bulunamadı. Root'suz olarak '2. Gizli Radyo Menüsü' üzerinden ağ türünü 'LTE only' yapabilirsiniz.
")
                    openHiddenRadioInfo()
                }
            }
        }
    }

    private fun openHiddenRadioInfo() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.RadioInfo")),
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun openMediaTekBandMode() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.mediatek.engineermode", "com.mediatek.engineermode.bandselect.BandMode")),
            Intent().setComponent(ComponentName("com.mediatek.engineermode", "com.mediatek.engineermode.EngineerMode"))
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        statusText.append("
[BİLGİ] MIUI doğrudan EngineerMode aktivitesini engelledi. Telefon uygulamasından *#*#3646633#*#* tuşlayarak MediaTek BandMode menüsüne girebilirsiniz.
")
    }

    private fun runCellularSpeedTest() {
        speedText.text = "Hız Testi: Hücresel ağa bağlanılıyor ve gecikme/indirme ölçülüyor..."

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                executor.execute {
                    try {
                        // 1. Ping / TTFB olcumu
                        val pingStart = System.nanoTime()
                        val pingConn = network.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection
                        pingConn.connectTimeout = 5000
                        pingConn.readTimeout = 5000
                        pingConn.responseCode
                        val pingMs = ((System.nanoTime() - pingStart) / 1_000_000.0).roundToInt()
                        pingConn.disconnect()

                        // 2. Indirme hizi olcumu (2 MB)
                        val dlUrl = URL("https://speed.cloudflare.com/__down?bytes=2000000")
                        val dlConn = network.openConnection(dlUrl) as HttpURLConnection
                        dlConn.connectTimeout = 8000
                        dlConn.readTimeout = 10000
                        val input = dlConn.inputStream
                        val buffer = ByteArray(8192)
                        var totalBytes = 0L
                        val startNs = System.nanoTime()
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            totalBytes += read
                        }
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                        input.close()
                        dlConn.disconnect()

                        val mbps = if (elapsedSec > 0) (totalBytes * 8.0) / (elapsedSec * 1_000_000.0) else 0.0
                        val formattedMbps = String.format(Locale.US, "%.2f", mbps)

                        handler.post {
                            speedText.text = "Hücresel Hız: $formattedMbps Mbps | Ping: $pingMs ms"
                        }
                    } catch (e: Exception) {
                        handler.post {
                            speedText.text = "Hız Testi Hatası: Mobil veri aktif olduğundan emin olun (${e.localizedMessage})"
                        }
                    } finally {
                        try {
                            connectivityManager.unregisterNetworkCallback(this)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onUnavailable() {
                handler.post {
                    speedText.text = "Hız Testi: Hücresel internet bağlantısı bulunamadı (Mobil veriyi açın)."
                }
            }
        }

        connectivityManager.requestNetwork(request, callback, 6000)
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMonitoring = false
        handler.removeCallbacks(monitorRunnable)
        executor.shutdownNow()
    }
}
