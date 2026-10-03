package com.iforgotmylaptop.geyserskins.mixin;

import com.iforgotmylaptop.geyserskins.GeyserSkinApi;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

@Mixin(SkinManager.class)
public abstract class SkinManagerMixin {
    @Inject(method = "createLookup", at = @At("RETURN"), cancellable = true)
    private void geyserSkins$wrapLookup(
            GameProfile profile,
            boolean requireSecure,
            CallbackInfoReturnable<Supplier<PlayerSkin>> cir
    ) {
        Supplier<PlayerSkin> vanilla = cir.getReturnValue();

        cir.setReturnValue(() -> {
            PlayerSkin custom = GeyserSkinApi.get(profile.id());
            if (custom == null) {
                GeyserSkinApi.request(profile.id(), profile.name());
                return vanilla.get();
            }
            return custom;
        });
    }
}