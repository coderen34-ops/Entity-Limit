package tr.com.koninski.limit.optimize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Villager;

import tr.com.koninski.limit.KoninskiEntityLimit;

/**
 * /optimize temizle <grup|koylu-arac|esya> [--onayla]
 * - Varsayilan dry-run: ne kadarinin, hangi chunk'larda kaldirilacagini soyler, hicbir sey silmez.
 * - --onayla: once son uyari; 30 sn icinde ayni komut tekrar yazilirsa uygulanir.
 * - Sadece limiti asan chunk'larda FAZLA olan kaldirilir; korumali entity/esyaya (Koruma) asla dokunulmaz.
 * - Koylu hicbir modda silinmez; "koylu-arac" sadece araçtan indirir (ayara gore bos araci da siler).
 */
final class Temizlik {

    private record Plan(String chunk, int kaldirilacak) {}

    private final Optimize o;
    private final Map<String, Long> onayBekleyen = new HashMap<>(); // gonderen + komut -> son tarih
    private String sonOzet = "-";

    Temizlik(Optimize o) {
        this.o = o;
    }

    String sonOzet() { return sonOzet; }

    void komut(CommandSender s, String hedef, boolean onayla) {
        hedef = hedef.toLowerCase(Locale.ROOT);
        KoninskiEntityLimit.Group grup = null;
        if (!hedef.equals("koylu-arac") && !hedef.equals("esya")) {
            for (KoninskiEntityLimit.Group g : o.ana().groups()) if (g.id().equalsIgnoreCase(hedef)) grup = g;
            if (grup == null) {
                s.sendMessage("§cBöyle bir grup yok. Gruplar: §f" + String.join(", ", o.ana().groups().stream().map(KoninskiEntityLimit.Group::id).toList())
                        + "§c, ayrıca §fkoylu-arac§c, §fesya");
                return;
            }
            if (grup.types().contains("VILLAGER")) {
                s.sendMessage("§cKöylüler hiçbir modda silinmez. Araçtaki köylüler için: §f/optimize temizle koylu-arac");
                return;
            }
        }
        boolean uygula = false;
        if (onayla) {
            String anahtar = s.getName() + "|" + hedef;
            Long t = onayBekleyen.get(anahtar);
            if (t != null && System.currentTimeMillis() - t < 30_000) {
                onayBekleyen.remove(anahtar);
                uygula = true;
            } else {
                onayBekleyen.put(anahtar, System.currentTimeMillis());
                s.sendMessage("§c§lUYARI: §cBu işlem entity'leri kalıcı olarak kaldırır (geri alınamaz). Korumalı olanlara dokunulmaz.");
                s.sendMessage("§cOnaylıyorsan 30 saniye içinde aynı komutu tekrar yaz: §f/optimize temizle " + hedef + " --onayla");
                return;
            }
        }
        if (hedef.equals("koylu-arac") && uygula && o.ayar().koyluAracIslem == OptimizeAyarlar.KoyluAracIslem.RAPOR) {
            s.sendMessage("§econfig'te temizlik.koylu-arac-islem: RAPOR; sadece rapor verilecek. İndirmek için INDIR ya da INDIR_VE_ARACI_SIL yapın.");
            uygula = false;
        }
        final boolean gercek = uygula;
        final KoninskiEntityLimit.Group g = grup;
        final String h = hedef;
        List<Plan> plan = new ArrayList<>();
        int[] korunan = {0}, toplam = {0}, aracSilinen = {0};
        s.sendMessage("§7" + (gercek ? "Temizlik uygulanıyor" : "Deneme (dry-run) taraması") + "...");
        boolean basladi = o.tarayici().baslat(new ArrayList<>(Bukkit.getWorlds()), o.ayar().taramaChunkPerTick, c -> {
            if (!c.isEntitiesLoaded()) return;
            int n;
            if (g != null) n = grupChunk(c, g, gercek, korunan);
            else if (h.equals("esya")) n = esyaChunk(c, gercek, korunan);
            else n = koyluAracChunk(c, gercek, korunan, aracSilinen);
            if (n > 0) {
                plan.add(new Plan(Rapor.chunkYazi(c), n));
                toplam[0] += n;
            }
        }, () -> bitir(s, h, gercek, plan, toplam[0], korunan[0], aracSilinen[0]));
        if (!basladi) s.sendMessage("§cŞu an başka bir tarama sürüyor, biraz sonra tekrar deneyin.");
    }

    /** Grup: chunk limitini asan fazlayi (en yeni dogandan baslayarak) kaldirir. */
    private int grupChunk(Chunk c, KoninskiEntityLimit.Group g, boolean uygula, int[] korunan) {
        List<Entity> eslesen = new ArrayList<>();
        for (Entity e : c.getEntities()) if (!e.isDead() && g.matches(e)) eslesen.add(e);
        int fazla = eslesen.size() - g.chunkLimit();
        if (fazla <= 0) return 0;
        List<Entity> silinebilir = new ArrayList<>();
        for (Entity e : eslesen) {
            if (Koruma.entityKorumali(e, o.ayar())) korunan[0]++;
            else silinebilir.add(e);
        }
        silinebilir.sort(Comparator.comparingInt(Entity::getTicksLived)); // En yeniler once; yerlesik olanlar kalir
        int n = Math.min(fazla, silinebilir.size());
        if (uygula) for (int i = 0; i < n; i++) silinebilir.get(i).remove();
        return n;
    }

    /** Yerdeki esya: esigi asan chunk'ta korunmayan, yasli esyalar (en yasli once) esige inene kadar. */
    private int esyaChunk(Chunk c, boolean uygula, int[] korunan) {
        List<Item> esyalar = new ArrayList<>();
        for (Entity e : c.getEntities()) if (e instanceof Item i && !i.isDead()) esyalar.add(i);
        int fazla = esyalar.size() - o.ayar().esyaUyariEsigi;
        if (fazla <= 0) return 0;
        List<Item> silinebilir = new ArrayList<>();
        for (Item i : esyalar) {
            if (Koruma.esyaKorumali(i, o.ayar())) korunan[0]++;
            else silinebilir.add(i);
        }
        silinebilir.sort(Comparator.comparingInt(Entity::getTicksLived).reversed());
        int n = Math.min(fazla, silinebilir.size());
        if (uygula) for (int i = 0; i < n; i++) silinebilir.get(i).remove();
        return n;
    }

    /** Araçtaki koylu: indirilebilir olanlar (adsiz, meslegsiz, PDC'siz) sayilir; uygulanirsa indirilir. */
    private int koyluAracChunk(Chunk c, boolean uygula, int[] korunan, int[] aracSilinen) {
        int n = 0;
        for (Entity e : c.getEntities()) {
            if (!(e instanceof Villager v)) continue;
            Entity arac = v.getVehicle();
            if (!(arac instanceof Minecart || arac instanceof Boat)) continue;
            if (Koruma.koyluKorumali(v, o.ayar())) { korunan[0]++; continue; }
            n++;
            if (!uygula) continue;
            v.leaveVehicle();
            if (o.ayar().koyluAracIslem == OptimizeAyarlar.KoyluAracIslem.INDIR_VE_ARACI_SIL && arac.getPassengers().isEmpty()
                    && arac.customName() == null && arac.getPersistentDataContainer().isEmpty() && !Koruma.oyuncuYakininda(arac, o.ayar())) {
                arac.remove();
                aracSilinen[0]++;
            }
        }
        return n;
    }

    private void bitir(CommandSender s, String hedef, boolean uygula, List<Plan> plan, int toplam, int korunan, int aracSilinen) {
        String fiil = hedef.equals("koylu-arac") ? (uygula ? "araçtan indirildi" : "araçtan indirilecek") : (uygula ? "kaldırıldı" : "kaldırılacak");
        s.sendMessage((uygula ? "§a" : "§e") + (uygula ? "" : "[DENEME] ") + hedef + ": §f" + toplam + " §7entity " + fiil + " §8("
                + plan.size() + " chunk, korunduğu için atlanan " + korunan + (aracSilinen > 0 ? ", silinen boş araç " + aracSilinen : "") + ")");
        plan.sort(Comparator.comparingInt(Plan::kaldirilacak).reversed());
        for (Plan p : plan.subList(0, Math.min(10, plan.size()))) s.sendMessage("§7 " + p.chunk() + ": §f" + p.kaldirilacak());
        if (!uygula && toplam > 0) s.sendMessage("§7Uygulamak için: §f/optimize temizle " + hedef + " --onayla");
        List<String> satirlar = new ArrayList<>();
        for (Plan p : plan.subList(0, Math.min(50, plan.size()))) satirlar.add(p.chunk() + ": " + p.kaldirilacak());
        o.log().yaz(s.getName(), uygula ? "TEMIZLIK" : "TEMIZLIK-DENEME", hedef + (aracSilinen > 0 ? " (+" + aracSilinen + " arac)" : ""), toplam, satirlar);
        if (uygula) sonOzet = hedef + ": " + toplam + " (" + s.getName() + ", " + java.time.LocalTime.now().withNano(0) + ")";
    }

    /** Tab tamamlama icin hedefler. */
    List<String> hedefler() {
        List<String> l = new ArrayList<>();
        for (KoninskiEntityLimit.Group g : o.ana().groups()) if (!g.types().contains("VILLAGER")) l.add(g.id());
        l.add("koylu-arac");
        l.add("esya");
        return l;
    }
}
