package com.sereneworldgen.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @ModifyVariable(
            method = "confirmWorldCreation(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;Lcom/mojang/serialization/Lifecycle;Ljava/lang/Runnable;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private static Lifecycle sereneworldgen$forceStableLifecycle(Lifecycle lifecycle) {
        return Lifecycle.stable();
    }

    @ModifyVariable(
            method = "confirmWorldCreation(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;Lcom/mojang/serialization/Lifecycle;Ljava/lang/Runnable;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private static boolean sereneworldgen$forceSkipWarnings(boolean skipWarnings) {
        return true;
    }
}
