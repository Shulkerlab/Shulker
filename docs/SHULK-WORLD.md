# Shulk Worlds: tek dosyalık dünyalar (v1)

Durum: **uygulandı.** Format `shulker-format` (`ShulkWorld`, `ShulkWorldFolders`), sunucu tarafı
`dev.shulker.server.world`, API `dev.shulker.api.world`.

`.shulk` formatının iki düzeni var. **Region** düzeni (`r.X.Z.shulk`,
[SHULK-FORMAT.md](SHULK-FORMAT.md)) büyük, sınırsız dünyalar için `.mca`'nın yerine geçer. **World**
düzeni ise ada, arena ve lobi gibi küçük dünyaları tek bir `<isim>.shulk` dosyasında tutar. Bu belge
world düzenini ve onu kullanan Shulk World sistemini anlatıyor.

## 1. Ne işe yarıyor

| İhtiyaç | Region dünyası (Paper'daki gibi) | Shulk World |
|---|---|---|
| Bütün dünya tek dosya | ❌ klasör + region dosyaları | ✅ `<isim>.shulk` |
| Diske chunk yazmadan çalışma | ❌ | ✅ chunk'lar bellekte |
| Dosya dışı kaynaktan yükleme (DB, S3) | ❌ | ✅ `ShulkWorldLoader` |
| Şablondan yeni dünya | blok blok yapıştırma | ✅ chunk referanslarının kopyası |
| Salt okunur dünya | ❌ | ✅ |
| Sunucular arası taşıma | klasör kopyalama | ✅ tek `byte[]` |

Shulk World sıradan bir Bukkit dünyasıdır: ışınlanma, blok değişikliği, koruma eklentileri ve diğer
eklentiler aynen çalışır. Farkı depolamada: chunk'lar bellekte yaşar ve loader'a ancak dünya
kaydedilince bir bütün olarak gider.

## 2. Dosya düzeni

```
[ "SHULKWD1" u64 ][ sürüm u32 ][ bayraklar u32 ][ chunk sayısı u32 ][ dosya sayısı u32 ][ özellik sayısı u32 ]
[ chunk'lar: tür u8, x i32, z i32, sıkıştırılmış uzunluk u32, ham uzunluk u32, zaman u32,
             sözlük u16, codec u8, seviye u8, veri ]
[ dosyalar:  yol uzunluğu u16, yol (UTF-8), ham uzunluk u32, sıkıştırılmış uzunluk u32, codec u8, veri ]
[ özellikler: anahtar u16 + UTF-8, değer u32 + UTF-8 ]
[ öncesindeki her şeyin CRC32C'si u32 ]
```

- **Ayrım sihirli sayıyla yapılıyor.** Region dosyaları `SHULKRG1`, world dosyaları `SHULKWD1` ile
  başlıyor. Biri diğeri yerine okunursa hata mesajı doğru düzeni söylüyor. Uzantı ikisinde de `.shulk`.
- **Chunk'lar region düzeniyle aynı şekilde sıkıştırılıyor:** chunk başına zstd ve aynı sözlükler
  (chunk, entity, POI). Seviye `shulk-worlds.compression-level` ayarından geliyor, varsayılanı 6.
- **Dosyalar:** dünyanın küçük dosyaları (`data/...`: UUID, PDC, oyun kuralları, generator ayarları,
  bilet listesi). Yolları dimension klasörüne göreceli.
- **Özellikler:** serbest metin metadata. `shulker.region-dir`, içe aktarılan klasörde region
  klasörlerinin nerede olduğunu tutuyor.
- **Bütünlük:** dosyanın sonunda her şeyi kapsayan bir CRC32C var, her chunk'ın zstd çerçevesinin de
  kendi checksum'ı var. Yarım ya da bozuk bir dosya yarım yüklenmiyor, bütünüyle reddediliyor.
- **Çökme güvenliği:** dosya loader'ı önce `<isim>.shulk.tmp` dosyasına yazıyor, `fsync` yapıyor,
  sonra atomik rename ile eskisinin üzerine taşıyor.
- Çıktı deterministik: aynı dünya her seferinde bayt bayt aynı dosyayı üretiyor.

## 3. Bellekte tutma ve klonlama

`ShulkWorld`, chunk'ları diskteki gibi **sıkıştırılmış ve değişmez** `byte[]` olarak tutuyor:

- Bir chunk okunurken açılıyor; yazılırken sıkıştırılıp girdisi atomik olarak değiştiriliyor.
- `copy()` sadece referansları kopyalıyor, hiçbir chunk açılmıyor ya da yeniden sıkıştırılmıyor. Bu
  yüzden şablondan dünya oluşturmanın maliyeti dünyanın boyutundan neredeyse bağımsız.
- Değişmeyen bir chunk kayıtta yeniden sıkıştırılmıyor.
- `modifications()` her değişiklikte artıyor. Hiçbir şey değişmediyse kayıt loader'a gitmiyor.

## 4. Sunucuya bağlanma

`n` adlı Shulk World, `shulker:n` dimension'ı olarak açılıyor (Bukkit adı `shulker_n`; vanilla
komutları `execute in shulker:n` ile ulaşabiliyor). Paper'a ek yama gerekmedi:

1. **Region kancası:** `.shulk` region yaması `RegionFileStorage`'ın açtığı her region dosyasını zaten
   `ShulkRegionFiles.open` üzerinden geçiriyordu. Bu metod artık klasörün bir Shulk World'e ait olup
   olmadığına bakıyor (`ShulkWorldStorage`). Aitse `ShulkWorldRegionFile` döndürüyor: `RegionFile`'ın
   bir alt sınıfı, chunk okuma ve yazmayı bellekteki `ShulkWorld`'e yönlendiriyor. Moonrise, Paper ve
   NMS eklentileri yine bir `RegionFile` görüyor.
2. **Yükleme:** dünyanın küçük dosyaları `world/dimensions/shulker/n/` altına yazılıyor, klasör kayda
   alınıyor, ardından normal `Bukkit.createWorld` çağrılıyor. Generator verilmediyse saklanan
   chunk'ların dışı boş (void) kalıyor.
3. **Kayıt:** ana thread'de `ShulkWorldPreSaveEvent` tetikleniyor, seviye belleğe kaydediliyor
   (`level.save` + Moonrise I/O flush). Sonrası işçi thread'lerinde: küçük dosyalar klasörden tekrar
   okunuyor, dünya serileştiriliyor ve loader'a veriliyor. Bir dünyanın kayıtları sırayla çalışıyor,
   üst üste binmiyor.
4. **Boşaltma:** oyuncular ana dünyanın spawn'ına taşınıyor, `Bukkit.unloadWorld` çalışıyor,
   isteniyorsa kayıt yapılıyor, ardından klasör siliniyor.
5. **Kapanış:** tek MC yaması (`0341-Save-Shulk-Worlds-on-shutdown`). Bütün seviyeler kaydedilip
   region I/O boşaltıldıktan sonra yüklü her Shulk World loader'ına yazılıyor. Kapanışta eklentiler
   zaten devre dışı kalmış oluyor; bir eklentinin loader'ı yazamazsa dünya kaybolmuyor,
   `shulker-worlds/.recovery/<isim>.shulk` dosyasına yazılıyor.
6. **Önceki çalışmadan kalanlar:** sunucu Shulk World'ler yüklüyken çökerse
   `world/dimensions/shulker/` altında sadece küçük dosyalar kalıyor. İlk Shulk World açılırken
   bunlar temizleniyor.

## 5. API (`dev.shulker.api.world`)

```java
if (ShulkWorlds.isAvailable()) {                       // Paper'da false
    ShulkWorlds worlds = ShulkWorlds.get();
    ShulkWorldTemplate island = worlds.readTemplate(worlds.fileLoader(), "island").join();
    worlds.create("island_" + owner, island, worlds.fileLoader(), ShulkWorldProperties.defaults())
        .thenAccept(world -> player.teleportAsync(world.getSpawnLocation()));
}
```

| Metod | İş |
|---|---|
| `fileLoader()`, `registerLoader(plugin, id, loader)`, `loader(id)` | depolama |
| `readTemplate(loader, name)`, `captureTemplate(world, name)` | şablonlar |
| `load(loader, name, properties)`, `load(name, bytes, saveTo, properties)` | yükleme |
| `create(name, template, saveTo, properties)` | şablondan yeni dünya |
| `save(world)`, `serialize(world)`, `unload(world, save)` | kayıt ve boşaltma |
| `isShulkWorld`, `world(name)`, `name(world)`, `properties(world)`, `worlds()` | sorgular |

`ShulkWorldProperties`: environment, read-only, otomatik kayıt aralığı (varsayılan 5 dakika, `ZERO`
ile kapalı), generator (varsayılan void).

Event'ler (ana thread): `ShulkWorldLoadEvent`, `ShulkWorldPreSaveEvent`, `ShulkWorldSaveEvent`,
`ShulkWorldUnloadEvent`.

**Kendi loader'ını kaydeden eklentiler**, dünyalarını `onDisable` içinde boşaltmalı
(`unload(world, true).join()`). Kapanışta eklentiler, dünyalar kaydedilmeden önce devre dışı
kalıyor.

## 6. Komutlar ve araçlar

```
/shulker world list | info <w> | tp <w>
/shulker world load <isim> [loader] [read-only]
/shulker world create <isim> <şablon> [loader]
/shulker world save <w> | unload <w> [no-save] | capture <w> <şablon>
/shulker world import <klasör> <isim> | export <isim> <klasör> [shulk|mca]
```

Yetki: `shulker.command.world` (varsayılan OP).

```bash
java -jar shulker-26.2.jar --convert-world=world/dimensions/minecraft/overworld --to=shulk --layout=world
java -jar shulker-26.2.jar --convert-world=shulker-worlds/island.shulk --to=mca --out=island-folder
java -jar shulker-26.2.jar --verify-world=shulker-worlds/island.shulk
```

Birden fazla dimension içeren bir dünya klasörü reddediliyor. Her dimension klasörü ayrı ayrı
dönüştürülmeli.

## 7. `shulker.yml`

| Ayar | Varsayılan | |
|---|---|---|
| `shulk-worlds.folder` | `shulker-worlds` | dosya loader'ının klasörü |
| `shulk-worlds.compression-level` | `6` | belleğe kaydedilen chunk'ların zstd seviyesi |
| `shulk-worlds.save-threads` | `2` | serileştirme ve loader thread'leri |

## 8. Ölçümler

Ayrıntılar ve ham veriler: [BENCHMARKS.md](BENCHMARKS.md#islands-and-arenas-world-systems).
Ryzen 5 2600, Windows 11, 2 GiB heap, `tools/world-bench`, 50 ada ve 20 arena reset'i, iki koşunun
ortalaması:

| | Shulk World (Shulker) | Ortak dünya + yapıştırma (Paper) | Ada başına dünya (Paper) |
|---|---|---|---|
| Yeni ada, ana thread CPU | 43.5 ms | 13.3 ms | 44.3 ms |
| Yeni ada, en kötü tick | 149 ms | 823 ms | 97 ms |
| Arena reset | 112 ms | 6.8 ms | 164 ms |
| Ada başına disk | 6.6 KiB | 31.2 KiB | 4390 KiB |
| Taşınacak veri | 6.6 KiB | 21.2 KiB | 4390 KiB |
| Taşınan adanın yüklenmesi | 90 ms | — | 61 ms |

Bu sonuçlara giden yolda yapılan düzeltmeler:

- **Boş chunk'lar saklanmıyor** (`EmptyChunks`): yalnızca hava içeren, blok entity'si, tick'i ve
  eklenti verisi olmayan chunk'lar ile entity'siz entity chunk'ları void dünyada aynen yeniden
  oluştuğu için yazılmıyor. İçe aktarılan ve yakalanan şablonlar da aynı şekilde temizleniyor.
  Normal bir void dünyadan alınan şablon 1089 chunk'tan 4 chunk'a indi (166 → 6.4 KiB).
- **Void dünyalar düz tabanla açılıyor** ve saklanan dünya üretim ayarları atılıyor. Böylece her
  dünya için overworld gürültü yönlendiricisi kurulup C2ME ile derlenmiyor. Oluşturma 240 ms'den
  42 ms ana thread CPU'ya indi.
- **Çalışma sırasında açılan dünyalar `spigot.yml` ve `purpur.yml`'yi yeniden yazmıyor**
  (`WorldConfigSaves`, iki yama).
- **C2ME düğümleri hash'lerini önbellekliyor**, ve aynı ayar ve seed'e sahip dünyalar tek bir
  `RandomState`'i (derlenmiş gürültü yönlendiricisiyle birlikte) paylaşıyor. Şablondan kopyalanan
  normal bir dünyayı açmak 222 ms'den 50 ms'ye indi (Paper: 44 ms). Bu düzeltmeler Shulk World'lere
  değil, çalışma sırasında açılan bütün normal dünyalara yarıyor.
- **Atılan dünya kaydedilmiyor:** `unload(world, false)` artık seviyeyi kaydetmiyor. Önceden ana
  thread Minecraft'ın veri dosyalarının paylaşılan bir I/O havuzunda yazılmasını bekliyordu; bir
  koşuda bu bekleme 23 saniyeyi bulmuştu.

Kalan ana thread süresinin büyük kısmı Paper'ın dünya başına kurulumu: Paper, Gale ve Purpur'un
dünya yapılandırmaları ve kayıtlı verilerin okunması.

## 9. Bilinen sınırlamalar

- **Çökmede kayıp:** bellekteki dünya son kayıttan sonraki değişiklikleri kaybeder. Otomatik kayıt
  aralığı bu pencereyi sınırlıyor.
- **Boş chunk'ların biyomu:** saklanmayan boş bir chunk yeniden oluşturulduğunda generator'ın
  biyomunu (düz taban: plains) alır, şablondaki biyomu değil.
- **Dünya başına yapılandırma dosyaları:** Paper ve Gale her dünya için kendi yml'ini oluşturuyor;
  bunlar dünyanın dosyalarıyla birlikte saklanıyor.
- **CLI dönüştürücü boş chunk'ları atmıyor:** `--layout=world` NBT okumadan çalışıyor. Boş
  chunk'lar dünya ilk kez yüklenip kaydedildiğinde ya da `/shulker world import` ile atılıyor.
- **Paralel dünya tick'i** Shulk World'lerle denenmedi.

## 10. Testler

- `ShulkWorldTest`: gidiş-dönüş, determinizm, uç koordinatlar, her 97. baytta bozulma tespiti, kesik
  dosya, region dosyasıyla karışma, yeni sürüm reddi, klonun chunk verisini paylaşması, değişiklik
  takibi, yol güvenliği, atomik yazma, eşzamanlı yazma + serileştirme.
- `ShulkWorldFoldersTest`: içe aktarma (`.mca` + `.shulk` karışık, `dimensions/` düzeni), iki
  formatta dışa aktarma, klasörün değişmeden kalması, çoklu dimension reddi, dosya takibi.
- Elle sunucu testi: içe aktarma, şablondan oluşturma, blok koyma, kaydet → boşalt → yükle (blok ve
  forceload bileti korunuyor), 52 yüklü dünya ile kapanışta kayıt, CLI ile klasör ↔ dosya dönüşümü.
