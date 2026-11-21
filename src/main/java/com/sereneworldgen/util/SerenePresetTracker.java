package com.sereneworldgen.util;

import com.mojang.serialization.Dynamic;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldDataConfiguration;

import java.util.Optional;

public final class SerenePresetTracker {
    public static final String MOD_ID = "sereneworldgen";

    private static final ThreadLocal<Boolean> CREATING_SERENE = ThreadLocal.withInitial(() -> false);

    private SerenePresetTracker() {}

    public static void setCreatingSerene(boolean creating) {
        if (creating) {
            CREATING_SERENE.set(true);
        } else {
            CREATING_SERENE.remove();
        }
    }

    public static boolean isCreatingSerene() {
        return CREATING_SERENE.get();
    }

    public static boolean hasNoExternalExperimentals(WorldDataConfiguration dataConfig) {
        if (dataConfig == null) {
            return true;
        }
        if (FeatureFlags.isExperimental(dataConfig.enabledFeatures())) {
            return false;
        }
        for (String packId : dataConfig.dataPacks().getEnabled()) {
            if (packId.startsWith("file/")) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSereneDynamic(Dynamic<?> dynamic) {
        if (dynamic == null) {
            return false;
        }
        try {
            Optional<String> settingsStr = dynamic.get("WorldGenSettings")
                    .get("dimensions")
                    .get("minecraft:overworld")
                    .get("generator")
                    .get("settings")
                    .asString()
                    .result();
            if (settingsStr.isPresent() && settingsStr.get().startsWith(MOD_ID + ":")) {
                return true;
            }
            Optional<String> presetStr = dynamic.get("WorldGenSettings")
                    .get("dimensions")
                    .get("minecraft:overworld")
                    .get("generator")
                    .get("biome_source")
                    .get("preset")
                    .asString()
                    .result();
            if (presetStr.isPresent() && presetStr.get().startsWith(MOD_ID + ":")) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}

