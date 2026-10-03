package com.iforgotmylaptop.geyserskins;

import net.fabricmc.api.ClientModInitializer;

public final class GeyserSkinsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GeyserSkinApi.init();
    }
}