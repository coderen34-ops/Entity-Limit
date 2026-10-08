package tr.com.koninski.limit.optimize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Villager;

/**
 * /optimize rapor [dunya]: yuklu chunk'lar tick'lere bolunerek taranir (chunk yuklenmez), sonra ozet yazilir.
 */
final class Rapor {

    /** Bir chunk'in ozeti (koordinat blok cinsinden). */
    private record ChunkOzet(String dunya, int x, int y, int z, int entity, int tile, int esya, String baskin) {}

    private final Optimize o;

    Rapor(Optimize o) {
        this.o = o;
    }

    void baslat(CommandSender s, List<World> dunyalar) {
        // Dunya basina sayaclar
        Map<String, int[]> dunyaSayac = new LinkedHashMap<>(); // {entity, tile, chunk}
        Map<EntityType, Integer> turler = new EnumMap<>(EntityType.class);
        Map<Material, Integer> tileTurleri = new EnumMap<>(Material.class);
        List<ChunkOzet> chunklar = new ArrayList<>();
        Map<String, Integer> aracKoylu = new HashMap<>(); // "dunya x y z" (chunk ortasi) -> sayi
        int[] toplamAracKoylu = {0};
        int[] toplamEsya = {0};
        for (World w : dunyalar) dunyaSayac.put(w.getName(), new int[]{0, 0, 0});
        long bas = System.currentTimeMillis();
        s.sendMessage("§7Rapor hazırlanıyor (" + dunyalar.size() + " dünya, tick başına " + o.ayar().taramaChunkPerTick + " chunk)...");

        boolean basladi = o.tarayici().baslat(dunyalar, o.ayar().taramaChunkPerTick, c -> {
            int[] ds = dunyaSayac.get(c.getWorld().getName());
            ds[2]++;
            int entity = 0, esya = 0, y = 64;
            Map<EntityType, Integer> yerel = new EnumMap<>(EntityType.class);
            if (c.isEntitiesLoaded()) {
                for (Entity e : c.getEntities()) {
                    entity++;
                    turler.merge(e.getType(), 1, Integer::sum);
                    yerel.merge(e.getType(), 1, Integer::sum);
                    y = e.getLocation().getBlockY();
                    if (e instanceof Item) esya++;
                    if (e instanceof Villager && (e.getVehicle() instanceof Minecart || e.getVehicle() instanceof Boat)) {
                        toplamAracKoylu[0]++;
                        aracKoylu.merge(c.getWorld().getName() + " " + ((c.getX() << 4) + 8) + " " + e.getLocation().getBlockY() + " " + ((c.getZ() << 4) + 8), 1, Integer::sum);
                    }
                }
            }
            BlockState[] tiles = c.getTileEntities(false); // snapshot almadan (ucuz)
            for (BlockState b : tiles) tileTurleri.merge(b.getType(), 1, Integer::sum);
            if (entity == 0 && tiles.length > 0) y = tiles[0].getY();
            ds[0] += entity;
            ds[1] += tiles.length;
            toplamEsya[0] += esya;
            if (entity > 0 || tiles.length > 0) {
                String baskin = yerel.entrySet().stream().max(Map.Entry.comparingByValue())
                        .map(en -> en.getKey().name() + " x" + en.getValue()).orElse("-");
                chunklar.add(new ChunkOzet(c.getWorld().getName(), (c.getX() << 4) + 8, y, (c.getZ() << 4) + 8, entity, tiles.length, esya, baskin));
            }
        }, () -> yaz(s, dunyalar, dunyaSayac, turler, tileTurleri, chunklar, aracKoylu, toplamAracKoylu[0], toplamEsya[0], bas));
        if (!basladi) s.sendMessage("§cŞu an başka bir tarama sürüyor, biraz sonra tekrar deneyin.");
    }

    private void yaz(CommandSender s, List<World> dunyalar, Map<String, int[]> dunyaSayac, Map<EntityType, Integer> turler,
                     Map<Material, Integer> tileTurleri, List<ChunkOzet> chunklar, Map<String, Integer> aracKoylu,
                     int toplamAracKoylu, int toplamEsya, long bas) {
        int toplamEntity = 0;
        s.sendMessage("§6§l=== Sunucu Raporu ===§7 (" + (System.currentTimeMillis() - bas) / 1000.0 + " sn)");
        for (World w : dunyalar) {
            int[] d = dunyaSayac.get(w.getName());
            toplamEntity += d[0];
            s.sendMessage("§e" + w.getName() + "§7: entity §f" + d[0] + "§7, tile §f" + d[1] + "§7, yüklü chunk §f" + d[2]
                    + "§7, oyuncu §f" + w.getPlayers().size());
        }

        s.sendMessage("§6Entity türleri (ilk 10):");
        int toplam = Math.max(1, toplamEntity);
        turler.entrySet().stream().sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed()).limit(10)
                .forEach(e -> s.sendMessage("§7 " + e.getKey().name() + ": §f" + e.getValue() + " §8(%" + Math.round(100.0 * e.getValue() / toplam) + ")"));

        s.sendMessage("§6En kalabalık 10 chunk (entity):");
        chunklar.stream().sorted(Comparator.comparingInt(ChunkOzet::entity).reversed()).limit(10)
                .forEach(c -> s.sendMessage("§7 " + c.dunya() + " " + c.x() + ", " + c.z() + ": §f" + c.entity() + " entity§7, " + c.tile()
                        + " tile, baskın " + c.baskin() + " §8/tp @s " + c.x() + " " + c.y() + " " + c.z()));

        s.sendMessage("§6Araçtaki köylüler: §f" + toplamAracKoylu + (toplamAracKoylu > 0 ? " §7(en kalabalık yerler):" : ""));
        aracKoylu.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
                .forEach(e -> {
                    String[] p = e.getKey().split(" ");
                    s.sendMessage("§7 " + p[0] + " " + p[1] + ", " + p[3] + ": §f" + e.getValue() + " köylü §8/tp @s " + p[1] + " " + p[2] + " " + p[3]);
                });

        s.sendMessage("§6Tile entity türleri (ilk 10):");
        tileTurleri.entrySet().stream().sorted(Map.Entry.<Material, Integer>comparingByValue().reversed()).limit(10)
                .forEach(e -> s.sendMessage("§7 " + e.getKey().name() + ": §f" + e.getValue()));
        s.sendMessage("§6En yoğun 10 chunk (tile):");
        chunklar.stream().filter(c -> c.tile() > 0).sorted(Comparator.comparingInt(ChunkOzet::tile).reversed()).limit(10)
                .forEach(c -> s.sendMessage("§7 " + c.dunya() + " " + c.x() + ", " + c.z() + ": §f" + c.tile() + " tile §8/tp @s " + c.x() + " " + c.y() + " " + c.z()));

        int esik = o.ayar().esyaUyariEsigi;
        s.sendMessage("§6Yerdeki eşya: §f" + toplamEsya + " §7(uyarı eşiği chunk başı " + esik + ")");
        chunklar.stream().filter(c -> c.esya() > 0).sorted(Comparator.comparingInt(ChunkOzet::esya).reversed()).limit(5)
                .forEach(c -> s.sendMessage("§7 " + c.dunya() + " " + c.x() + ", " + c.z() + ": " + (c.esya() > esik ? "§c" : "§f") + c.esya()
                        + " eşya" + (c.esya() > esik ? " (eşik aşıldı)" : "") + " §8/tp @s " + c.x() + " " + c.y() + " " + c.z()));
        o.log().yaz(s.getName(), "RAPOR", dunyalar.size() + " dunya", toplamEntity, List.of());
    }

    /** Chunk icin dunya adi + blok koordinati (log/mesaj). */
    static String chunkYazi(Chunk c) {
        return c.getWorld().getName() + " " + ((c.getX() << 4) + 8) + ", " + ((c.getZ() << 4) + 8);
    }
}
