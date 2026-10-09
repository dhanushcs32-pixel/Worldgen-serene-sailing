package com.sereneworldgen;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(SereneOceanWorldgen.MOD_ID)
public class SereneOceanWorldgen {
    public static final String MOD_ID = "sereneworldgen";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SereneOceanWorldgen(IEventBus modEventBus) {
        LOGGER.info("[SereneOceanWorldgen] Initializing Oceanic Worldgen Presets.");
    }
}
