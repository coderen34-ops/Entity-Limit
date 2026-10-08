package tr.com.koninski.limit.optimize;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Yuklu chunk'lari tick'lere bolerek gezer (her tick en fazla N chunk). Chunk YUKLEMEZ: tarama anina kadar
 * bosaltilmis chunk atlanir. Ayni anda tek tarama calisir.
 */
final class Tarayici {

    private final Plugin plugin;
    private BukkitTask gorev;

    Tarayici(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean mesgul() { return gorev != null; }

    /**
     * @param dunyalar taranacak dunyalar
     * @param perTick tick basina chunk
     * @param isle    her yuklu chunk icin (ana thread)
     * @param bitti   tarama bitince (ana thread)
     * @return baska tarama suruyorsa false
     */
    boolean baslat(List<World> dunyalar, int perTick, Consumer<Chunk> isle, Runnable bitti) {
        if (gorev != null) return false;
        Deque<Chunk> kuyruk = new ArrayDeque<>();
        for (World w : dunyalar) for (Chunk c : w.getLoadedChunks()) kuyruk.add(c);
        gorev = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < perTick && !kuyruk.isEmpty(); i++) {
                Chunk c = kuyruk.poll();
                if (!c.isLoaded()) continue; // Arada bosaltildi; yuklemiyoruz
                isle.accept(c);
            }
            if (kuyruk.isEmpty()) {
                gorev.cancel();
                gorev = null;
                bitti.run();
            }
        }, 1L, 1L);
        return true;
    }

    void iptal() {
        if (gorev != null) gorev.cancel();
        gorev = null;
    }
}
