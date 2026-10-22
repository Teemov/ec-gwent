package io.github.teemov.ecgwent.server;

import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Card book requests from players. */
public final class ServerHooks {
    private ServerHooks() {}

    public static void syncCards(ServerPlayer p) {
        Net.toPlayer(p, new Net.SyncCards(GwentData.cardJson()));
    }

    public static void sendBook(ServerPlayer p, boolean open) {
        if (p == null) return;
        if (open && Matches.reopen(p)) return; // K during a match brings the board back
        Net.toPlayer(p, new Net.BookData(Collection.get(p).copy(), open));
    }

    public static void pickStarter(ServerPlayer p, String faction) {
        if (p == null || !CardRegistry.PLAYABLE_FACTIONS.contains(faction) || Collection.started(p)) return;
        Collection.start(p, GwentData.cards(), faction);
        p.sendSystemMessage(Component.literal("You received the " + GwentData.cards().factionName(faction)
                + " starter deck. Win matches to collect more cards.").withStyle(ChatFormatting.GOLD));
        sendBook(p, true);
    }

    public static void saveDeck(ServerPlayer p, String faction, String leader, List<String> cards) {
        if (p == null || !Collection.started(p)) return;
        Deck d = new Deck(faction, leader, cards);
        String err = d.validate(GwentData.cards(), Collection.owned(p));
        if (err != null) {
            p.sendSystemMessage(Component.literal("Deck not saved: " + err).withStyle(ChatFormatting.RED));
            return;
        }
        Collection.saveDeck(p, d);
        Collection.setActive(p, faction);
        p.sendSystemMessage(Component.literal("Deck saved. You now play " + GwentData.cards().factionName(faction) + ".")
                .withStyle(ChatFormatting.GREEN));
        sendBook(p, false);
    }
}
