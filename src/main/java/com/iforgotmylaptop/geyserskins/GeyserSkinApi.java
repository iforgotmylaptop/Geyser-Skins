package com.iforgotmylaptop.geyserskins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class GeyserSkinApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("geyser-skins");
    private static final String API = "https://api.geysermc.org";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<UUID, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<?>> LOOKUPS = new ConcurrentHashMap<>();

    private static volatile String prefix = ".";

    private GeyserSkinApi() {}

    public static void init() {
        // Default Floodgate prefix is ".".
        // If your server uses another prefix, set it in:
        // config/geyser-skins.properties
        loadConfig();
    }

    public static PlayerSkin get(UUID uuid) {
        return SKINS.get(uuid);
    }

    public static void request(UUID uuid, String username) {
        if (SKINS.containsKey(uuid) || LOOKUPS.containsKey(uuid)) {
            return;
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                String encodedUser = java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8);
                String encodedPrefix = java.net.URLEncoder.encode(prefix, java.nio.charset.StandardCharsets.UTF_8);

                // This endpoint returns a Floodgate UUID for a Bedrock username.
                // If it is a Java player, Geyser redirects to Mojang instead.
                String profileUrl = API + "/v2/utils/uuid/bedrock_or_java/" + encodedUser + "?prefix=" + encodedPrefix;

                HttpResponse<String> profileResponse = get(profileUrl);
                if (profileResponse.statusCode() != 200) {
                    return;
                }

                JsonObject profile = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
                String floodgateUuid = profile.get("id").getAsString();
                if (!sameUuid(uuid, floodgateUuid)) {
                    // The visible Java profile does not map to the returned Bedrock profile.
                    return;
                }

                long xuid = xuidFromFloodgateUuid(floodgateUuid);
                String skinUrl = API + "/v2/skin/" + xuid;

                HttpResponse<String> skinResponse = get(skinUrl);
                if (skinResponse.statusCode() != 200) {
                    return;
                }

                JsonObject skin = JsonParser.parseString(skinResponse.body()).getAsJsonObject();
                if (!skin.has("texture_id") || skin.get("texture_id").isJsonNull()) {
                    return;
                }

                String textureId = skin.get("texture_id").getAsString();
                boolean steve = skin.has("is_steve") && skin.get("is_steve").getAsBoolean();

                // Geyser's raw renderer endpoint serves the converted PNG.
                String textureUrl = API + "/render/raw/" + textureId;

                // Unique client-side texture location. The actual PNG is downloaded by
                // Minecraft's normal skin texture machinery.
                var id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "geyser_skins", "bedrock/" + uuid.toString().replace("-", ""));

                ClientAsset.Texture body = new ClientAsset.DownloadedTexture(id, textureUrl);
                PlayerModelType model = steve ? PlayerModelType.WIDE : PlayerModelType.WIDE;

                // Geyser's is_steve field is about the converted model fallback.
                // The converted Bedrock skin itself may be slim; until Geyser exposes
                // that geometry through this endpoint, WIDE is the safe default.
                PlayerSkin custom = PlayerSkin.insecure(body, null, null, model);
                SKINS.put(uuid, custom);

                LOGGER.info("Loaded Geyser skin for {}", username);
            } catch (Exception e) {
                LOGGER.debug("Could not load Geyser skin for {}: {}", username, e.getMessage());
            } finally {
                LOOKUPS.remove(uuid);
            }
        });

        LOOKUPS.put(uuid, future);
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "GeyserSkins/1.0.0 (Minecraft Fabric)")
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean sameUuid(UUID javaUuid, String floodgateUuid) {
        String a = javaUuid.toString().replace("-", "").toLowerCase();
        String b = floodgateUuid.replace("-", "").toLowerCase();
        return a.equals(b);
    }

    private static long xuidFromFloodgateUuid(String uuid) {
        String hex = uuid.replace("-", "");
        // Floodgate UUIDs encode the Bedrock XUID in their final 14 hex digits.
        return Long.parseUnsignedLong(hex.substring(hex.length() - 14), 16);
    }

    private static void loadConfig() {
        try {
            Path file = Path.of("config", "geyser-skins.properties");
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "prefix=.\\n");
                return;
            }

            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("prefix=")) {
                    String value = line.substring("prefix=".length()).trim();
                    if (!value.isEmpty()) {
                        prefix = value;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read geyser-skins.properties; using prefix '.'", e);
        }
    }
}