package com.iforgotmylaptop.geyserskins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class GeyserSkinApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("geyser-skins");
    private static final String API = "https://api.geysermc.org";

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private static final Map<UUID, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<?>> LOOKUPS = new ConcurrentHashMap<>();

    private static volatile String prefix = ".";

    private GeyserSkinApi() {}

    public static void init() {
        loadConfig();
        LOGGER.info("Geyser skin API initialized with prefix '{}'", prefix);
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
                String encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8);
                String encodedPrefix = URLEncoder.encode(prefix, StandardCharsets.UTF_8);

                String profileUrl = API + "/v2/utils/uuid/bedrock_or_java/" + encodedUser + "?prefix=" + encodedPrefix;

                LOGGER.info("Looking up Geyser UUID for {}", username);

                HttpResponse<String> profileResponse = get(profileUrl);

                LOGGER.info("Geyser UUID response for {}: status={}", username, profileResponse.statusCode());

                if (profileResponse.statusCode() != 200) {
                    LOGGER.warn("Geyser UUID lookup failed for {}: {}", username, profileResponse.body());
                    return;
                }

                JsonObject profile = JsonParser.parseString(profileResponse.body()).getAsJsonObject();

                if (!profile.has("id") || profile.get("id").isJsonNull()) {
                    LOGGER.warn("Geyser UUID response for {} has no id", username);
                    return;
                }

                String floodgateUuid = profile.get("id").getAsString();

                LOGGER.info("Geyser UUID for {} = {}", username, floodgateUuid);

                if (!sameUuid(uuid, floodgateUuid)) {
                    LOGGER.info("UUID mismatch for {}. Player UUID={}, Geyser UUID={}",username, uuid, floodgateUuid);
                    return;
                }

                long xuid = xuidFromFloodgateUuid(floodgateUuid);

                LOGGER.info("XUID for {} = {}", username, Long.toUnsignedString(xuid));

                String skinUrl = API + "/v2/skin/" + xuid;

                LOGGER.info("Requesting Geyser skin for {}", username);

                HttpResponse<String> skinResponse = get(skinUrl);

                LOGGER.info("Geyser skin response for {}: status={}", username, skinResponse.statusCode());

                if (skinResponse.statusCode() != 200) {
                    LOGGER.warn("Geyser skin lookup failed for {}: {}", username, skinResponse.body());
                    return;
                }

                JsonObject skin = JsonParser
                        .parseString(skinResponse.body())
                        .getAsJsonObject();

                if (!skin.has("texture_id") || skin.get("texture_id").isJsonNull()) {
                    LOGGER.warn("Geyser returned no texture_id for {}", username);
                    return;
                }

                String textureId = skin.get("texture_id").getAsString();

                boolean steve = skin.has("is_steve") && skin.get("is_steve").getAsBoolean();

                String textureUrl = API + "/render/raw/" + textureId;

                LOGGER.info("Geyser texture for {}: id={}, isSteve={}, url={}", username, textureId, steve, textureUrl);

                Identifier id = Identifier.fromNamespaceAndPath("geyser_skins","bedrock/" + uuid.toString().replace("-", ""));

                ClientAsset.Texture texture = new ClientAsset.DownloadedTexture(id, textureUrl);

                PlayerModelType model = steve ? PlayerModelType.WIDE : PlayerModelType.SLIM;

                PlayerSkin custom = PlayerSkin.insecure(texture, null, null, model);

                SKINS.put(uuid, custom);
                LOGGER.info("Loaded Geyser skin for {} (model={})",username,model);

            } catch (Exception e) {
                LOGGER.error("Could not load Geyser skin for {}",username, e);
            } finally {
                LOOKUPS.remove(uuid);
            }
        });

        LOOKUPS.put(uuid, future);
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent","GeyserSkins/1.0.0 (Minecraft Fabric)")
                .GET()
                .build();

        return HTTP.send(request,HttpResponse.BodyHandlers.ofString());
    }

    private static boolean sameUuid(UUID javaUuid, String floodgateUuid) {
        String a = javaUuid.toString().replace("-", "").toLowerCase();
        String b = floodgateUuid.replace("-", "").toLowerCase();

        return a.equals(b);
    }

    private static long xuidFromFloodgateUuid(String uuid) {
        String hex = uuid.replace("-", "");

        return Long.parseUnsignedLong(hex.substring(hex.length() - 14), 16);
    }

    private static void loadConfig() {
        try {
            Path file = Path.of("config", "geyser-skins.properties"
            );

            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "prefix=.\n");
                prefix = ".";
                return;
            }

            for (String line : Files.readAllLines(file)) {
                line = line.trim();

                if (line.startsWith("prefix=")) {
                    String value = line.substring("prefix=".length()).trim();

                    if (!value.isEmpty()) {
                        prefix = value;
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.warn("Could not read geyser-skins.properties; using prefix '.'", e);
            prefix = ".";
        }
    }
}
