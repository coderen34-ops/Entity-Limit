package tr.com.koninski.limit.optimize;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;

import tr.com.koninski.limit.KoninskiEntityLimit;

/** plugins/KoninskiEntityLimit/optimize.log (UTF-8, arka planda tek thread) + konsola ASCII ozet. */
final class OptimizeLog {

    private static final DateTimeFormatter BICIM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JavaPlugin plugin;
    private final File dosya;
    private final ExecutorService yazici = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KoninskiEntityLimit-Log");
        t.setDaemon(true);
        return t;
    });

    OptimizeLog(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "optimize.log");
    }

    /** Ozet satiri + (varsa) chunk ayrinti satirlari. */
    void yaz(String kim, String islem, String tur, int sayi, List<String> chunklar) {
        String zaman = LocalDateTime.now().format(BICIM);
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(zaman).append("] ").append(kim).append(" | ").append(islem).append(" | ").append(tur)
                .append(" | ").append(sayi).append(System.lineSeparator());
        for (String c : chunklar) sb.append("    ").append(c).append(System.lineSeparator());
        String metin = sb.toString();
        plugin.getLogger().info(KoninskiEntityLimit.ascii("[Optimize] " + kim + " | " + islem + " | " + tur + " | " + sayi
                + (chunklar.isEmpty() ? "" : " | " + chunklar.size() + " chunk")));
        Runnable is = () -> {
            try {
                Files.createDirectories(dosya.getParentFile().toPath());
                Files.writeString(dosya.toPath(), metin, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().warning("optimize.log yazilamadi: " + e.getMessage());
            }
        };
        if (yazici.isShutdown()) is.run();
        else yazici.execute(is);
    }

    void kapat() {
        yazici.shutdown();
        try { yazici.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
