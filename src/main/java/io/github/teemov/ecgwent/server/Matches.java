package io.github.teemov.ecgwent.server;

import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.Ai;
import io.github.teemov.ecgwent.engine.Card;
import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.engine.Decks;
import io.github.teemov.ecgwent.engine.Game;
import io.github.teemov.ecgwent.engine.Row;
import io.github.teemov.ecgwent.engine.View;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Running matches, one per player. The player is always side 0, the NPC or villager side 1. */
public final class Matches {
    public static final int BET = 2;
    /** Ticks between AI moves, so players can follow what happens. */
    private static final int AI_DELAY = 30;

    private static final Map<UUID, Match> matches = new HashMap<>();
    private static final Random rng = new Random();

    private Matches() {}

    enum Kind { NPC, VILLAGER }

    static final class Match {
        final UUID player;
        final Game game;
        final Kind kind;
        final String opponentId;
        final String opponentEntity;
        /** The villager or NPC being played against; protected from damage while the match runs. */
        UUID opponentUuid;
        final String reward;
        final Deck opponentDeck;
        int aiWait = AI_DELAY;
        boolean finished;
        String result;
        int closeTimer = -1;

        Match(UUID player, Game game, Kind kind, String opponentId, String opponentEntity, String reward, Deck opponentDeck) {
            this.player = player;
            this.game = game;
            this.kind = kind;
            this.opponentId = opponentId;
            this.opponentEntity = opponentEntity;
            this.reward = reward;
            this.opponentDeck = opponentDeck;
        }
    }

    /** Players in a running match and their opponents take no damage (except /kill and the void). */
    public static boolean isProtected(Entity e) {
        if (matches.isEmpty()) return false;
        Match own = matches.get(e.getUUID());
        if (own != null && !own.finished) return true;
        for (Match m : matches.values()) if (!m.finished && e.getUUID().equals(m.opponentUuid)) return true;
        return false;
    }

    public static boolean inMatch(ServerPlayer p) {
        Match m = matches.get(p.getUUID());
        return m != null && !m.finished;
    }

    // ------------------------------------------------------------------ starting matches

    /** /gwent challenge: a story NPC opponent. */
    public static String challengeNpc(ServerPlayer p, String opponentId) {
        GwentData.Opponent op = GwentData.opponents().get(opponentId);
        if (op == null) return "Unknown opponent " + opponentId;
        if (!Access.allowed(p)) return p.getName().getString() + " hasn't unlocked Gwent yet (/gwent unlock)";
        if (inMatch(p)) return p.getName().getString() + " is already playing";
        String[] why = new String[1];
        Deck mine = Collection.playable(p, GwentData.cards(), why);
        if (mine == null) {
            p.sendSystemMessage(Component.literal(why[0]).withStyle(ChatFormatting.YELLOW));
            return why[0];
        }
        Deck theirs = GwentData.deckOf(op);
        String err = theirs.validate(GwentData.cards(), null);
        if (err != null) return "Opponent " + opponentId + " has an invalid deck: " + err;
        String reward = Collection.beatNpc(p, opponentId) ? null : op.reward;
        String entity = op.npc != null ? "npc:" + op.npc + "|" + op.entity : op.entity;
        Match match = new Match(p.getUUID(), new Game(GwentData.cards(), mine, p.getName().getString(), theirs, op.name, rng.nextLong()),
                Kind.NPC, opponentId, entity, reward, theirs);
        try { if (op.npc != null) match.opponentUuid = UUID.fromString(op.npc); } catch (IllegalArgumentException ignored) {}
        start(p, match);
        p.sendSystemMessage(Component.literal(op.name + " accepts your challenge!"
                + (reward != null ? " Win to earn their card." : " (No card to win this time.)")).withStyle(ChatFormatting.GOLD));
        return null;
    }

    /** Sneak + right-click on a villager: offer a match. */
    public static void offerVillager(ServerPlayer p, Villager v) {
        if (inMatch(p)) { p.sendSystemMessage(Component.literal("You're already in a match.")); return; }
        String[] why = new String[1];
        if (Collection.playable(p, GwentData.cards(), why) == null) {
            p.sendSystemMessage(Component.literal(why[0]).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (p.getInventory().countItem(Items.EMERALD) < BET) {
            p.sendSystemMessage(Component.literal("Villagers play Gwent for " + BET + " emeralds. You don't have enough.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Net.toPlayer(p, new Net.VillagerOffer(v.getId(), v.getDisplayName().getString(), v.getVillagerData().getLevel()));
    }

    public static void acceptVillager(ServerPlayer p, int entityId) {
        if (p == null || inMatch(p) || !Access.allowed(p)) return;
        Entity e = p.level().getEntity(entityId);
        if (!(e instanceof Villager v) || v.isBaby() || e.distanceToSqr(p) > 100) return;
        String[] why = new String[1];
        Deck mine = Collection.playable(p, GwentData.cards(), why);
        if (mine == null) { p.sendSystemMessage(Component.literal(why[0])); return; }
        if (p.getInventory().countItem(Items.EMERALD) < BET) return;
        p.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), BET, p.inventoryMenu.getCraftSlots());
        int level = v.getVillagerData().getLevel();
        double quality = Math.min(1, (level - 1) / 4.0 * 0.7 + rng.nextDouble() * 0.3);
        String faction = CardRegistry.PLAYABLE_FACTIONS.get(rng.nextInt(CardRegistry.PLAYABLE_FACTIONS.size()));
        Deck theirs = Decks.random(GwentData.cards(), faction, quality, rng);
        String name = v.getDisplayName().getString();
        Match match = new Match(p.getUUID(), new Game(GwentData.cards(), mine, p.getName().getString(), theirs, name, rng.nextLong()),
                Kind.VILLAGER, "villager", "entity:" + v.getId() + "|minecraft:villager", null, theirs);
        match.opponentUuid = v.getUUID();
        start(p, match);
    }

    private static void start(ServerPlayer p, Match m) {
        matches.put(p.getUUID(), m);
        send(p, m);
    }

    // ------------------------------------------------------------------ player input

    public static void action(ServerPlayer p, Net.MatchAction a) {
        if (p == null) return;
        Match m = matches.get(p.getUUID());
        if (m == null) return;
        if (a.type.equals("close")) {
            if (m.finished) matches.remove(p.getUUID());
            return;
        }
        if (m.finished) return;
        Game g = m.game;
        try {
            switch (a.type) {
                case "play": g.play(0, a.uid, Row.parse(a.row), a.target); break;
                case "leader": g.useLeader(0); break;
                case "pass": g.pass(0); break;
                case "redraw": g.redraw(0, a.uid); break;
                case "redraw_done": g.finishRedraw(0); break;
                case "first_me": g.chooseFirst(0, true); break;
                case "first_them": g.chooseFirst(0, false); break;
                case "choose": {
                    List<Integer> picks = new ArrayList<>();
                    for (int i : a.picks) picks.add(i);
                    g.choose(0, picks);
                    break;
                }
                case "forfeit": forfeit(p, m); return;
                default: return;
            }
        } catch (IllegalStateException e) {
            p.displayClientMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED), true);
        }
        m.aiWait = AI_DELAY;
        afterChange(p, m);
    }

    /** K during a match reopens the board. */
    public static boolean reopen(ServerPlayer p) {
        Match m = matches.get(p.getUUID());
        if (m == null) return false;
        send(p, m);
        return true;
    }

    private static void forfeit(ServerPlayer p, Match m) {
        m.game.sides[0].gems = 0;
        m.game.phase = Game.Phase.OVER;
        m.game.winner = 1;
        m.game.log.add(p.getName().getString() + " forfeits.");
        m.game.logSide.add(-1);
        afterChange(p, m);
    }

    public static void logout(ServerPlayer p) {
        Match m = matches.remove(p.getUUID());
        if (m != null && !m.finished) EcGwent.LOGGER.info("{} left a Gwent match; it counts as a loss.", p.getName().getString());
    }

    // ------------------------------------------------------------------ AI and results

    public static void tick(net.minecraft.server.MinecraftServer server) {
        for (Match m : new ArrayList<>(matches.values())) {
            ServerPlayer p = server.getPlayerList().getPlayer(m.player);
            if (p == null) { matches.remove(m.player); continue; }
            if (m.finished) {
                if (m.closeTimer > 0 && --m.closeTimer == 0) matches.remove(m.player);
                continue;
            }
            if (!aiHasMove(m.game)) continue;
            // Redraws and the first-player choice are instant, real moves wait a moment.
            if (m.game.phase == Game.Phase.PLAY && --m.aiWait > 0) continue;
            m.aiWait = AI_DELAY;
            try {
                Ai.act(m.game, 1);
            } catch (RuntimeException e) {
                EcGwent.LOGGER.error("Gwent AI error", e);
                m.game.pass(1);
            }
            afterChange(p, m);
        }
    }

    private static boolean aiHasMove(Game g) {
        switch (g.phase) {
            case CHOOSE_FIRST: case CHOICE: return g.choice != null && g.choice.side == 1;
            case REDRAW: return !g.sides[1].redrawDone;
            case PLAY: return g.current == 1;
            default: return false;
        }
    }

    private static void afterChange(ServerPlayer p, Match m) {
        if (m.game.phase == Game.Phase.OVER && !m.finished) finish(p, m);
        send(p, m);
    }

    private static void send(ServerPlayer p, Match m) {
        Net.toPlayer(p, new Net.MatchView(View.of(m.game, 0, m.opponentEntity, m.result)));
    }

    private static void finish(ServerPlayer p, Match m) {
        m.finished = true;
        m.closeTimer = 20 * 120;
        int w = m.game.winner == null ? -1 : m.game.winner;
        String opp = m.game.sides[1].name;
        if (m.kind == Kind.NPC) {
            if (w == 0 && m.reward != null && GwentData.cards().get(m.reward) != null) {
                Collection.add(p, m.reward, 1);
                Collection.markNpcBeaten(p, m.opponentId);
                m.result = "You won " + GwentData.cards().get(m.reward).name + "! It's in your card book.";
            } else if (w == 0) {
                Collection.markNpcBeaten(p, m.opponentId);
                m.result = "You beat " + opp + " again. Nothing left to win here.";
            } else {
                m.result = w < 0 ? "A draw. Challenge " + opp + " again to win their card." : opp + " wins. Try again!";
            }
        } else {
            if (w == 0) {
                if ("cards".equals(GwentConfig.get(p.server).villagerReward)) {
                    give(p, BET);
                    CardDef won = randomCard(m.opponentDeck);
                    Collection.add(p, won.key, 1);
                    m.result = "You won your " + BET + " emeralds back and " + won.name + "!";
                } else {
                    give(p, BET + 1);
                    m.result = "You won! " + (BET + 1) + " emeralds.";
                }
            } else if (w < 0) {
                give(p, BET);
                m.result = "A draw: you get your " + BET + " emeralds back.";
            } else {
                m.result = opp + " keeps your " + BET + " emeralds.";
            }
        }
        p.sendSystemMessage(Component.literal(m.result).withStyle(w == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    private static CardDef randomCard(Deck d) {
        List<CardDef> cards = new ArrayList<>();
        for (CardDef c : d.resolve(GwentData.cards())) if (Collection.collectible(c)) cards.add(c);
        return cards.get(rng.nextInt(cards.size()));
    }

    private static void give(ServerPlayer p, int emeralds) {
        ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(Items.EMERALD, emeralds));
    }

    @SuppressWarnings("unused")
    private static Card unused;
}
