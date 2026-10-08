package tr.com.koninski.limit.optimize;

import org.bukkit.Bukkit;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import tr.com.koninski.limit.KoninskiEntityLimit;

/**
 * Yuk modu (varsayilan KAPALI): "kontrol-saniye"de bir TPS'e bakar. TPS acilis esiginin altina dusunce yeni dogal
 * canavar/ambient dogmasi gecici olarak iptal edilir; kapanis esiginin ustune cikinca kapanir (histerezis).
 * Spawner, mob farm, yumurta, komut ve ureme sebeplerine hicbir zaman dokunmaz.
 */
final class YukModu implements Listener {

    private final Optimize o;
    private BukkitTask gorev;
    private boolean acik = false;
    private long sonDegisim = 0;

    YukModu(Optimize o) {
        this.o = o;
    }

    boolean acik() { return acik; }
    long sonDegisim() { return sonDegisim; }

    /** Ayarlara gore zamanlayiciyi (yeniden) kurar. Kapaliysa yuk modu da kapatilir. */
    void kur() {
        if (gorev != null) gorev.cancel();
        gorev = null;
        if (!o.ayar().yukAktif) {
            if (acik) degistir(false, Bukkit.getServer().getTPS()[0]);
            return;
        }
        long aralik = o.ayar().yukAralikSaniye * 20L;
        gorev = Bukkit.getScheduler().runTaskTimer(o.ana(), this::kontrol, aralik, aralik);
    }

    private void kontrol() {
        double tps = Bukkit.getServer().getTPS()[0]; // Son 1 dakikanin ortalamasi: anlik dalgalanmaya kapilmaz
        if (!acik && tps < o.ayar().yukAcilisTps) degistir(true, tps);
        else if (acik && tps >= o.ayar().yukKapanisTps) degistir(false, tps);
    }

    private void degistir(boolean yeni, double tps) {
        acik = yeni;
        sonDegisim = System.currentTimeMillis();
        String metin = yeni
                ? "Yük modu AÇILDI (TPS " + String.format("%.1f", tps) + "): yeni doğal canavar/yarasa doğması geçici olarak durduruldu."
                : "Yük modu kapandı (TPS " + String.format("%.1f", tps) + "): doğal doğma normale döndü.";
        o.ana().getLogger().info(KoninskiEntityLimit.ascii("[Optimize] " + metin));
        if (!o.ayar().yukBildirim) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("koninski.optimize.admin")) p.sendMessage((yeni ? "§c" : "§a") + "[Optimize] " + metin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!acik) return; // Ucuz kontrol: yuk modu kapaliyken hicbir sey yapmaz
        if (!o.ayar().yukSebepler.contains(event.getSpawnReason().name())) return;
        if ((o.ayar().yukCanavar && event.getEntity() instanceof Enemy) || (o.ayar().yukAmbient && event.getEntity() instanceof Ambient)) {
            event.setCancelled(true);
        }
    }

    void durdur() {
        if (gorev != null) gorev.cancel();
        gorev = null;
    }
}
