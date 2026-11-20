package com.sereneworldgen.mixin;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import com.sereneworldgen.util.SereneLevelDataAccess;
import com.sereneworldgen.util.SerenePresetTracker;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PrimaryLevelData.class)
public class PrimaryLevelDataMixin implements SereneLevelDataAccess {

    @Unique
    private boolean sereneworldgen$isSereneWorld;

    @Override
    public boolean sereneworldgen$isSereneWorld() {
        return this.sereneworldgen$isSereneWorld;
    }

    @Override
    public void sereneworldgen$setSereneWorld(boolean value) {
        this.sereneworldgen$isSereneWorld = value;
    }

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/world/level/storage/PrimaryLevelData$SpecialWorldProperty;Lcom/mojang/serialization/Lifecycle;)V",
            at = @At("RETURN")
    )
    private void sereneworldgen$onNew(LevelSettings settings, WorldOptions options, PrimaryLevelData.SpecialWorldProperty prop, Lifecycle lifecycle, CallbackInfo ci) {
        if (SerenePresetTracker.isCreatingSerene()) {
            this.sereneworldgen$isSereneWorld = true;
        }
    }

    @Inject(
            method = "parse(Lcom/mojang/serialization/Dynamic;Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/world/level/storage/PrimaryLevelData$SpecialWorldProperty;Lnet/minecraft/world/level/levelgen/WorldOptions;Lcom/mojang/serialization/Lifecycle;)Lnet/minecraft/world/level/storage/PrimaryLevelData;",
            at = @At("RETURN")
    )
    private static void sereneworldgen$onParse(Dynamic<?> dynamic, LevelSettings settings, PrimaryLevelData.SpecialWorldProperty prop, WorldOptions options, Lifecycle lifecycle, CallbackInfoReturnable<PrimaryLevelData> cir) {
        PrimaryLevelData data = cir.getReturnValue();
        if (data instanceof SereneLevelDataAccess access
                && SerenePresetTracker.isSereneDynamic(dynamic)
                && SerenePresetTracker.hasNoExternalExperimentals(settings.getDataConfiguration())) {
            access.sereneworldgen$setSereneWorld(true);
        }
    }

    @Inject(method = "worldGenSettingsLifecycle", at = @At("HEAD"), cancellable = true)
    private void sereneworldgen$forceStableWorldGenSettingsLifecycle(CallbackInfoReturnable<Lifecycle> cir) {
        if (this.sereneworldgen$isSereneWorld) {
            cir.setReturnValue(Lifecycle.stable());
        }
    }
}
