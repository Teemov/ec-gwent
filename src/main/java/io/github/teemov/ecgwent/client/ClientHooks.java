package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Map;

/** Client-side state and packet handlers. Only ever loaded on the client. */
public final class ClientHooks {
    public static CardRegistry cards = new CardRegistry();
    public static CompoundTag book = new CompoundTag();
    public static Map<String, Object> match;
    /** The player closed the board mid-match; updates don't reopen it until K is pressed. */
    public static boolean matchHidden;
    /** The start-of-match coin toss has been shown for the current match. */
    public static boolean coinDone;

    private ClientHooks() {}

    public static void syncCards(List<String> files) {
        CardRegistry reg = new CardRegistry();
        for (String f : files) {
            try {
                reg.load(f);
            } catch (RuntimeException e) {
                EcGwent.LOGGER.error("Bad card data from server: {}", e.toString());
            }
        }
        cards = reg;
        CardArt.clearCache();
    }

    public static void bookData(CompoundTag data, boolean open) {
        book = data == null ? new CompoundTag() : data;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BookScreen s) s.refresh();
        else if (open) mc.setScreen(new BookScreen());
    }

    public static boolean matchRunning() { return match != null && !match.containsKey("winner"); }

    @SuppressWarnings("unchecked")
    public static void matchView(String json) {
        boolean fresh = match == null || match.containsKey("winner");
        match = (Map<String, Object>) Json.parse(json);
        if (fresh) { matchHidden = false; coinDone = false; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MatchScreen s) s.refresh();
        else if (!matchHidden || match.containsKey("winner")) mc.setScreen(new MatchScreen());
    }

    /** K pressed: back to a running match, or the card book. Returns true if it handled it locally. */
    public static boolean openMatchIfRunning() {
        if (!matchRunning()) return false;
        matchHidden = false;
        Minecraft.getInstance().setScreen(new MatchScreen());
        return true;
    }

    public static void duelOffer(java.util.UUID from, String name, int bet) {
        Minecraft mc = Minecraft.getInstance();
        if (matchRunning()) return;
        mc.setScreen(new DuelScreen(from, name, bet));
    }

    public static void openChallenge(java.util.UUID target, String name) {
        if (matchRunning()) return;
        Minecraft.getInstance().setScreen(new DuelScreen(target, name, -1));
    }

    public static void villagerOffer(int entityId, String name, int level) {
        Minecraft.getInstance().setScreen(new VillagerScreen(entityId, name, level));
    }
}
