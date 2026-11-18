package com.sereneworldgen.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Inject(
            method = "confirmWorldCreation(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;Lcom/mojang/serialization/Lifecycle;Ljava/lang/Runnable;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void sereneworldgen$bypassExperimentalWarningForSerenePreset(
            Minecraft minecraft,
            CreateWorldScreen screen,
            Lifecycle lifecycle,
            Runnable onConfirm,
            boolean skipWarnings,
            CallbackInfo ci
    ) {
        if (sereneworldgen$isSerenePreset(screen)) {
            try {
                SerenePresetTracker.setCreatingSerene(true);
                onConfirm.run();
                ci.cancel();
            } finally {
                SerenePresetTracker.setCreatingSerene(false);
            }
        }
    }

    @Unique
    private static boolean sereneworldgen$isSerenePreset(CreateWorldScreen screen) {
        if (screen == null) {
            return false;
        }
        try {
            WorldCreationUiState uiState = screen.getUiState();
            if (uiState != null) {
                WorldCreationUiState.WorldTypeEntry worldType = uiState.getWorldType();
                if (worldType != null && worldType.preset() != null) {
                    var presetKeyOpt = worldType.preset().unwrapKey();
                    if (presetKeyOpt.isPresent() && SerenePresetTracker.MOD_ID.equals(presetKeyOpt.get().location().getNamespace())) {
                        return true;
                    }
                }
                WorldCreationContext settings = uiState.getSettings();
                if (settings != null && settings.selectedDimensions() != null) {
                    ChunkGenerator generator = settings.selectedDimensions().overworld();
                    if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
                        var settingsKeyOpt = noiseGen.generatorSettings().unwrapKey();
                        if (settingsKeyOpt.isPresent() && SerenePresetTracker.MOD_ID.equals(settingsKeyOpt.get().location().getNamespace())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
