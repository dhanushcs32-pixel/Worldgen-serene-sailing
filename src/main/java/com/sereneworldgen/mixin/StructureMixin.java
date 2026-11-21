package com.sereneworldgen.mixin;

import com.sereneworldgen.util.StructureDryLandGuard;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Structure.class)
public abstract class StructureMixin {

    @Inject(
            method = "findValidGenerationPoint(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;)Ljava/util/Optional;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void sereneworldgen$rejectFloatingOceanStructures(
            Structure.GenerationContext context,
            CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir
    ) {
        Optional<Structure.GenerationStub> result = cir.getReturnValue();
        if (result == null || result.isEmpty()) {
            return;
        }
        if (!StructureDryLandGuard.isValidPlacement((Structure) (Object) this, context, result.get())) {
            cir.setReturnValue(Optional.empty());
        }
    }
}

