package com.ieralp27.autobazconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BandSpec(
    val name: String,
    val capacityBonus: Int,
    val isHighSpeedBand: Boolean
)

data class TowerInfo(
    val rat: String,          // "4G" veya "3G"
    val key: String,          // "4G|earfcn|pci" veya "3G|uarfcn|psc"
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

/** Tek bir hiz olcumu. early = ilk 2 sn, sustained = son 3 sn ortalamasi. */
data class SpeedSample(
    val ts: Long,
    val avgMbps: Double,
    val earlyMbps: Double,
    val sustainedMbps: Double,
    val pingMs: Int
)

data class CellStats(
    val n: Int,
    val avg: Double,
    val sustained: Double,
    val worst: Double,
    val sinking: Boolean,
    val score: Double
)

fun isSinkSample(s: SpeedSample): Boolean = s.earlyMbps > 0.5 && s.sustainedMbps < s.earlyMbps * 0.5

/** Hucre basina olcum gecmisi (telefonda SharedPreferences icinde saklanir). */
class SpeedStore(context: Context) {
    private val prefs = context.getSharedPreferences("speed_history", Context.MODE_PRIVATE)
    private val cache = HashMap<String, MutableList<SpeedSample>>()

    init {
        val raw = prefs.getString("data", null)
        if (raw != null) {
            try {
                val root = JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = root.getJSONArray(k)
                    val list = mutableListOf<SpeedSample>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            SpeedSample(
                                o.getLong("ts"),
                                o.getDouble("avg"),
                                o.getDouble("early"),
                                o.getDouble("sus"),
                                o.getInt("ping")
                            )
                        )
                    }
                    cache[k] = list
                }
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun add(key: String, s: SpeedSample) {
        val list = cache.getOrPut(key) { mutableListOf() }
        list.add(s)
        while (list.size > 30) list.removeAt(0)
        save()
    }

    @Synchronized
    fun samples(key: String): List<SpeedSample> = (cache[key] ?: emptyList<SpeedSample>()).toList()

    @Synchronized
    fun allSamples(rat: String): List<SpeedSample> {
        val out = mutableListOf<SpeedSample>()
        for ((k, v) in cache) {
            if (k.startsWith("$rat|")) out.addAll(v)
        }
        return out
    }

    @Synchronized
    fun clear() {
        cache.clear()
        save()
    }

    private fun save() {
        val root = JSONObject()
        for ((k, list) in cache) {
            val arr = JSONArray()
            for (s in list) {
                val o = JSONObject()
                o.put("ts", s.ts)
                o.put("avg", s.avgMbps)
                o.put("early", s.earlyMbps)
                o.put("sus", s.sustainedMbps)
                o.put("ping", s.pingMs)
                arr.put(o)
            }
            root.put(k, arr)
        }
        prefs.edit().putString("data", root.toString()).apply()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var learnSwitch: Switch
    private lateinit var store: SpeedStore

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var isAutoMonitoring = false
    private var isLearning = false
    private var lastTowers: List<TowerInfo> = emptyList()
    private var lastHandoverAt = 0L

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isAutoMonitoring) {
                scanTowers(autoTrigger = true)
                handler.postDelayed(this, 10_000L)
            }
        }
    }

    private val learnRunnable = object : Runnable {
        override fun run() {
            if (isLearning) {
                runSpeedTest()
                handler.postDelayed(this, 90_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SpeedStore(applicationContext)

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
            text = "Gerçek hız ölçümüyle öğrenen 3G / 4.5G kule seçici"
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 6, 0, 28)
        }

        val scanBtn = createStyledButton("1. Tara ve Analiz Et", "#0284C7")
        val speedTestBtn = createStyledButton("2. Hız Ölç ve Bu Kuleye Kaydet", "#0D9488")
        val compareBtn = createStyledButton("3. Root: 3G / 4.5G Otomatik Karşılaştır", "#7C3AED")
        val modeBtn = createStyledButton("4. Root: Ağ Modu Seç (4.5G / 3G / Oto)", "#334155")
        val radioInfoBtn = createStyledButton("5. Gizli Radyo Menüsü (*#*#4636#*#*)", "#1E293B")
        val mtkBandBtn = createStyledButton("6. MediaTek Bant Kilidi (EngineerMode)", "#1E293B")
        val clearBtn = createStyledButton("Ölçüm Geçmişini Temizle", "#7F1D1D")

        autoSwitch = Switch(this).apply {
            text = "Canlı Takip (10 sn'de bir hücre tara)"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(8, 20, 8, 10)
        }

        learnSwitch = Switch(this).apply {
            text = "Otomatik Ölçüm (90 sn'de bir, ~1-8 MB veri harcar)"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(8, 10, 8, 20)
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
            text = "Hazır. GPS açık olsun. Önce '1. Tara', sonra bir süre 'Otomatik Ölçüm'ü açık bırakın; uygulama hangi kule/bandın gerçekten hızlı olduğunu öğrenir."
            textSize = 13.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 28, 0, 0)
            setLineSpacing(6f, 1.12f)
        }

        layout.addView(titleText)
        layout.addView(subTitleText)
        layout.addView(scanBtn)
        layout.addView(speedTestBtn)
        layout.addView(compareBtn)
        layout.addView(modeBtn)
        layout.addView(radioInfoBtn)
        layout.addView(mtkBandBtn)
        layout.addView(autoSwitch)
        layout.addView(learnSwitch)
        layout.addView(speedText)
        layout.addView(statusText)
        layout.addView(clearBtn)

        scrollView.addView(layout)
        setContentView(scrollView)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        scanBtn.setOnClickListener { checkPermissionsAndScan(autoTrigger = false) }
        speedTestBtn.setOnClickListener { runSpeedTest() }
        compareBtn.setOnClickListener { runModeComparison() }
        modeBtn.setOnClickListener { showModeDialog() }
        radioInfoBtn.setOnClickListener { openHiddenRadioInfo() }
        mtkBandBtn.setOnClickListener { openMediaTekBandMode() }
        clearBtn.setOnClickListener {
            store.clear()
            speedText.text = "Hız Testi: Geçmiş temizlendi"
            if (lastTowers.isNotEmpty()) statusText.text = buildReport(lastTowers).first
        }

        autoSwitch.setOnCheckedChangeListener { _, isChecked ->
            isAutoMonitoring = isChecked
            handler.removeCallbacks(monitorRunnable)
            if (isChecked) {
                checkPermissionsAndScan(autoTrigger = true)
                handler.postDelayed(monitorRunnable, 10_000L)
            }
        }

        learnSwitch.setOnCheckedChangeListener { _, isChecked ->
            isLearning = isChecked
            handler.removeCallbacks(learnRunnable)
            if (isChecked) {
                if (!hasPermissions()) {
                    checkPermissionsAndScan(autoTrigger = false)
                }
                handler.postDelayed(learnRunnable, 2_000L)
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

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissionsAndScan(autoTrigger: Boolean) {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, 101)
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
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            scanTowers(autoTrigger = false)
        } else {
            statusText.text = "HATA: Baz istasyonu verilerini okumak için Tam Konum ve Telefon izinleri zorunludur."
        }
    }

    // ---------------------------------------------------------------- TARAMA

    @SuppressLint("MissingPermission")
    private fun scanTowers(autoTrigger: Boolean) {
        if (!autoTrigger) {
            statusText.text = "Çevredeki 3G / 4.5G hücreler taranıyor..."
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

    private fun decodeUmtsBand(uarfcn: Int): BandSpec {
        return when (uarfcn) {
            in 10562..10838 -> BandSpec("U2100 (3G B1)", capacityBonus = 12, isHighSpeedBand = true)
            in 2937..3088 -> BandSpec("U900 (3G B8)", capacityBonus = 6, isHighSpeedBand = false)
            else -> BandSpec("UARFCN $uarfcn", capacityBonus = 5, isHighSpeedBand = false)
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

                val weakSignalPenalty = if (rsrp < -116) -35 else if (rsrp < -110) -15 else 0
                val rawScore = (rsrp + 140) + (rsrq * 2) + (sinr * 2) + band.capacityBonus + weakSignalPenalty

                towers.add(
                    TowerInfo(
                        rat = "4G",
                        key = "4G|$earfcn|$pci",
                        pci = pci,
                        earfcn = earfcn,
                        enbId = enbId,
                        sectorId = sectorId,
                        band = band,
                        rsrp = rsrp,
                        rsrq = rsrq,
                        sinr = sinr,
                        cqi = cqi,
                        speedScore = rawScore.coerceIn(0, 100),
                        isRegistered = cell.isRegistered
                    )
                )
            } else if (cell is CellInfoWcdma) {
                val id = cell.cellIdentity
                val sig = cell.cellSignalStrength

                val psc = if (id.psc != CellInfo.UNAVAILABLE) id.psc else -1
                val uarfcn = if (id.uarfcn != CellInfo.UNAVAILABLE) id.uarfcn else -1
                val dbm = if (sig.dbm != CellInfo.UNAVAILABLE) sig.dbm else -120
                val band = decodeUmtsBand(uarfcn)

                val weakPenalty = if (dbm < -105) -20 else 0
                val rawScore = (dbm + 120) + band.capacityBonus + weakPenalty

                towers.add(
                    TowerInfo(
                        rat = "3G",
                        key = "3G|$uarfcn|$psc",
                        pci = psc,
                        earfcn = uarfcn,
                        enbId = -1,
                        sectorId = -1,
                        band = band,
                        rsrp = dbm,
                        rsrq = 0,
                        sinr = 0,
                        cqi = -1,
                        speedScore = rawScore.coerceIn(0, 70),
                        isRegistered = cell.isRegistered
                    )
                )
            }
        }

        if (towers.isEmpty()) {
            statusText.text = "Hücre verisi alınamadı.\n• Kontrol 1: Üst panelden Konum (GPS) açık olmalı.\n• Kontrol 2: Mobil veri / SIM aktif olmalı."
            return
        }

        lastTowers = towers
        val (report, target) = buildReport(towers)
        statusText.text = report

        if (target != null) {
            triggerTowerHandover(target, autoTrigger)
        }
    }

    // ---------------------------------------------------------------- ANALIZ

    private fun fmt(d: Double): String = String.format(Locale.US, "%.2f", d)

    /** Son 8 olcumu, yeniye daha cok agirlik vererek ozetler. */
    private fun statsOf(list: List<SpeedSample>): CellStats? {
        if (list.isEmpty()) return null
        val recent = list.takeLast(8)
        var wSum = 0.0
        var avgSum = 0.0
        var susSum = 0.0
        var w = 1.0
        for (s in recent.asReversed()) {
            avgSum += s.avgMbps * w
            susSum += s.sustainedMbps * w
            wSum += w
            w *= 0.85
        }
        val avg = avgSum / wSum
        val sus = susSum / wSum
        val worst = recent.minOf { it.avgMbps }
        val sinking = recent.count { isSinkSample(it) } * 2 >= recent.size
        // "Batan" kuleyi cezalandir: surekli hiz en onemli, sonra ortalama, sonra en kotu an.
        val base = 0.6 * sus + 0.25 * avg + 0.15 * worst
        return CellStats(recent.size, avg, sus, worst, sinking, if (sinking) base * 0.7 else base)
    }

    private fun ratLabel(rat: String): String = if (rat == "4G") "4.5G (LTE)" else "3G (UMTS)"

    private fun towerLine(t: TowerInfo): String {
        return if (t.rat == "4G") {
            val cqiPart = if (t.cqi >= 0) " | CQI:${t.cqi}" else ""
            "4.5G ${t.band.name} | PCI:${t.pci} | RSRP:${t.rsrp} | RSRQ:${t.rsrq} | SINR:${t.sinr}$cqiPart | Sinyal skoru:${t.speedScore}"
        } else {
            "3G ${t.band.name} | PSC:${t.pci} | RSCP:${t.rsrp} dBm | Sinyal skoru:${t.speedScore}"
        }
    }

    private fun techSummary(): String {
        val sb = StringBuilder()
        sb.append("--- 3G / 4.5G GERÇEK ÖLÇÜM KARŞILAŞTIRMASI ---\n")
        val scores = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        for (rat in listOf("4G", "3G")) {
            val list = store.allSamples(rat)
            if (list.isEmpty()) {
                sb.append("• ${ratLabel(rat)}: henüz ölçüm yok\n")
            } else {
                val avg = list.map { it.avgMbps }.average()
                val sus = list.map { it.sustainedMbps }.average()
                val sink = list.count { isSinkSample(it) }
                scores[rat] = 0.6 * sus + 0.4 * avg
                counts[rat] = list.size
                sb.append("• ${ratLabel(rat)}: ort ${fmt(avg)} Mbps | sürekli ${fmt(sus)} Mbps | ${list.size} ölçüm | batma: $sink/${list.size}\n")
            }
        }
        val s4 = scores["4G"]
        val s3 = scores["3G"]
        if (s4 != null && s3 != null && (counts["4G"] ?: 0) >= 2 && (counts["3G"] ?: 0) >= 2) {
            if (s3 > s4 * 1.15) {
                sb.append("➜ SONUÇ: Bu konumda 3G daha hızlı/stabil. '4. Ağ Modu' ile 3G'ye sabitlemeyi deneyin.\n")
            } else if (s4 > s3 * 1.15) {
                sb.append("➜ SONUÇ: 4.5G daha hızlı/stabil. LTE-only moduna sabitleyin.\n")
            } else {
                sb.append("➜ SONUÇ: İkisi birbirine yakın. Otomatik modda kalabilirsiniz.\n")
            }
        } else {
            sb.append("➜ Karar için her iki ağdan da en az 2 ölçüm gerekli. '3. Karşılaştır' (root) veya modu elle değiştirip '2. Hız Ölç' kullanın.\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    /** Raporu ve (varsa) gecis hedefini dondurur. */
    private fun buildReport(towers: List<TowerInfo>): Pair<String, TowerInfo?> {
        val sb = StringBuilder()
        val sorted = towers.sortedByDescending { it.speedScore }
        val current = towers.find { it.isRegistered }
        val curStats = if (current != null) statsOf(store.samples(current.key)) else null

        val measured = towers.mapNotNull { t ->
            val st = statsOf(store.samples(t.key))
            if (st != null) Pair(t, st) else null
        }
        val bestMeasured = measured.maxByOrNull { it.second.score }
        val bestUnmeasured = towers
            .filter { !it.isRegistered && store.samples(it.key).isEmpty() }
            .maxByOrNull { it.speedScore }

        var target: TowerInfo? = null

        if (current != null) {
            sb.append("ŞU AN BAĞLI:\n• ${towerLine(current)}\n")
            if (curStats != null) {
                val warn = if (curStats.sinking) "  ⚠ BATIYOR (ilk saniyeler hızlı, sonra düşüyor)" else ""
                sb.append("• Gerçek ölçüm (${curStats.n}): ort ${fmt(curStats.avg)} | sürekli ${fmt(curStats.sustained)} | en kötü ${fmt(curStats.worst)} Mbps$warn\n")
            } else {
                sb.append("• Bu hücre için henüz hız ölçümü yok ('2. Hız Ölç' ya da Otomatik Ölçüm).\n")
            }

            val curScore = curStats?.score
            if (bestMeasured != null && bestMeasured.first.key != current.key &&
                (curScore == null || bestMeasured.second.score > curScore * 1.3 + 0.2)
            ) {
                target = bestMeasured.first
                sb.append("\nÖNERİ: Ölçümlere göre ${towerLine(bestMeasured.first)} daha hızlı (skor ${fmt(bestMeasured.second.score)} Mbps).\n")
            } else if ((curStats == null || curStats.score < 3.0 || curStats.sinking) &&
                bestUnmeasured != null && bestUnmeasured.speedScore - current.speedScore >= 6
            ) {
                target = bestUnmeasured
                sb.append("\nÖNERİ: Henüz denenmemiş ama sinyali daha iyi aday: ${towerLine(bestUnmeasured)}\n")
            } else if (curStats != null) {
                sb.append("\nÖNERİ: Şu anki hücre ölçülenler arasında en iyisi. Değiştirmeye gerek yok.\n")
            } else {
                sb.append("\nÖNERİ: Önce birkaç ölçüm toplayın.\n")
            }
            sb.append("\n")
        } else {
            sb.append("Bağlı hücre okunamadı (cihaz hücre bilgisini vermedi).\n\n")
        }

        sb.append(techSummary())

        sb.append("--- Çevredeki Hücreler (${sorted.size}) ---\n")
        sorted.forEachIndexed { idx, t ->
            val tag = if (t.isRegistered) " [BAĞLI]" else ""
            val st = statsOf(store.samples(t.key))
            val meas = if (st != null) " | ÖLÇÜM: ${fmt(st.score)} Mbps (${st.n})${if (st.sinking) " ⚠batıyor" else ""}" else " | ölçüm yok"
            sb.append("${idx + 1}. ${towerLine(t)}$meas$tag\n")
        }
        return Pair(sb.toString(), target)
    }

    // ---------------------------------------------------------------- HIZ OLCUMU

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registeredCell(): Pair<String, String>? {
        val cells: List<CellInfo> = try {
            telephonyManager.allCellInfo ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        for (c in cells) {
            if (!c.isRegistered) continue
            if (c is CellInfoLte) {
                val id = c.cellIdentity
                val band = decodeLteBand(id.earfcn)
                return Pair("4G|${id.earfcn}|${id.pci}", "4.5G ${band.name} PCI:${id.pci}")
            }
            if (c is CellInfoWcdma) {
                val id = c.cellIdentity
                val band = decodeUmtsBand(id.uarfcn)
                return Pair("3G|${id.uarfcn}|${id.psc}", "3G ${band.name} PSC:${id.psc}")
            }
        }
        return null
    }

    /** Hucresel agda 8 sn'lik indirme testi. Ilk 2 sn ve son 3 sn ayri raporlanir (batma tespiti). */
    private fun downloadTest(network: Network): SpeedSample? {
        var pingMs = -1
        try {
            val t0 = System.nanoTime()
            val pc = network.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection
            pc.connectTimeout = 5000
            pc.readTimeout = 5000
            pc.responseCode
            pingMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
            pc.disconnect()
        } catch (_: Exception) {
        }

        val slices = mutableListOf<Double>()
        var total = 0L
        val startNs = System.nanoTime()
        try {
            val conn = network.openConnection(URL("https://speed.cloudflare.com/__down?bytes=8000000")) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 6000
            val input = conn.inputStream
            val buffer = ByteArray(16384)
            var sliceBytes = 0L
            var sliceStart = startNs
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                sliceBytes += read
                val now = System.nanoTime()
                if (now - sliceStart >= 1_000_000_000L) {
                    slices.add(sliceBytes * 8.0 / ((now - sliceStart) / 1e9) / 1e6)
                    sliceBytes = 0L
                    sliceStart = now
                }
                if (now - startNs >= 8_000_000_000L) break
            }
            input.close()
            conn.disconnect()
        } catch (_: Exception) {
        }

        val elapsed = (System.nanoTime() - startNs) / 1e9
        if (total <= 0L || elapsed <= 0.0) return null
        val avg = total * 8.0 / elapsed / 1e6
        val early = if (slices.size >= 2) slices.take(2).average() else avg
        val sustained = if (slices.size >= 4) slices.takeLast(3).average() else avg
        return SpeedSample(System.currentTimeMillis(), avg, early, sustained, pingMs)
    }

    private fun measureBlocking(): SpeedSample? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Network?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ref.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        return try {
            connectivityManager.requestNetwork(request, cb, 8000)
            latch.await(10, TimeUnit.SECONDS)
            val net = ref.get()
            if (net == null) null else downloadTest(net)
        } catch (e: Exception) {
            null
        } finally {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
    }

    private fun sampleText(s: SpeedSample): String {
        val sink = if (isSinkSample(s)) " ⚠ BATTI" else ""
        return "${fmt(s.avgMbps)} Mbps (ilk 2sn: ${fmt(s.earlyMbps)} → son 3sn: ${fmt(s.sustainedMbps)}) | Ping: ${s.pingMs} ms$sink"
    }

    private fun runSpeedTest() {
        if (!busy.compareAndSet(false, true)) {
            speedText.text = "Hız Testi: Başka bir ölçüm sürüyor, bekleyin..."
            return
        }
        speedText.text = "Hız Testi: Ölçülüyor (yaklaşık 8-10 sn)..."
        executor.execute {
            val before = registeredCell()
            val sample = measureBlocking()
            val after = registeredCell()
            busy.set(false)
            handler.post {
                if (sample == null) {
                    speedText.text = "Hız Testi Hatası: Mobil veri açık mı? (Uçak modu / sinyal yok olabilir)"
                } else {
                    val same = before != null && (after == null || after.first == before.first)
                    if (same && before != null) {
                        store.add(before.first, sample)
                        speedText.text = "${sampleText(sample)}\nKaydedildi → ${before.second}"
                    } else {
                        speedText.text = "${sampleText(sample)}\nHücre değiştiği/okunamadığı için KAYDEDİLMEDİ."
                    }
                    if (lastTowers.isNotEmpty()) {
                        statusText.text = buildReport(lastTowers).first
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- ROOT ISLEMLERI

    private fun runSu(vararg cmds: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            for (c in cmds) {
                os.writeBytes(c + "\n")
            }
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun setModeCommands(mode: Int): Array<String> = arrayOf(
        "settings put global preferred_network_mode $mode",
        "settings put global preferred_network_mode1 $mode",
        "cmd connectivity airplane-mode enable",
        "sleep 1",
        "cmd connectivity airplane-mode disable"
    )

    private fun showModeDialog() {
        val items = arrayOf("Sadece 4.5G (LTE only)", "Sadece 3G (WCDMA only)", "Otomatik (4G/3G/2G)")
        AlertDialog.Builder(this)
            .setTitle("Ağ Modu (Root gerekir)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setNetworkModeRoot(11, "Sadece 4.5G")
                    1 -> setNetworkModeRoot(2, "Sadece 3G")
                    else -> setNetworkModeRoot(9, "Otomatik")
                }
            }
            .show()
    }

    private fun setNetworkModeRoot(mode: Int, label: String) {
        executor.execute {
            val ok = runSu(*setModeCommands(mode))
            handler.post {
                if (ok) {
                    statusText.append("\n[ROOT] Ağ modu: $label. Hücreye yeniden kayıt olması ~15 sn sürer.\n")
                } else {
                    statusText.append("\n[BİLGİ] Root yok/reddedildi. '5. Gizli Radyo Menüsü'nden 'Set preferred network type' ile $label seçin.\n")
                    openHiddenRadioInfo()
                }
            }
        }
    }

    /** 4.5G-only ve 3G-only modlarinda ikiser olcum alip karsilastirir, sonda Otomatik'e doner. */
    private fun runModeComparison() {
        if (!busy.compareAndSet(false, true)) {
            statusText.text = "Başka bir ölçüm sürüyor, bekleyin..."
            return
        }
        statusText.text = "Karşılaştırma testi başlıyor (yaklaşık 1-1.5 dk, bağlantı kısa süre kopacak)...\n"
        executor.execute {
            try {
                if (!runSu("id")) {
                    handler.post {
                        statusText.append("[BİLGİ] Root yok. Elle yapın: '5. Gizli Radyo Menüsü'nden ağ türünü değiştirip her seferinde '2. Hız Ölç'e basın.\n")
                    }
                } else {
                    val plan = listOf(Pair(11, "4.5G (LTE only)"), Pair(2, "3G (WCDMA only)"))
                    for ((mode, label) in plan) {
                        handler.post { statusText.append("→ $label moduna geçiliyor...\n") }
                        runSu(*setModeCommands(mode))
                        Thread.sleep(15_000L)
                        for (round in 1..2) {
                            val before = registeredCell()
                            val s = measureBlocking()
                            if (s != null && before != null) {
                                store.add(before.first, s)
                                handler.post { statusText.append("  #$round [${before.second}] ${sampleText(s)}\n") }
                            } else {
                                handler.post { statusText.append("  #$round ölçüm alınamadı (bu modda kayıt olunamadı olabilir)\n") }
                            }
                            Thread.sleep(2_000L)
                        }
                    }
                    runSu(*setModeCommands(9))
                    handler.post { statusText.append("→ Otomatik moda dönüldü.\n") }
                }
            } catch (e: Exception) {
                handler.post { statusText.append("[HATA] ${e.localizedMessage}\n") }
            } finally {
                busy.set(false)
            }
            handler.post {
                if (lastTowers.isNotEmpty()) {
                    statusText.append("\n" + buildReport(lastTowers).first)
                } else {
                    statusText.append("\n" + techSummary())
                }
            }
        }
    }

    private fun triggerTowerHandover(best: TowerInfo, autoTrigger: Boolean) {
        val now = System.currentTimeMillis()
        if (autoTrigger && now - lastHandoverAt < 180_000L) return
        lastHandoverAt = now
        executor.execute {
            val rootSuccess = runSu(
                "svc data disable",
                "cmd connectivity airplane-mode enable",
                "sleep 1",
                "cmd connectivity airplane-mode disable",
                "svc data enable"
            )
            handler.post {
                if (rootSuccess) {
                    statusText.append("\n[ROOT] Radyo yenilendi; ${best.band.name} (${best.pci}) hücresine yeniden kayıt denendi. Hangi hücreye bağlanacağına operatör ağı karar verir.\n")
                } else if (!autoTrigger) {
                    statusText.append("\n[ROOTSUZ] Zorunlu kilit yapılamaz. Radyo menüsü açılıyor — 'Radio power' anahtarını kapatıp açın.\n")
                    openHiddenRadioInfo()
                } else {
                    statusText.append("\n[ROOTSUZ] Daha iyi hücre var. '5. Gizli Radyo Menüsü' ile radyoyu yenileyin.\n")
                }
            }
        }
    }

    // ---------------------------------------------------------------- KISAYOLLAR

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
        statusText.append("\n[BİLGİ] MIUI EngineerMode'u engelledi. Telefon uygulamasından *#*#3646633#*#* tuşlayarak BandMode'a girebilirsiniz.\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoMonitoring = false
        isLearning = false
        handler.removeCallbacks(monitorRunnable)
        handler.removeCallbacks(learnRunnable)
        executor.shutdownNow()
    }
}
