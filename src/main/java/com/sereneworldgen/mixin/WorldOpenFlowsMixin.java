package com.sereneworldgen.mixin;

import com.mojang.serialization.Lifecycle;
import com.sereneworldgen.util.SerenePresetTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.WorldDataConfiguration;
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
        if (sereneworldgen$shouldBypassWarnings(screen)) {
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
    private static boolean sereneworldgen$shouldBypassWarnings(CreateWorldScreen screen) {
        if (screen == null) {
            return false;
        }
        try {
            WorldCreationUiState uiState = screen.getUiState();
            if (uiState == null) {
                return false;
            }

            WorldCreationContext settings = uiState.getSettings();
            if (settings == null) {
                return false;
            }

            // Do not bypass warnings if user explicitly enabled separate experimental feature flags
            WorldDataConfiguration dataConfig = settings.dataConfiguration();
            if (!SerenePresetTracker.hasNoExternalExperimentals(dataConfig)) {
                return false;
            }

            // Check if selected preset is Serene Ocean
            WorldCreationUiState.WorldTypeEntry worldType = uiState.getWorldType();
            if (worldType != null && worldType.preset() != null) {
                var presetKeyOpt = worldType.preset().unwrapKey();
                if (presetKeyOpt.isPresent() && SerenePresetTracker.MOD_ID.equals(presetKeyOpt.get().location().getNamespace())) {
                    return true;
                }
            }

            // Check if overworld generator uses Serene Ocean settings
            if (settings.selectedDimensions() != null) {
                ChunkGenerator generator = settings.selectedDimensions().overworld();
                if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
                    var settingsKeyOpt = noiseGen.generatorSettings().unwrapKey();
                    if (settingsKeyOpt.isPresent() && SerenePresetTracker.MOD_ID.equals(settingsKeyOpt.get().location().getNamespace())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
