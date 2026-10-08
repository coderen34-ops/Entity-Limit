package tr.com.koninski.limit.optimize;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Temizligin ASLA dokunmadigi entity/esyalar. Supheli durumda korur (silmez).
 */
final class Koruma {

    private Koruma() {}

    /** PDC'de herhangi bir anahtar (herhangiPdcKoru) ya da korumali namespace'ten anahtar var mi? */
    static boolean pdcKorumali(PersistentDataContainer pdc, OptimizeAyarlar a) {
        if (pdc == null || pdc.isEmpty()) return false;
        if (a.herhangiPdcKoru) return true;
        for (NamespacedKey k : pdc.getKeys()) if (a.korumaliNamespaceler.contains(k.getNamespace())) return true;
        return false;
    }

    /** Oyuncu yaricap icinde mi? (ayni dunya; dunyadaki oyuncu listesi kucuk) */
    static boolean oyuncuYakininda(Entity e, OptimizeAyarlar a) {
        if (a.oyuncuKorumaYaricapi <= 0) return false;
        World w = e.getWorld();
        double r2 = (double) a.oyuncuKorumaYaricapi * a.oyuncuKorumaYaricapi;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(e.getLocation()) <= r2) return true;
        return false;
    }

    /** Entity silinebilir mi? Koylu hicbir zaman silinmez. */
    static boolean entityKorumali(Entity e, OptimizeAyarlar a) {
        if (e instanceof Player || e instanceof Villager) return true;
        if (e.customName() != null) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        if (e instanceof LivingEntity le && le.isLeashed()) return true;
        if (!e.getPassengers().isEmpty() || e.getVehicle() != null) return true;
        if (e instanceof Mob m && !m.hasAI()) return true; // NPC'ler (Meslek/Klan/Arena NPC'leri AI kapali)
        if (e.isInvulnerable()) return true;
        if (pdcKorumali(e.getPersistentDataContainer(), a)) return true;
        return oyuncuYakininda(e, a);
    }

    /** Koylu araçtan indirilebilir mi? Adli, meslekli ya da PDC'li koyluye dokunulmaz. */
    static boolean koyluKorumali(Villager v, OptimizeAyarlar a) {
        if (v.customName() != null) return true;
        if (v.getProfession() != Villager.Profession.NONE) return true;
        if (!v.getPersistentDataContainer().isEmpty()) return true;
        if (v.isLeashed() || !v.hasAI()) return true;
        return oyuncuYakininda(v, a);
    }

    /** Yerdeki esya silinebilir mi? Para, isimli/lore'lu, PDC'li esya ve genc esyalar korunur. */
    static boolean esyaKorumali(Item item, OptimizeAyarlar a) {
        if (item.getTicksLived() < a.esyaYasDakika * 1200) return true;
        if (item.isUnlimitedLifetime() || item.customName() != null) return true;
        if (!item.getPersistentDataContainer().isEmpty()) return true;
        ItemStack s = item.getItemStack();
        if (s.hasItemMeta()) {
            ItemMeta m = s.getItemMeta();
            if (m.hasDisplayName() || m.hasLore() || m.hasCustomModelData()) return true;
            if (!m.getPersistentDataContainer().isEmpty()) return true; // Para (economy:value), klan/meslek esyalari
        }
        return oyuncuYakininda(item, a);
    }
}
