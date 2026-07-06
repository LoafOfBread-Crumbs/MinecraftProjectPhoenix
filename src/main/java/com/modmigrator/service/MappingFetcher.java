package com.modmigrator.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MappingFetcher {

    private static final String MOJANG_VERSION_MANIFEST =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_META_VERSIONS =
        "https://meta.fabricmc.net/v2/versions/game";
    private static final String FABRIC_INTERMEDIARY_BASE =
        "https://maven.fabricmc.net/net/fabricmc/intermediary/%s/intermediary-%s-v2.jar";

    private final OkHttpClient client;
    private final Path cacheDir;

    public MappingFetcher(Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        Files.createDirectories(cacheDir);
        this.client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
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
