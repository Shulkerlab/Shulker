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

## Karar verilenler

- **Dinamik view distance yok.** View distance, sunucu başlarken `server.properties`'ten okunan
  sabit bir değer olarak kalıyor. Profiller yalnızca ilk kurulumda bu değeri belirliyor.
