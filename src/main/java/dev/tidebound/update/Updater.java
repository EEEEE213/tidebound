package dev.tidebound.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.tidebound.Tidebound;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps Tidebound up to date from its GitHub releases. At startup a background thread asks GitHub for the latest
 * release; if it is newer than the running version, its jar is downloaded next to the current one and swapped in,
 * so the next launch runs the new version. The running jar is renamed out of the way if Windows allows it, otherwise
 * the swap happens just after the game closes. Turn it off with enabled=false in config/tidebound-updater.properties.
 */
public final class Updater {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String REPO = "EEEEE213/tidebound";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** Set once a newer version has been downloaded (shown to the player when they join a world). */
    @Nullable public static volatile String downloaded;
    /** True when the swap could only be scheduled for when the game closes. */
    public static volatile boolean pendingRestart;

    private Updater() {
    }

    public static void start() {
        Thread t = new Thread(Updater::run, "Tidebound updater");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            if (!enabled()) {
                LOG.info("[updater] disabled in config/tidebound-updater.properties");
                return;
            }
            var file = ModList.get().getModFileById(Tidebound.MODID);
            if (file == null) return;
            Path self = file.getFile().getFilePath();
            if (!Files.isRegularFile(self) || !self.toString().endsWith(".jar")) {
                LOG.info("[updater] not running from a jar (dev environment), skipping");
                return;
            }
            Path mods = self.getParent();
            cleanup(mods);
            String current = file.versionString();

            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(API))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Tidebound-Updater")
                    .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                LOG.info("[updater] no release info (HTTP {})", res.statusCode());
                return;
            }
            JsonObject rel = JsonParser.parseString(res.body()).getAsJsonObject();
            String latest = rel.get("tag_name").getAsString().replaceFirst("^v", "");
            if (new DefaultArtifactVersion(latest).compareTo(new DefaultArtifactVersion(current)) <= 0) {
                LOG.info("[updater] Tidebound {} is up to date (latest release {})", current, latest);
                return;
            }
            JsonObject asset = null;
            for (var a : rel.getAsJsonArray("assets")) {
                String name = a.getAsJsonObject().get("name").getAsString();
                if (name.startsWith("tidebound-") && name.endsWith(".jar")) asset = a.getAsJsonObject();
            }
            if (asset == null) {
                LOG.warn("[updater] release {} has no Tidebound jar", latest);
                return;
            }
            String name = asset.get("name").getAsString();
            long size = asset.get("size").getAsLong();
            Path target = mods.resolve(name);
            Path part = mods.resolve(name + ".part");
            LOG.info("[updater] downloading Tidebound {} ({} bytes)", latest, size);
            HttpResponse<InputStream> dl = http.send(HttpRequest.newBuilder(URI.create(asset.get("browser_download_url").getAsString()))
                    .header("User-Agent", "Tidebound-Updater").timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = dl.body(); OutputStream out = Files.newOutputStream(part)) {
                in.transferTo(out);
            }
            if (dl.statusCode() != 200 || Files.size(part) != size) {
                Files.deleteIfExists(part);
                LOG.warn("[updater] download failed (HTTP {}, {} of {} bytes)", dl.statusCode(), Files.exists(part) ? Files.size(part) : 0, size);
                return;
            }
            swap(self, part, target);
            downloaded = latest;
        } catch (Exception e) {
            LOG.warn("[updater] update check failed: {}", e.toString());
        }
    }

    /**
     * Never leave two Tidebound jars in mods (the game would refuse to start): the new jar only gets its .jar name
     * once the old one is out of the way.
     */
    private static void swap(Path self, Path part, Path target) throws IOException {
        Path old = self.resolveSibling(self.getFileName() + ".old");
        try {
            Files.move(self, old, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException locked) {
            // Windows won't let go of the running jar: finish the swap as soon as the game has closed
            pendingRestart = true;
            String cmd = "ping -n 4 127.0.0.1 >nul & del /f /q \"" + self + "\" & move /y \"" + part + "\" \"" + target + "\"";
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", "cmd", "/c", cmd).start();
                } catch (IOException e) {
                    LOG.warn("[updater] could not schedule the update: {}", e.toString());
                }
            }, "Tidebound updater finish"));
            LOG.info("[updater] Tidebound update ready; it is installed when the game closes");
            return;
        }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(old, self, StandardCopyOption.REPLACE_EXISTING); // put the old jar back
            throw e;
        }
        LOG.info("[updater] installed {}; it runs from the next launch", target.getFileName());
    }

    /** Leftovers from earlier updates. */
    private static void cleanup(Path mods) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "tidebound-*.jar.{old,part}")) {
            for (Path p : ds) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean enabled() throws IOException {
        Path cfg = FMLPaths.CONFIGDIR.get().resolve("tidebound-updater.properties");
        Properties p = new Properties();
        if (Files.exists(cfg)) {
            try (InputStream in = Files.newInputStream(cfg)) {
                p.load(in);
            }
        } else {
            p.setProperty("enabled", "true");
            try (OutputStream out = Files.newOutputStream(cfg)) {
                p.store(out, "Tidebound updater: set enabled=false to stop automatic updates from github.com/" + REPO);
            }
        }
        return !"false".equalsIgnoreCase(p.getProperty("enabled", "true").trim());
    }
}
