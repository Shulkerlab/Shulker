# .shulk dünya formatı (v0.3)

Durum: **onaylandı (2026-09-27), uygulanıyor.** Kararlar 6. bölümde.

## 1. Ölçümler

Deney: seed `shulker`, 64×64 chunk üretilmiş overworld (16 region dosyası, 8100 chunk, 255 MiB ham
NBT). Sözlük bir grup region'dan eğitildi, başka region'larda test edildi (sızıntı yok).

**Disk boyutu (tüm set):**

| Yöntem | Boyut | `.mca`'ya göre |
|---|---|---|
| `.mca` (bugünkü hali, diskte) | 57.6 MiB | 1.00× |
| zlib, chunk başına, sektör dolgusu yok | 37.3 MiB | 1.55× |
| zstd-3, chunk başına | 40.5 MiB | 1.42× |
| zstd-3 + sözlük | 36.7 MiB | 1.57× |
| zstd-3, bütün region tek parça (Linear tarzı) | 36.1 MiB | 1.60× |
| zstd-19, chunk başına | 32.8 MiB | 1.76× |

**Hız (test seti: 54 MiB ham veri, zstd-jni, tek thread):**

| Codec | Oran | Sıkıştırma | Açma |
|---|---|---|---|
| zlib-6 (bugünkü `.mca`) | 7.3× | 58 MB/s | 473 MB/s |
| zstd-3 + sözlük | 7.7× | 396 MB/s | 1013 MB/s |
| **zstd-6 + sözlük** | **8.8×** | **133 MB/s** | **1116 MB/s** |
| zstd-19 + sözlük | 10.0× | 5 MB/s | 999 MB/s |

**Çıkarımlar:**
1. `.mca`'nın kaybının en büyük kısmı sıkıştırma değil, **4 KiB sektör dolgusu**: aynı zlib'le bile %35
   küçülüyor.
2. Düşük seviye zstd, küçük chunk'larda zlib'den **daha kötü**. Sözlük olmadan zstd'nin avantajı az.
3. zstd-6 + sözlük: zlib'den %21 küçük, 2.3× hızlı sıkıştırma, 2.4× hızlı açma.
4. Bütün region'ı tek parça sıkıştırmak (Linear), chunk başına + sözlükten iyi değil ve rastgele erişimi
   kaybettiriyor. Bu yüzden chunk başına sıkıştırma.

Tahmini sonuç: `.mca`'ya göre yaklaşık **%46 daha küçük** (zstd-6 + sözlük, dolgusuz). Soğuk chunk'lar
zstd-19'a yeniden sıkıştırılırsa yaklaşık %53.

**Uygulamadan sonra ölçülen (aynı seed ve alan):**

| | `.mca` | `.shulk` |
|---|---|---|
| Sunucunun kendisi yazdığı overworld region'ları (soğuk sıkıştırma kapalı) | 57.6 MiB | 34.4 MiB (−%40) |
| Dönüştürücüyle çevrilen tüm dünya (region + entity + POI, tek seferde yazıldığı için boşluksuz) | 64.7 MiB | 33.0 MiB (−%49) |

Sunucunun yazdığı dosyalarda fark, chunk'ların üretim sırasında birkaç kez yeniden yazılmasından kalan
boşluklardan (compaction eşiğinin altında, ~%3.5) ve üretim sırasındaki tick'lerin chunk içeriğini biraz
değiştirmesinden geliyor. 9754 chunk'ı dönüştürmek ~4 saniye sürüyor.

## 2. Dosya yapısı: `r.X.Z.shulk`

Bir dosya = 32×32 chunk (`.mca` ile aynı bölge).

```
[ başlık: 1024 × 24 bayt girdi ][ veri alanı: sıkıştırılmış chunk'lar, boşluklar yeniden kullanılır ]
```

- **Başlık** (tek kopya, dosyanın başında): sihirli sayı, format sürümü ve 1024 girdilik tablo. Her girdi:
  veri ofseti (u32), sıkıştırılmış uzunluk (u32), ham uzunluk (u32), zaman damgası (u32), sözlük kimliği
  (u16), codec (u8), seviye (u8) ve **girdinin kendi CRC32C'si** (u32): 24 bayt.
- **Yazma sırası** (çökmeye dayanıklı): chunk verisi her zaman boş bir alana yazılır, eski veri yerinde
  durur → (sync açıksa fsync) → en son 24 baytlık girdi güncellenir. Yazma yarıda kalırsa ya eski girdi
  eski veriyi gösterir ya da girdinin CRC'si tutmaz ve yalnızca o chunk kaybolur. Bu, `.mca`'nın
  güvenlik seviyesiyle aynı (o da başlığını yerinde günceller). İlk taslaktaki çift başlık, her dosyaya
  ~24 KiB ek yük getirdiği için bırakıldı.
- **Boş alan:** bellekte tutulan boşluk listesi, en iyi uyan boşluğa yazılır. Boşa giden alan %25'i
  geçerse dosya kapanırken sıkıştırılır (compaction).
- **Büyük chunk'lar:** uzunluk u32 olduğu için Paper'ın ayrı `.mcc` dosyalarına gerek yok, hepsi
  dosyanın içinde.

## 3. Sıkıştırma

- **zstd-jni** (Leaf/Luminol'ün de kullandığı, tüm platformların native'lerini içeren kütüphane).
  Sistem `libzstd`'sine FFM ile bağlanmak daha "modern" olurdu ama sunucularda kurulu olmayabiliyor.
- **Varsayılan:** zstd-6 + yerleşik sözlük.
- **Soğuk chunk'lar:** 24 saattir kaydedilmemiş chunk'lar, MSPT 30 ms'nin altındayken tek bir düşük
  öncelikli thread'de, saniyede en fazla 20 chunk olacak şekilde zstd-19'a yeniden sıkıştırılır.
  Yalnızca sunucunun o anda açık tuttuğu region'lar işlenir (dosyayı ikinci kez açmamak için). Oyuncular
  dolaştıkça zamanla tüm dünya kapsanır.
- **Sözlükler:** her Minecraft sürümü için üretilmiş dünyalardan eğitilir ve jar'a gömülür. 26.2 için:
  chunk (id 1, 110 KiB, 9158 gerçek chunk), entity (id 2, 50 KiB) ve POI (id 3, 4 KiB). POI chunk'ları
  çok küçük (gerçek dünyada ortalama 148 bayt) ve gerçek dünyada yeterli örnek olmadığı için POI sözlüğü,
  düz bir dünyada 2304 chunk'a rastgele yerleştirilmiş 10 897 POI bloğundan (iş blokları, yataklar, çan,
  arı kovanları, lodestone, paratoner) eğitildi. Gerçek köy POI'lerinde sözlüksüz zstd 14 020 → 12 227
  bayt, sözlükle 4 155 bayt (%66 daha küçük).
  Her chunk kendi sözlük kimliğini taşıdığı için eski sözlükler hep jar'da kalır. Eski dosyalar her zaman
  okunabilir, farklı sürümlerle yazılmış chunk'lar aynı dosyada karışık durabilir.

## 4. Paper'a bağlanma (eklenti uyumluluğu)

- `ShulkRegionFile extends RegionFile`. `RegionFileStorage` her yerde `RegionFile` döndürmeye devam eder,
  **public imzalar değişmez** (Luminol/Leaf'in framework'ü dönüş tiplerini değiştiriyor, o yüzden onu
  almıyoruz).
- Gereken yamalar yalnızca eklemeli: `RegionFile`'a korumalı bir constructor ve bir metottan `final`'ın
  kaldırılması.
- Chunk, entity ve POI depoları aynı sınıftan geçtiği için üçü de `.shulk` olur.
- **Dosya başına tek nesne:** Sunucu aynı region dosyasını birden fazla yerden açabiliyor (chunk deposu,
  başlangıçtaki dosya düzelticiler, upgrader). Her `.shulk` dosyası için paylaşılan tek bir `ShulkRegion`
  tutuluyor (referans sayımlı). İki ayrı nesne, iki ayrı boş alan listesiyle birbirinin verisinin
  üzerine yazardı.

## 5. Geçiş ve dış araçlar

- **Kademeli geçiş:** Bir chunk `.shulk`'ta yoksa `.mca`'dan okunur ve bir sonraki kayıtta `.shulk`'a
  yazılır. Eski dünya ilk açılışta dönüştürülmeyi beklemeden çalışır, zamanla kendiliğinden geçer.
- **Tam dönüştürme:** `java -jar shulker.jar --convert-world=<dünya> --to=shulk|mca` (sunucu kapalıyken;
  açıksa `session.lock` yüzünden reddeder). Her region yazılır, geri okunup bayt bayt karşılaştırılır,
  ancak ondan sonra kaynak dosya silinir.
- **Bütünlük kontrolü:** `java -jar shulker.jar --verify-world=<dünya>` her `.shulk` chunk'ını açıp
  zstd sağlama toplamını doğrular.
- **Dış araçlar:** BlueMap, MCA Selector, Chunky'nin harita araçları gibi region dosyalarını doğrudan
  okuyan araçlar `.shulk`'u okuyamaz. `--to=mca` ile geri dönüştürme her zaman mümkün olacak.
  Eklentiler Bukkit/Paper API'si üzerinden çalıştığı için etkilenmez.
- **Bilinen sınırlama:** Vanilla'nın `--forceUpgrade` / "Optimize World" özelliği yalnızca `.mca`
  dosyalarını tarar. `.shulk` dünyalarda chunk'lar, vanilla'da da olduğu gibi yüklendikçe yeni sürüme
  yükseltilir.

## 7. Test

- `shulker-format` birim testleri: gidiş-dönüş, yeniden açma, boşluk yeniden kullanımı, compaction,
  bozuk girdi/veri, yarım kalmış yazma, eşzamanlı yazma + okuma + yeniden sıkıştırma stres testi,
  dönüştürücü.
- `scripts/world-format-test.sh`: gerçek sunucuyla `.mca` dünya → yerinde geçiş (soğuk sıkıştırma
  zorla açık) → yeniden başlatma → `.mca`'ya ve geri dönüştürme → yeni `.shulk` dünya. Her adım
  işaret bloklarını ve `--verify-world` ile tüm chunk'ları kontrol eder.
- Bu test, ilk sürümde boş alan yönetimindeki bir hatayı (TreeMap girdisi silinince ardılın
  değerlerini taşıyordu) yakaladı: yoğun yazmada canlı chunk'ların üzerine yazılıyordu.

## 6. Kararlar (onaylandı)

1. **Varsayılan format:** Yeni dünyalar `.shulk` mu başlasın, yoksa `.mca` varsayılan kalıp `.shulk`
   `shulker.yml`'den mi açılsın? → **Yeni dünyalar `.shulk`, mevcut dünyalar kullanıcı açana kadar `.mca`.**
2. **Kademeli geçiş varsayılan mı?** Mevcut bir `.mca` dünyada `.shulk` açılınca eski dosyalar
   yerinde kalıp chunk'lar kaydedildikçe mi taşınsın? → **Evet, kademeli; boşalan `.mca`'lar silinir.**
3. **Soğuk chunk'ların arka planda zstd-19'a sıkıştırılması** varsayılan açık mı olsun?
   → **Varsayılan açık, sadece MSPT düşükken.**
