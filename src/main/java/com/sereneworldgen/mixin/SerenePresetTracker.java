package com.sereneworldgen.mixin;

import com.mojang.serialization.Dynamic;

import java.util.Optional;

public final class SerenePresetTracker {
    public static final String MOD_ID = "sereneworldgen";
    public static final String ENDLESS_OCEAN = "endless_ocean";

    private static final ThreadLocal<Boolean> CREATING_SERENE = ThreadLocal.withInitial(() -> false);

    private SerenePresetTracker() {}

    public static void setCreatingSerene(boolean creating) {
        CREATING_SERENE.set(creating);
    }

    public static boolean isCreatingSerene() {
        return CREATING_SERENE.get();
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
            if (settingsStr.isPresent() && settingsStr.get().contains(MOD_ID)) {
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
            if (presetStr.isPresent() && presetStr.get().contains(MOD_ID)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}

