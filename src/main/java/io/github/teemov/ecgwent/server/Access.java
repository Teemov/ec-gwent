package io.github.teemov.ecgwent.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Gwent is unlocked per player by the storyline (/gwent unlock). Until then the mod stays out of the way. */
public final class Access {
    private Access() {}

    public static boolean allowed(ServerPlayer p) {
        if (p == null) return false;
        return !GwentConfig.get(p.server).requireUnlock || Collection.unlocked(p);
    }

    /** For command visibility: operators always see the commands, players only once unlocked. */
    public static boolean allowed(CommandSourceStack s) {
        if (s.hasPermission(2)) return true;
        ServerPlayer p = s.getPlayer();
        return p != null && allowed(p);
    }

    /** Resends the command list so the /gwent commands appear or disappear for this player. */
    public static void refreshCommands(ServerPlayer p) {
        p.server.getCommands().sendCommands(p);
    }
}
