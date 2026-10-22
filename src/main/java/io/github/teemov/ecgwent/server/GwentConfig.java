package io.github.teemov.ecgwent.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world settings, changed with {@code /gwent config}. */
public final class GwentConfig extends SavedData {
    private static final String NAME = "ec_gwent_config";
    /** "emeralds" or "cards": what winning against a villager gives on top of the returned bet. */
    public String villagerReward = "emeralds";

    public static GwentConfig get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(GwentConfig::load, GwentConfig::new, NAME);
    }

    private static GwentConfig load(CompoundTag tag) {
        GwentConfig c = new GwentConfig();
        if (tag.contains("villagerReward")) c.villagerReward = tag.getString("villagerReward");
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString("villagerReward", villagerReward);
        return tag;
    }
}
