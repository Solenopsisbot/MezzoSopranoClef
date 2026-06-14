package dev.mezzo.clef.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-bootstrapping launcher for the MezzoSopranoClef headless bot.
 *
 * <p>It does what {@code ./gradlew runClient} does, but as a standalone {@code java -jar}: on first
 * run it downloads the official Minecraft client + libraries + assets (from Mojang) and the Fabric
 * loader + intermediary mappings (from Fabric meta), drops the bundled mod + Fabric API into a
 * {@code mods/} folder, then spawns the real client JVM with the GPU-free / headless flags.
 *
 * <p>Minecraft itself is <b>not</b> bundled (it isn't redistributable) — it is downloaded into the
 * game directory on first run and cached for subsequent runs, exactly like any launcher.
 *
 * <p>Config: the bot reads {@code <gameDir>/config/mezzoclef.json} (written on first run). Anything
 * is overridable with {@code -Dmezzoclef.*} passed to this launcher (forwarded to the client JVM).
 * Env: {@code CLEF_GAMEDIR}, {@code CLEF_MAX_HEAP} (768m), {@code CLEF_BG_THREADS} (4),
 * {@code CLEF_SKIP_SOUNDS} (true — we mute audio, so sound assets are skipped by default).
 */
public final class Launcher {

    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES_BASE = "https://resources.download.minecraft.net/";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String DEFAULT_MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String mcVersion;
    private final String loaderVersion;
    private final Path gameDir;
    private final Path libDir;
    private final Path assetsDir;
    private final boolean skipSounds;

    private Launcher(Properties versions) {
        this.mcVersion = versions.getProperty("minecraft");
        this.loaderVersion = versions.getProperty("loader");
        String dir = firstNonBlank(System.getProperty("clef.gamedir"),
                System.getProperty("mezzoclef.gamedir"),
                System.getenv("CLEF_GAMEDIR"),
                "mezzosopranoclef");
        this.gameDir = Path.of(dir).toAbsolutePath();
        this.libDir = gameDir.resolve(".cache");
        this.assetsDir = gameDir.resolve("assets");
        this.skipSounds = !"false".equalsIgnoreCase(System.getenv().getOrDefault("CLEF_SKIP_SOUNDS", "true"));
    }

    public static void main(String[] args) throws Exception {
        Properties versions = new Properties();
        try (InputStream in = Launcher.class.getResourceAsStream("/clef-launcher.properties")) {
            if (in == null) throw new IllegalStateException("clef-launcher.properties missing from jar");
            versions.load(in);
        }
        Launcher launcher = new Launcher(versions);
        launcher.run();
    }

    private void run() throws Exception {
        log("MezzoSopranoClef launcher — Minecraft " + mcVersion + ", Fabric loader " + loaderVersion);
        log("Game directory: " + gameDir);
        Files.createDirectories(libDir);
        Files.createDirectories(gameDir.resolve("mods"));

        extractBundledMods();

        // 1) Vanilla Minecraft (client jar + assets).
        JsonObject versionJson = resolveVersionJson();
        Path clientJar = downloadClientJar(versionJson);
        String assetIndexId = downloadAssets(versionJson);

        // 2) Merge libraries by group:artifact:classifier so Fabric's versions (e.g. ASM 9.10.1)
        //    override Minecraft's (ASM 9.6) — otherwise the loader aborts on "duplicate ASM classes".
        java.util.LinkedHashMap<String, LibSpec> libs = new java.util.LinkedHashMap<>();
        collectVanillaLibraries(versionJson, libs);
        JsonObject fabricProfile = fetchJson(FABRIC_META + mcVersion + "/" + loaderVersion + "/profile/json");
        collectFabricLibraries(fabricProfile, libs);
        String mainClass = fabricProfile.has("mainClass") && fabricProfile.get("mainClass").isJsonPrimitive()
                ? fabricProfile.get("mainClass").getAsString() : DEFAULT_MAIN_CLASS;

        List<Path> classpath = new ArrayList<>();
        classpath.add(clientJar);
        classpath.addAll(downloadLibraries(libs));

        // 3) Spawn the real client JVM.
        int rc = launchClient(classpath, mainClass, assetIndexId);
        System.exit(rc);
    }

    // ===== bundled mods ==============================================================

    private void extractBundledMods() throws IOException {
        Path mods = gameDir.resolve("mods");
        copyResource("/bundled/mezzosopranoclef.jar", mods.resolve("mezzosopranoclef.jar"));
        copyResource("/bundled/fabric-api.jar", mods.resolve("fabric-api.jar"));
        log("Installed bundled mods (mezzosopranoclef + fabric-api) into " + mods);
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = Launcher.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Bundled resource missing: " + resource);
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ===== vanilla download ==========================================================

    private JsonObject resolveVersionJson() throws IOException, InterruptedException {
        JsonObject manifest = fetchJson(VERSION_MANIFEST);
        String url = null;
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (mcVersion.equals(v.get("id").getAsString())) {
                url = v.get("url").getAsString();
                break;
            }
        }
        if (url == null) throw new IllegalStateException("Minecraft version not found in manifest: " + mcVersion);
        return fetchJson(url);
    }

    private Path downloadClientJar(JsonObject versionJson) throws IOException, InterruptedException {
        JsonObject client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        Path out = libDir.resolve("minecraft-" + mcVersion + "-client.jar");
        download(client.get("url").getAsString(), out, client.get("size").getAsLong(),
                client.get("sha1").getAsString());
        return out;
    }

    private record LibSpec(String url, String path, long size, String sha1) {}

    private void collectVanillaLibraries(JsonObject versionJson, Map<String, LibSpec> out) {
        for (JsonElement e : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (!rulesAllow(lib)) continue;
            if (!lib.has("downloads")) continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (!downloads.has("artifact")) continue;
            JsonObject art = downloads.getAsJsonObject("artifact");
            out.put(libKey(lib.get("name").getAsString()),
                    new LibSpec(art.get("url").getAsString(), art.get("path").getAsString(),
                            art.get("size").getAsLong(), art.get("sha1").getAsString()));
        }
    }

    private List<Path> downloadLibraries(Map<String, LibSpec> libs) throws Exception {
        List<Path> jars = new ArrayList<>();
        log("Downloading " + libs.size() + " libraries (Minecraft + Fabric, de-duplicated)…");
        for (LibSpec spec : libs.values()) {
            Path out = libDir.resolve("libraries").resolve(spec.path());
            download(spec.url(), out, spec.size(), spec.sha1());
            jars.add(out);
        }
        return jars;
    }

    /** group:artifact[:classifier] — version omitted so duplicates collapse; classifier kept so a
     *  library's main jar and its natives jar don't collide. */
    private static String libKey(String coord) {
        String[] p = coord.split(":");
        String key = p[0] + ":" + p[1];
        if (p.length > 3) key += ":" + p[3];
        return key;
    }

    /** Returns the asset index id (for {@code --assetIndex}). */
    private String downloadAssets(JsonObject versionJson) throws Exception {
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        String id = assetIndex.get("id").getAsString();
        Path indexFile = assetsDir.resolve("indexes").resolve(id + ".json");
        download(assetIndex.get("url").getAsString(), indexFile, assetIndex.get("size").getAsLong(),
                assetIndex.get("sha1").getAsString());

        JsonObject objects = JsonParser.parseString(Files.readString(indexFile))
                .getAsJsonObject().getAsJsonObject("objects");
        Path objectsDir = assetsDir.resolve("objects");

        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(objects.entrySet());
        AtomicInteger done = new AtomicInteger();
        int total = entries.size();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        int[] skipped = {0};
        log("Fetching assets (" + total + " objects" + (skipSounds ? ", skipping sounds" : "") + ")…");
        for (Map.Entry<String, JsonElement> entry : entries) {
            String logicalPath = entry.getKey();
            if (skipSounds && (logicalPath.startsWith("minecraft/sounds/") || logicalPath.endsWith(".ogg"))) {
                skipped[0]++;
                continue;
            }
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();
            long size = obj.get("size").getAsLong();
            String sub = hash.substring(0, 2);
            Path out = objectsDir.resolve(sub).resolve(hash);
            futures.add(pool.submit(() -> {
                try {
                    download(RESOURCES_BASE + sub + "/" + hash, out, size, hash);
                    int n = done.incrementAndGet();
                    if (n % 250 == 0) log("  assets: " + n + " downloaded");
                } catch (Exception ex) {
                    throw new RuntimeException("asset " + hash + ": " + ex.getMessage(), ex);
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        log("Assets ready (" + done.get() + " downloaded, " + skipped[0] + " sound objects skipped).");
        return id;
    }

    // ===== fabric download ===========================================================

    private void collectFabricLibraries(JsonObject profile, Map<String, LibSpec> out) {
        for (JsonElement e : profile.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            String name = lib.get("name").getAsString();           // group:artifact:version[:classifier]
            String base = lib.has("url") ? lib.get("url").getAsString() : "https://maven.fabricmc.net/";
            if (!base.endsWith("/")) base += "/";
            String path = mavenPath(name);
            out.put(libKey(name), new LibSpec(base + path, path, -1, null)); // Fabric overrides Minecraft
        }
    }

    // ===== launch ====================================================================

    private int launchClient(List<Path> classpath, String mainClass, String assetIndexId)
            throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        String cp = String.join(java.io.File.pathSeparator,
                classpath.stream().map(p -> p.toString()).toList());

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);

        // --- JVM tuning (mirrors runClient) ---
        String maxHeap = System.getenv().getOrDefault("CLEF_MAX_HEAP", "768m");
        String bgThreads = System.getenv().getOrDefault("CLEF_BG_THREADS", "4");
        cmd.add("-Xmx" + maxHeap);
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:MaxGCPauseMillis=50");
        cmd.add("-XX:+UseStringDeduplication");
        cmd.add("-XX:G1PeriodicGCInterval=15000");
        cmd.add("-XX:MaxMetaspaceSize=256m");
        cmd.add("-Dmax.bg.threads=" + bgThreads);
        cmd.add("-Djava.awt.headless=true");
        if (isMac()) cmd.add("-XstartOnFirstThread"); // GLFW must own the main thread on macOS

        // headless on by default; forward every mezzoclef.*/clef.* property we were given.
        if (System.getProperty("mezzoclef.headless") == null) cmd.add("-Dmezzoclef.headless=true");
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("mezzoclef.") || key.startsWith("clef.")) {
                cmd.add("-D" + key + "=" + System.getProperty(key));
            }
        }
        // CLEF_OPTS: space-separated extra JVM flags, handy in Docker
        // (e.g. CLEF_OPTS="-Dmezzoclef.ws.host=0.0.0.0 -Dmezzoclef.connect.auto=true").
        String opts = System.getenv("CLEF_OPTS");
        if (opts != null && !opts.isBlank()) {
            for (String tok : opts.trim().split("\\s+")) cmd.add(tok);
        }

        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(mainClass);

        // --- program args: a dummy offline session; the mod injects the real one (offline/MSA). ---
        cmd.add("--gameDir");          cmd.add(gameDir.toString());
        cmd.add("--assetsDir");        cmd.add(assetsDir.toString());
        cmd.add("--assetIndex");       cmd.add(assetIndexId);
        cmd.add("--version");          cmd.add(mcVersion);
        cmd.add("--accessToken");      cmd.add("0");
        cmd.add("--username");         cmd.add("Player");
        cmd.add("--uuid");             cmd.add("00000000-0000-0000-0000-000000000000");
        cmd.add("--userType");         cmd.add("legacy");
        cmd.add("--versionType");      cmd.add("release");

        log("Launching client (" + mainClass + ")…");
        Process proc = new ProcessBuilder(cmd)
                .directory(gameDir.toFile())
                .inheritIO()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(proc::destroy));
        return proc.waitFor();
    }

    // ===== helpers ===================================================================

    /** Mojang library rules: allowed unless rules say otherwise (we match on OS name only). */
    private boolean rulesAllow(JsonObject lib) {
        if (!lib.has("rules")) return true;
        boolean allowed = false;
        String os = currentOsName();
        for (JsonElement e : lib.getAsJsonArray("rules")) {
            JsonObject rule = e.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject ro = rule.getAsJsonObject("os");
                if (ro.has("name") && !ro.get("name").getAsString().equals(os)) matches = false;
            }
            if (matches) allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static String mavenPath(String coord) {
        // group:artifact:version[:classifier]
        String[] p = coord.split(":");
        String group = p[0].replace('.', '/');
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length > 3 ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private JsonObject fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(60)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** Download to {@code out} unless it already exists with the expected size/hash. */
    private void download(String url, Path out, long expectedSize, String expectedSha1)
            throws IOException, InterruptedException {
        String sha1 = expectedSha1 != null && !expectedSha1.isBlank() ? expectedSha1 : fetchSha1(url);
        if (Files.exists(out) && fileMatches(out, expectedSize, sha1)) return;
        Files.createDirectories(out.getParent());
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofMinutes(5)).build();
                Path tmp = out.resolveSibling(out.getFileName() + ".part");
                HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                if (!fileMatches(tmp, expectedSize, sha1)) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("checksum/size mismatch for " + url);
                }
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException ex) {
                last = ex;
                if (attempt < 3) Thread.sleep(1000L * attempt);
            }
        }
        throw new IOException("Failed to download " + url, last);
    }

    private static boolean fileMatches(Path file, long expectedSize, String expectedSha1) throws IOException {
        if (expectedSize >= 0 && Files.size(file) != expectedSize) return false;
        return expectedSha1 == null || expectedSha1.isBlank() || sha1(file).equalsIgnoreCase(expectedSha1);
    }

    private String fetchSha1(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + ".sha1"))
                .GET().timeout(Duration.ofSeconds(60)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("GET " + url + ".sha1 -> HTTP " + resp.statusCode());
        }
        String body = resp.body() == null ? "" : resp.body().trim();
        String hash = body.split("\\s+")[0];
        if (!hash.matches("(?i)[0-9a-f]{40}")) {
            throw new IOException("invalid SHA-1 from " + url + ".sha1");
        }
        return hash;
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static String currentOsName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    private static boolean isMac() { return currentOsName().equals("osx"); }

    private static boolean isWindows() { return currentOsName().equals("windows"); }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static void log(String msg) {
        System.out.println("[clef-launcher] " + msg);
    }
}
