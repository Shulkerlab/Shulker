# Açık sorular

Geliştirme sırasında senin kararını gerektiren noktalar. Her birinde şimdilik en mantıklı gördüğüm
seçeneği uyguladım; "Şu anki seçim" satırı ne yaptığımı söylüyor. İş bitince bunları birlikte
gözden geçirebiliriz. Değiştirmek istediğin her karar kolayca geri alınabilir.

## Genel

### S1. Paket adı / alan adı
`dev.shulker` kullanılıyor (launcher: `dev.shulker.launcher`, sunucu: `dev.shulker.server`).
Sahip olduğun bir alan adı varsa (ör. `com.senin-adin.shulker`) değiştirebiliriz.
- **Şu anki seçim:** `dev.shulker`.

### S2. GitHub ve dağıtım
Commit'ler yalnızca yerel. CI workflow'u yazıldı ama hiç çalıştırılmadı.
- Hangi GitHub hesabı/organizasyonu altında yayınlanacak?
- Jar'lar nereden dağıtılacak (GitHub Releases, kendi site, Modrinth)?
- **Şu anki seçim:** Hiçbir şey push edilmedi.

## Profiller

### S3. Vanilla profilinin kapsamı
`--profile=vanilla` şunları açıyor: piston dupe (TNT/halı/ray), headless piston, bedrock kırma,
end portal ile gravity block dupe, tripwire (string) dupe, end crystal exploit'i, fazla yığınlı
loot, sınırsız entity çarpışması, global mob cap. Ayrıca her mob her tick aktif (farm oranları
vanilla olsun diye), TNT/tick sınırı yok, async pathfinding ve Alternate Current kapalı.
- Açık bırakılmasını istemediğin bir şey var mı? (ör. bedrock kırma)
- Spigot'un oyuncu/mob görünürlük mesafeleri (entity-tracking-range) sadece görseli etkilediği
  için değiştirilmedi.
- **Şu anki seçim:** Yukarıdakilerin hepsi açık.

### S4. Profiller yalnızca ilk kurulumda uygulanıyor
Profil değiştirildiğinde mevcut config dosyalarına dokunulmuyor (kullanıcının ayarlarını ezmemek için).
Sonradan profil değiştiren biri için `/shulker profile apply` gibi bir komut ister misin?
- **Şu anki seçim:** Sadece ilk kurulum; profil değişince log'a not düşülüyor.

## Performans

### S5. Async pathfinding varsayılan olarak açık
Leaf'te varsayılan kapalı. Mob'lar yolu bir tick geç alabiliyor (gözle fark edilmez). Pathfinding
ağırlıklı yükte tick süresini yarıya indiriyor. Vanilla profilinde kapalı.
- **Şu anki seçim:** vanilla dışındaki profillerde açık.

### S6. Multithreaded entity tracker varsayılan olarak açık
Leaf'te "deneysel" ve varsayılan kapalı. Riski kabul ettin. 40 bot ve 1500 mob ile ortalama tick
süresi 34.8 ms'den 28.2 ms'ye indi. Bot'lar tracker açıkken ve kapalıyken aynı paketleri alıyor.
Citizens gibi NPC eklentileriyle henüz test edilmedi (Citizens'ın derleme sunucusuna otomatik
erişilemiyor). Sorun çıkarsa `shulker.yml` → `entity-tracker.multithreaded: false`.
- **Şu anki seçim:** vanilla dışındaki profillerde açık.

### S7. Yavaş istemci sınırı: 32 MiB
Bir oyuncu için gönderilmeyi bekleyen veri 32 MiB'ı aşınca bağlantı kesiliyor. Hiç okumayan
istemcileri vanilla keep-alive zaten 30 saniyede düşürüyor; bu sınır, o 30 saniye içinde belleğin
dolmasını engelliyor. Normal oyuncular testte bu sınıra hiç yaklaşmadı.
- **Şu anki seçim:** 32 MiB (`network.slow-client-protection.max-pending-megabytes`).

### S8. Hız (motion) paketi filtresi varsayılan olarak açık
Canvas'ın filtresi: periyodik hız güncellemesi yalnızca item, ender eye, squid, shulker mermisi ve
oltaya gönderiliyor; knockback her zaman gönderiliyor. 40 bot + 1500 mob testinde oyuncu başına
trafik %25 azaldı, MSPT değişmedi. Görsel etkisi gerçek bir istemciyle henüz kontrol edilmedi
(bot'lar görüntü çizmiyor). Mob hareketlerinde takılma görürsen:
`entity-tracker.filter-motion-packets: false`.
- **Şu anki seçim:** vanilla dışındaki profillerde açık.

### S9. Paralel dünya tick'i (SparklyPaper) varsayılan olarak kapalı
Açık olduğunda her dünya kendi thread'inde tick'leniyor. Üç dünyaya dağıtılmış 1500 mobla ortalama
tick 20.4 ms'den 14.5 ms'ye iniyor (−%29). Tek dünyası yüklü sunucularda (çoğu survival, tek dünyalı
skyblock eklentileri) kazanç yok. Başka bir dünyaya dokunan eklentiler thread kontrolüne takılıp
sunucuyu çökertebiliyor (NoCheatPlus bilinen örnek), oyuncu olmayan entity'ler portal kullanamıyor.
Kapalıyken SparklyPaper'ın eklediği ek kontroller ve kısıtların hiçbiri çalışmıyor; sunucu Paper gibi
davranıyor. Açıkken başlangıçta büyük bir uyarı basılıyor. SparklyPaper'ın lisans dosyası yok;
GPLv3 gerekçesiyle ve atıfla kullanılıyor (`CREDITS.md`).
- **Şu anki seçim:** tüm profillerde kapalı (`parallel-world-ticking.enabled`).

### S10. Tick thread izolasyonu 6+ fiziksel çekirdekte otomatik açık
Tick thread'ine kendi fiziksel çekirdeği veriliyor (hibrit Intel'de P-core, çift CCD'li X3D Ryzen'de
büyük önbellekli CCD). Test makinesinde (6 çekirdek, sınırlı kaynak) ortalama MSPT ~%7 düştü; hibrit
ve X3D seçimi yalnızca birim testleriyle doğrulandı (o donanım yok).
- **Şu anki seçim:** `tick-thread-isolation=auto` (`shulker-launcher.properties`).

### S11. TT20 gecikme telafisi yaması AGPL-3.0 lisanslı
Leaf'in tüm yamaları alınırken TT20 "lag compensation" yaması da geldi. Bu yama AGPL-3.0-only
lisanslı; Shulker ise GPLv3. GPLv3'ün 13. maddesi ikisinin birleştirilmesine izin veriyor, ama o
kısım AGPL şartlarını koruyor: değiştirilmiş hâliyle çalışan bir sunucuda oyunculara kaynak kodunu
sunma yükümlülüğü doğabilir. Özellik varsayılan kapalı ve oyun davranışını değiştiriyor (Leaf'te taş
jeneratörlerini bozduğu için düzeltme almıştı).
- **Şu anki seçim:** yama duruyor, özellik her profilde kapalı. Lisansı tamamen GPLv3 tutmak
  istersen yama (`0174-TT20-Lag-compensation.patch`) kaldırılabilir.

### S12. Leaf'in SIMD desteği için `--add-modules=jdk.incubator.vector`
Pufferfish'in SIMD kodu (harita renk eşleme) bu modül olmadan kendini kapatıyor. Launcher şu an
modülü eklemiyor; eklemek AOT önbelleğinin tam modül grafiğini bozabilir (bkz. boot.jar notu).
- **Şu anki seçim:** eklenmiyor. Kazanç yalnızca harita çizen eklentilerde görülür.

### S13. C2ME native matematik yaması alınmadı
DivineMC'nin C2ME native matematik yaması, repoda hazır derlenmiş gelen `.so`/`.dll` dosyalarını
yüklüyor. Kaynağından kendimiz derlemediğimiz native kodu jar'a koymak tedarik zinciri riski; ayrıca
yalnızca x86_64'te çalışıyor. Gerekirse C2ME'nin kaynağından kendi derlememizle eklenebilir.
- **Şu anki seçim:** yok.

### S14. Profillerin açtığı bazı Leaf ayarları Leaf'te "deneysel" işaretli
optimize-despawn, optimize-entity-activation, optimize-mob-spawning, optimize-random-tick,
optimize-waypoint ve density-function-compiler açılışta "experimental" uyarısı veriyor. Eklenti
testleri geçiyor; density-function-compiler ayrıca dünya üretimi eşlik testinden geçti. Diğerleri
için oyun davranışını ölçen ayrı bir test yok.
- **Şu anki seçim:** vanilla dışındaki profillerde açık.

### S15. Lazy paket gönderimi ProtocolLib ile denenmedi
`network.lazy-packet-scheduling` bir eklenti event loop'u sardığında (ProtocolLib'in yaptığı)
kendini o bağlantı için kapatıyor. ProtocolLib'in 26.2 sürümü olmadığı için bu yol gerçek eklentiyle
denenemedi; PacketEvents ve ViaVersion ile test geçiyor.
- **Şu anki seçim:** açık. Sorun görülürse `network.lazy-packet-scheduling: false`.

## Karar verilenler

- **Dünya üretimini değiştiren yamalar alınmaz.** `scripts/worldgen-parity-test.sh` bu yüzden
  var: C2ME Aquifer yeniden yazımı ve StructureLayoutOptimizer araziyi/deneme odalarını değiştirdiği
  için çıkarıldı (2026-09-29).
- **Dinamik view distance yok.** View distance, sunucu başlarken `server.properties`'ten okunan
  sabit bir değer olarak kalıyor. Profiller yalnızca ilk kurulumda bu değeri belirliyor.
