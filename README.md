# autoBazConnector (Redmi 12C / MediaTek Helio G85 Sürümü)

Xiaomi **Redmi 12C** (MediaTek Helio G85 / MIUI & HyperOS) için özel olarak optimize edilmiş baz istasyonu analiz ve hız odaklı hücre geçiş uygulaması.

## Öne Çıkan Özellikler
- **EARFCN -> LTE Bant Çözümleme:** Çevredeki hücrelerin hangi frekansta yayın yaptığını otomatik çözer (`B7 2600 MHz`, `B3 1800 MHz`, `B1 2100 MHz` gibi yüksek kapasiteli bantlara öncelik verir; yoğun ve yavaş `B20 800 MHz` bantlarını ayırt eder).
- **Gerçek Hız Skorlaması (RSRP + RSRQ + SINR + CQI):** Sadece sinyal gücüne (dBm) değil, baz istasyonunun doluluk oranını gösteren **RSRQ** ve sinyal temizliğini gösteren **SINR** değerlerine göre en yüksek indirme hızı verecek kuleyi seçer.
- **Aynı Kulede Farklı Frekans Tespiti (eNB ID):** Bağlı olduğunuz fiziksel baz istasyonunun daha hızlı bir frekans bandı (ör. B20 yerine B3/B7) varsa bunu tespit eder.
- **Redmi 12C / MediaTek Hızlı Geçiş Araçları:**
  - Rootsuz cihazlarda Wi-Fi'yi koparmadan geçiş tetiklemek için tek dokunuşla **Gizli Radyo Menüsü (`*#*#4636#*#*`)** ve **MediaTek EngineerMode BandMode** kısayolları.
  - Root'lu cihazlarda otomatik radyo yenileme ve tek tuşla **Sadece 4.5G (LTE Only)** kilidi.
- **Dahili Hücresel Hız & Ping Testi:** Wi-Fi açık olsa bile doğrudan mobil veri arayüzüne bağlanarak gerçek indirme hızını (Mbps) ve gecikmeyi (ms) ölçer.

## GitHub Actions ile Otomatik APK İndirme
Depoya kod gönderildiğinde **Actions -> Build autoBazConnector APK (Redmi 12C)** iş akışı otomatik çalışır ve alt kısımdaki **Artifacts** bölümünden `autoBazConnector-Redmi12C` APK paketini indirmenizi sağlar.
