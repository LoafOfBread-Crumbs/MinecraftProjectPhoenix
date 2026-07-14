package com.modmigrator.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MappingFetcher {

    private static final String MOJANG_VERSION_MANIFEST =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_META_VERSIONS =
        "https://meta.fabricmc.net/v2/versions/game";
    private static final String FABRIC_INTERMEDIARY_BASE =
        "https://maven.fabricmc.net/net/fabricmc/intermediary/%s/intermediary-%s-v2.jar";
    private static final String NEOFORGE_MCP_CONFIG_MAVEN =
        "https://maven.neoforged.net/releases/de/oceanlabs/mcp/mcp_config";

    private final OkHttpClient client;
    private final Path cacheDir;

    public MappingFetcher(Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        Files.createDirectories(cacheDir);
        this.client = buildHttpClient();
    }

    private static OkHttpClient buildHttpClient() {
        try {
            KeyStore ks = KeyStore.getInstance("Windows-ROOT");
            ks.load(null, null);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            X509TrustManager tm = (X509TrustManager) tmf.getTrustManagers()[0];
            return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.getSocketFactory(), tm)
                .build();
        } catch (Exception e) {
            System.err.println("[WARN] Could not load Windows truststore, using default SSL: " + e.getMessage());
            return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        }
    }

    public List<String> fetchAvailableMinecraftVersions() throws IOException {
        List<String> versions = new ArrayList<>();

        String json = fetchString(MOJANG_VERSION_MANIFEST);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray versionArray = root.getAsJsonArray("versions");

        for (JsonElement elem : versionArray) {
            JsonObject ver = elem.getAsJsonObject();
            String type = ver.get("type").getAsString();
            String id = ver.get("id").getAsString();
            if ("release".equals(type)) {
                versions.add(id);
            }
        }

        return versions;
    }

    public Path fetchMojangMappings(String minecraftVersion, Consumer<String> statusCallback) throws IOException {
        Path mappingsFile = cacheDir.resolve("mojang-mappings-" + minecraftVersion + ".txt");
        if (Files.exists(mappingsFile)) {
            if (statusCallback != null) statusCallback.accept("Using cached Mojang mappings for " + minecraftVersion);
            return mappingsFile;
        }

        if (statusCallback != null) statusCallback.accept("Fetching Mojang version manifest...");
        String manifestJson = fetchString(MOJANG_VERSION_MANIFEST);
        JsonObject root = JsonParser.parseString(manifestJson).getAsJsonObject();
        JsonArray versions = root.getAsJsonArray("versions");

        String versionUrl = null;
        for (JsonElement elem : versions) {
            JsonObject ver = elem.getAsJsonObject();
            if (minecraftVersion.equals(ver.get("id").getAsString())) {
                versionUrl = ver.get("url").getAsString();
                break;
            }
        }

        if (versionUrl == null) {
            throw new IOException("Minecraft version '" + minecraftVersion + "' not found in Mojang manifest");
        }

        if (statusCallback != null) statusCallback.accept("Fetching version metadata for " + minecraftVersion + "...");
        String versionJson = fetchString(versionUrl);
        JsonObject versionData = JsonParser.parseString(versionJson).getAsJsonObject();

        JsonObject downloads = versionData.getAsJsonObject("downloads");
        if (!downloads.has("client_mappings")) {
            throw new IOException("No client mappings available for Minecraft " + minecraftVersion);
        }

        String mappingsUrl = downloads.getAsJsonObject("client_mappings").get("url").getAsString();
        if (statusCallback != null) statusCallback.accept("Downloading Mojang mappings for " + minecraftVersion + "...");
        downloadFile(mappingsUrl, mappingsFile);

        return mappingsFile;
    }

    public Path fetchFabricIntermediaryMappings(String minecraftVersion, Consumer<String> statusCallback) throws IOException {
        Path mappingsJar = cacheDir.resolve("fabric-intermediary-" + minecraftVersion + ".jar");
        if (Files.exists(mappingsJar)) {
            if (statusCallback != null) statusCallback.accept("Using cached Fabric intermediary mappings for " + minecraftVersion);
            return mappingsJar;
        }

        String url = String.format(FABRIC_INTERMEDIARY_BASE, minecraftVersion, minecraftVersion);
        if (statusCallback != null) statusCallback.accept("Downloading Fabric intermediary mappings for " + minecraftVersion + "...");

        try {
            downloadFile(url, mappingsJar);
            return mappingsJar;
        } catch (IOException e) {
            throw new IOException("Fabric intermediary mappings not available for " + minecraftVersion, e);
        }
    }

    /**
     * Fetches Forge/NeoForge SRG mappings for the given Minecraft version.
     * Downloads the MCPConfig archive from NeoForged's Maven and converts the
     * contained joined.tsrg file into a Tiny2 mapping file for use by the
     * MixinAnalyzer.
     */
    public Path fetchForgeSrgMappings(String minecraftVersion, Consumer<String> statusCallback) throws IOException {
        Path tinyFile = cacheDir.resolve("forge-srg-" + minecraftVersion + ".tiny");
        if (Files.exists(tinyFile)) {
            if (statusCallback != null) statusCallback.accept("Using cached Forge SRG mappings for " + minecraftVersion);
            return tinyFile;
        }

        String version = findMcpConfigVersion(minecraftVersion);
        if (version == null) {
            throw new IOException("MCPConfig version not found for Minecraft " + minecraftVersion);
        }

        Path zipFile = cacheDir.resolve("mcp_config-" + version + ".zip");
        String url = String.format("%s/%s/mcp_config-%s.zip", NEOFORGE_MCP_CONFIG_MAVEN, version, version);
        if (statusCallback != null) statusCallback.accept("Downloading Forge MCPConfig for " + minecraftVersion + "...");
        downloadFile(url, zipFile);

        Path extractedTsrg = cacheDir.resolve("joined-" + version + ".tsrg");
        extractJoinedTsrg(zipFile, extractedTsrg);

        if (statusCallback != null) statusCallback.accept("Converting Forge SRG mappings to Tiny2...");
        try {
            MemoryMappingTree tree = new MemoryMappingTree();
            MappingReader.read(extractedTsrg, tree);
            tree.accept(MappingWriter.create(tinyFile, MappingFormat.TINY_2_FILE));
            return tinyFile;
        } catch (Exception e) {
            throw new IOException("Failed to convert Forge SRG mappings to Tiny2 for " + minecraftVersion, e);
        }
    }

    private String findMcpConfigVersion(String minecraftVersion) throws IOException {
        String metaUrl = NEOFORGE_MCP_CONFIG_MAVEN + "/maven-metadata.xml";
        String xml = fetchString(metaUrl);
        // Extract the latest version whose artifact version starts with "minecraftVersion-"
        String prefix = minecraftVersion + "-";
        String latest = null;
        for (String line : xml.split("\n")) {
            line = line.trim();
            if (line.startsWith("<version>")) {
                String ver = line.substring(9, line.indexOf("</version>"));
                if (ver.startsWith(prefix)) {
                    if (latest == null || ver.compareTo(latest) > 0) {
                        latest = ver;
                    }
                }
            }
        }
        return latest;
    }

    private void extractJoinedTsrg(Path zipFile, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry("config/joined.tsrg");
            if (entry == null) {
                throw new IOException("config/joined.tsrg not found in MCPConfig archive");
            }
            try (InputStream is = zip.getInputStream(entry)) {
                Files.copy(is, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public List<String> fetchFabricSupportedVersions() throws IOException {
        List<String> versions = new ArrayList<>();
        String json = fetchString(FABRIC_META_VERSIONS);
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement elem : array) {
            JsonObject ver = elem.getAsJsonObject();
            if (ver.has("stable") && ver.get("stable").getAsBoolean()) {
                versions.add(ver.get("version").getAsString());
            }
        }
        return versions;
    }

    private String fetchString(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " fetching: " + url);
            }
            return response.body().string();
        }
    }

    private void downloadFile(String url, Path destination) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " downloading: " + url);
            }
            try (InputStream is = response.body().byteStream()) {
                Files.copy(is, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
