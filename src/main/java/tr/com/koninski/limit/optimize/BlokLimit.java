package tr.com.koninski.limit.optimize;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Yeni yerlestirilen huni, dropper, dispenser, firin vb. icin chunk basina ust sinir.
 * Asilirsa yerlestirme iptal edilir; mevcut bloklar ASLA silinmez.
 */
final class BlokLimit implements Listener {

    private final Optimize o;
    private final Map<UUID, Long> mesajlar = new HashMap<>();

    BlokLimit(Optimize o) {
        this.o = o;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        OptimizeAyarlar a = o.ayar();
        if (!a.blokAktif) return;
        Block b = event.getBlockPlaced();
        Material tur = b.getType();
        Integer limit = a.blokLimitleri.get(tur); // Ucuz kontrol: sinirli tur degilse hemen cik
        if (limit == null) return;
        Player p = event.getPlayer();
        if (p.hasPermission("koninski.optimize.bypass")) return;
        if (a.araziMuafiyeti && kendiArazisinde(p, b)) return;
        // Yerlestirilen blogun kendisi (olay aninda tile entity olarak sayilsa da sayilmasa da) haric tutulur
        int mevcut = b.getChunk().getTileEntities(x -> x.getType() == tur
                && !(x.getX() == b.getX() && x.getY() == b.getY() && x.getZ() == b.getZ()), false).size();
        if (mevcut < limit) return;
        event.setCancelled(true);
        long simdi = System.currentTimeMillis();
        if (simdi - mesajlar.getOrDefault(p.getUniqueId(), 0L) < 3000) return;
        mesajlar.put(p.getUniqueId(), simdi);
        p.sendMessage("§eBu chunk'ta " + tur.name().toLowerCase().replace('_', ' ') + " sınırı dolu (" + limit
                + "). Sunucu performansı için yeni blok konamadı; mevcut bloklar silinmez.");
    }

    /** GriefPrevention kuruluysa ve blok oyuncunun kendi arazisindeyse true. */
    private boolean kendiArazisinde(Player p, Block b) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return false;
            me.ryanhamshire.GriefPrevention.Claim claim =
                    me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(b.getLocation(), false, null);
            return claim != null && p.getUniqueId().equals(claim.getOwnerID());
        } catch (Throwable t) {
            return false;
        }
    }
}
