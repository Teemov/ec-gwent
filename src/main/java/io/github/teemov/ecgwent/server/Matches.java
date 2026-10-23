package io.github.teemov.ecgwent.server;

import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.Ai;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Running matches. Side 0 is always a player; side 1 is the NPC, the villager, or a second player in a duel.
 * Every player in a match maps to it, so both duellists find the same game.
 */
public final class Matches {
    public static final int BET = 2;
    public static final int MAX_DUEL_BET = 64;
    /** Ticks between AI moves, so players can follow what happens. */
    private static final int AI_DELAY = 30;
    /** The AI waits this long before its first move so the coin toss can play out. */
    private static final int AI_START_DELAY = 70;
    /** Duellists who end up further apart than this forfeit (the one who walked away loses). */
    private static final double DUEL_RANGE = 32;
    private static final int INVITE_TICKS = 20 * 60;
    /** PvP: a minute per move; running out passes (or makes the pending pick). The start screens get a few extra seconds. */
    private static final int MOVE_TICKS = 20 * 60, START_GRACE = 20 * 4;

    private static final Map<UUID, Match> matches = new HashMap<>();
    /** Pending duel invitations: invited player → invitation. */
    private static final Map<UUID, Invite> invites = new HashMap<>();
    private static final Random rng = new Random();

    private Matches() {}

    enum Kind { NPC, VILLAGER, DUEL }

    private record Invite(UUID from, int bet, long expiresTick) {}

    static final class Match {
        /** Players per side; side 1 is null unless this is a duel. */
        final UUID[] players = new UUID[2];
        final Game game;
        final Kind kind;
        final String opponentId;
        /** How each side's client draws the opponent's portrait. */
        final String[] portrait = new String[2];
        /** The villager or NPC being played against; protected from damage while the match runs. */
        UUID opponentUuid;
        final String reward;
        final Deck opponentDeck;
        final int[] bets = new int[2];
        final Vec3[] startPos = new Vec3[2];
        final Object[] startLevel = new Object[2];
        int aiWait = AI_START_DELAY;
        boolean finished;
        final String[] result = new String[2];
        int closeTimer = -1;
        /** Server tick of the last change, and the tick by which the due move must be made (duels). */
        long startTick, deadline;

        Match(UUID player, Game game, Kind kind, String opponentId, String opponentPortrait, String reward, Deck opponentDeck) {
            players[0] = player;
            this.game = game;
            this.kind = kind;
            this.opponentId = opponentId;
            portrait[0] = opponentPortrait;
            this.reward = reward;
            this.opponentDeck = opponentDeck;
        }

        int sideOf(UUID p) { return p.equals(players[0]) ? 0 : p.equals(players[1]) ? 1 : -1; }
        boolean human(int side) { return players[side] != null; }
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
        start(p.server, match);
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
        if (!takeEmeralds(p, BET)) return;
        int level = v.getVillagerData().getLevel();
        double quality = Math.min(1, (level - 1) / 4.0 * 0.7 + rng.nextDouble() * 0.3);
        String faction = CardRegistry.PLAYABLE_FACTIONS.get(rng.nextInt(CardRegistry.PLAYABLE_FACTIONS.size()));
        Deck theirs = Decks.random(GwentData.cards(), faction, quality, rng);
        String name = v.getDisplayName().getString();
        Match match = new Match(p.getUUID(), new Game(GwentData.cards(), mine, p.getName().getString(), theirs, name, rng.nextLong()),
                Kind.VILLAGER, "villager", "entity:" + v.getId() + "|minecraft:villager", null, theirs);
        match.opponentUuid = v.getUUID();
        start(p.server, match);
    }

    // ------------------------------------------------------------------ duels

    /** /gwent duel or sneak + right-click on a player: invite them. Returns an error, or null. */
    public static String inviteDuel(ServerPlayer from, ServerPlayer to, int bet) {
        if (from == to) return "You can't duel yourself.";
        if (!Access.allowed(from)) return "You haven't unlocked Gwent yet.";
        if (!Access.allowed(to)) return to.getName().getString() + " doesn't play Gwent (yet).";
        if (inMatch(from)) return "You're already in a match.";
        if (inMatch(to)) return to.getName().getString() + " is already playing.";
        Invite pending = invites.get(to.getUUID());
        if (pending != null && !pending.from.equals(from.getUUID()) && from.server.getTickCount() <= pending.expiresTick)
            return to.getName().getString() + " is already considering another challenge.";
        if (bet < 0 || bet > MAX_DUEL_BET) return "Bets go from 0 to " + MAX_DUEL_BET + " emeralds.";
        if (from.level() != to.level() || from.distanceTo(to) > 16) return "Stand closer to " + to.getName().getString() + " to challenge them.";
        String[] why = new String[1];
        if (Collection.playable(from, GwentData.cards(), why) == null) return why[0];
        if (bet > 0 && from.getInventory().countItem(Items.EMERALD) < bet) return "You don't have " + bet + " emeralds.";
        invites.put(to.getUUID(), new Invite(from.getUUID(), bet, from.server.getTickCount() + INVITE_TICKS));
        Net.toPlayer(to, new Net.DuelOffer(from.getUUID(), from.getName().getString(), bet));
        from.sendSystemMessage(Component.literal("You challenged " + to.getName().getString() + " to Gwent"
                + (bet > 0 ? ", betting " + bet + " emeralds." : ".")).withStyle(ChatFormatting.GOLD));
        return null;
    }

    public static void answerDuel(ServerPlayer to, UUID fromId, boolean accept, int myBet) {
        if (to == null) return;
        Invite inv = invites.get(to.getUUID());
        if (inv == null || !inv.from.equals(fromId)) return;
        invites.remove(to.getUUID());
        ServerPlayer from = to.server.getPlayerList().getPlayer(fromId);
        if (from == null) { to.sendSystemMessage(Component.literal("Your challenger has left.")); return; }
        if (!accept) {
            from.sendSystemMessage(Component.literal(to.getName().getString() + " declined your challenge.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (to.server.getTickCount() > inv.expiresTick) { to.sendSystemMessage(Component.literal("That challenge has expired.")); return; }
        String err = null;
        String[] why = new String[1];
        Deck a = Collection.playable(from, GwentData.cards(), why);
        if (a == null) err = from.getName().getString() + "'s deck isn't ready.";
        Deck b = err == null ? Collection.playable(to, GwentData.cards(), why) : null;
        if (err == null && b == null) err = why[0];
        if (err == null && (inMatch(from) || inMatch(to))) err = "One of you is already playing.";
        if (err == null && (from.level() != to.level() || from.distanceTo(to) > 16)) err = "You're too far apart.";
        if (err == null && (myBet < 0 || myBet > MAX_DUEL_BET)) err = "Bets go from 0 to " + MAX_DUEL_BET + " emeralds.";
        if (err == null && inv.bet > 0 && from.getInventory().countItem(Items.EMERALD) < inv.bet)
            err = from.getName().getString() + " no longer has " + inv.bet + " emeralds.";
        if (err == null && myBet > 0 && to.getInventory().countItem(Items.EMERALD) < myBet)
            err = to.getName().getString() + " doesn't have " + myBet + " emeralds.";
        if (err != null) {
            to.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.YELLOW));
            from.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (inv.bet > 0) takeEmeralds(from, inv.bet);
        if (myBet > 0) takeEmeralds(to, myBet);
        Match match = new Match(from.getUUID(), new Game(GwentData.cards(), a, from.getName().getString(), b, to.getName().getString(), rng.nextLong(), inv.bet, myBet),
                Kind.DUEL, "duel", "player:" + to.getUUID(), null, b);
        match.players[1] = to.getUUID();
        match.portrait[1] = "player:" + from.getUUID();
        match.bets[0] = inv.bet;
        match.bets[1] = myBet;
        match.startPos[0] = from.position();
        match.startPos[1] = to.position();
        match.startLevel[0] = from.level();
        match.startLevel[1] = to.level();
        start(to.server, match);
    }

    private static void start(MinecraftServer server, Match m) {
        m.startTick = server.getTickCount();
        m.deadline = m.startTick + MOVE_TICKS + START_GRACE;
        for (UUID p : m.players) if (p != null) matches.put(p, m);
        sendAll(server, m);
    }

    // ------------------------------------------------------------------ player input

    public static void action(ServerPlayer p, Net.MatchAction a) {
        if (p == null) return;
        Match m = matches.get(p.getUUID());
        if (m == null) return;
        int side = m.sideOf(p.getUUID());
        if (a.type.equals("close")) {
            if (m.finished) matches.remove(p.getUUID());
            return;
        }
        if (m.finished || side < 0) return;
        Game g = m.game;
        try {
            switch (a.type) {
                case "play" -> g.play(side, a.uid, Row.parse(a.row), a.target);
                case "leader" -> g.useLeader(side);
                case "pass" -> g.pass(side);
                case "redraw" -> g.redraw(side, a.uid);
                case "redraw_done" -> g.finishRedraw(side);
                case "first_me" -> g.chooseFirst(side, true);
                case "first_them" -> g.chooseFirst(side, false);
                case "choose" -> {
                    List<Integer> picks = new ArrayList<>();
                    for (int i : a.picks) picks.add(i);
                    g.choose(side, picks);
                }
                case "forfeit" -> { forfeit(p.server, m, side, " forfeits."); return; }
                default -> { return; }
            }
        } catch (IllegalStateException e) {
            // An illegal move changes nothing (and doesn't reset the move timer).
            p.displayClientMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED), true);
            send(p, m);
            return;
        }
        if (m.game.phase == Game.Phase.PLAY) m.aiWait = Math.max(m.aiWait, AI_DELAY);
        afterChange(p.server, m);
    }

    /** K during a match reopens the board. */
    public static boolean reopen(ServerPlayer p) {
        Match m = matches.get(p.getUUID());
        if (m == null) return false;
        send(p, m);
        return true;
    }

    private static void forfeit(MinecraftServer server, Match m, int side, String why) {
        if (m.finished) return;
        m.game.sides[side].gems = 0;
        m.game.phase = Game.Phase.OVER;
        m.game.winner = 1 - side;
        m.game.log.add(m.game.sides[side].name + why);
        m.game.logSide.add(-1);
        afterChange(server, m);
    }

    public static void logout(ServerPlayer p) {
        invites.remove(p.getUUID());
        Match m = matches.remove(p.getUUID());
        if (m == null) return;
        int side = m.sideOf(p.getUUID());
        if (!m.finished && side >= 0) {
            EcGwent.LOGGER.info("{} left a Gwent match; it counts as a loss.", p.getName().getString());
            forfeit(p.server, m, side, " left the game.");
        }
    }

    // ------------------------------------------------------------------ AI, distance checks and results

    public static void tick(MinecraftServer server) {
        invites.values().removeIf(i -> server.getTickCount() > i.expiresTick);
        for (Match m : new ArrayList<>(new java.util.LinkedHashSet<>(matches.values()))) {
            boolean anyone = false;
            for (UUID id : m.players) if (id != null && server.getPlayerList().getPlayer(id) != null) anyone = true;
            if (!anyone) { for (UUID id : m.players) if (id != null) matches.remove(id); continue; }
            if (m.finished) {
                if (m.closeTimer > 0 && --m.closeTimer == 0) for (UUID id : m.players) if (id != null) matches.remove(id, m);
                continue;
            }
            if (m.kind == Kind.DUEL) {
                checkDuelRange(server, m);
                checkMoveTimer(server, m);
                continue;
            }
            holdOpponent(server, m);
            if (--m.aiWait > 0 || !aiHasMove(m.game)) continue;
            m.aiWait = AI_DELAY;
            try {
                Ai.act(m.game, 1);
            } catch (RuntimeException e) {
                EcGwent.LOGGER.error("Gwent AI error", e);
                try { m.game.pass(1); } catch (IllegalStateException ignored) {}
            }
            afterChange(server, m);
        }
    }

    /** The villager or NPC stands still and faces the player for as long as the match runs. Nothing is stored on it. */
    private static void holdOpponent(MinecraftServer server, Match m) {
        if (m.opponentUuid == null) return;
        // Several players can play the same villager or NPC: it faces whoever challenged it last.
        for (Match o : matches.values())
            if (o != m && !o.finished && m.opponentUuid.equals(o.opponentUuid) && o.startTick > m.startTick) return;
        ServerPlayer p = server.getPlayerList().getPlayer(m.players[0]);
        if (p == null) return;
        Entity e = p.serverLevel().getEntity(m.opponentUuid);
        if (!(e instanceof net.minecraft.world.entity.Mob mob)) return;
        mob.getNavigation().stop();
        if (mob instanceof Villager v) {
            v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.PATH);
            v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET,
                    new net.minecraft.world.entity.ai.behavior.EntityTracker(p, true));
        }
        mob.setDeltaMovement(0, Math.min(0, mob.getDeltaMovement().y), 0);
        mob.getLookControl().setLookAt(p, 30f, 30f);
    }

    /** PvP move timer: whoever has to act and runs out of time passes (or gets the sensible default pick). */
    private static void checkMoveTimer(MinecraftServer server, Match m) {
        if (server.getTickCount() < m.deadline) return;
        Game g = m.game;
        try {
            switch (g.phase) {
                case PLAY -> {
                    int side = g.current;
                    g.log.add(g.sides[side].name + " ran out of time.");
                    g.logSide.add(-1);
                    g.pass(side);
                }
                case REDRAW -> {
                    for (int side = 0; side < 2; side++) if (!g.sides[side].redrawDone) g.finishRedraw(side);
                }
                case CHOICE, CHOOSE_FIRST -> {
                    if (g.choice != null) {
                        g.log.add(g.sides[g.choice.side].name + " ran out of time.");
                        g.logSide.add(-1);
                        Ai.act(g, g.choice.side);
                    }
                }
                default -> { return; }
            }
        } catch (RuntimeException e) {
            EcGwent.LOGGER.error("Gwent move timer", e);
        }
        afterChange(server, m);
    }

    /** A duellist who walks away (or changes dimension) forfeits. */
    private static void checkDuelRange(MinecraftServer server, Match m) {
        if (server.getTickCount() % 20 != 0) return;
        ServerPlayer a = server.getPlayerList().getPlayer(m.players[0]), b = server.getPlayerList().getPlayer(m.players[1]);
        if (a == null || b == null) return;
        if (a.level() == b.level() && a.distanceTo(b) <= DUEL_RANGE) return;
        double movedA = a.position().distanceTo(m.startPos[0]), movedB = b.position().distanceTo(m.startPos[1]);
        int leaver;
        if (a.level() != m.startLevel[0]) leaver = 0;
        else if (b.level() != m.startLevel[1]) leaver = 1;
        else leaver = movedA >= movedB ? 0 : 1;
        forfeit(server, m, leaver, " walked away from the table.");
    }

    private static boolean aiHasMove(Game g) {
        switch (g.phase) {
            case CHOOSE_FIRST: case CHOICE: return g.choice != null && g.choice.side == 1;
            case REDRAW: return !g.sides[1].redrawDone;
            case PLAY: return g.current == 1;
            default: return false;
        }
    }

    private static void afterChange(MinecraftServer server, Match m) {
        if (m.kind == Kind.DUEL) m.deadline = server.getTickCount() + MOVE_TICKS;
        if (m.game.phase == Game.Phase.OVER && !m.finished) finish(server, m);
        sendAll(server, m);
    }

    private static void sendAll(MinecraftServer server, Match m) {
        for (UUID id : m.players) {
            if (id == null) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) send(p, m);
        }
    }

    private static void send(ServerPlayer p, Match m) {
        int side = Math.max(0, m.sideOf(p.getUUID()));
        int timeLeft = m.kind == Kind.DUEL && !m.finished ? (int) Math.max(0, (m.deadline - p.server.getTickCount()) / 20) : -1;
        Net.toPlayer(p, new Net.MatchView(View.of(m.game, side, m.portrait[side], m.result[side], timeLeft)));
    }

    private static void finish(MinecraftServer server, Match m) {
        m.finished = true;
        m.closeTimer = 20 * 120;
        int w = m.game.winner == null ? -1 : m.game.winner;
        ServerPlayer p = server.getPlayerList().getPlayer(m.players[0]);
        String opp = m.game.sides[1].name;
        switch (m.kind) {
            case NPC -> {
                if (p == null) return;
                if (w == 0 && m.reward != null && GwentData.cards().get(m.reward) != null) {
                    Collection.add(p, m.reward, 1);
                    Collection.markNpcBeaten(p, m.opponentId);
                    m.result[0] = "You won " + GwentData.cards().get(m.reward).name + "! It's in your card book.";
                } else if (w == 0) {
                    Collection.markNpcBeaten(p, m.opponentId);
                    m.result[0] = "You beat " + opp + " again. Nothing left to win here.";
                } else {
                    m.result[0] = w < 0 ? "A draw. Challenge " + opp + " again to win their card." : opp + " wins. Try again!";
                }
            }
            case VILLAGER -> {
                if (p == null) return;
                if (w == 0) {
                    if ("cards".equals(GwentConfig.get(server).villagerReward)) {
                        give(p, BET);
                        CardDef won = randomCard(m.opponentDeck);
                        Collection.add(p, won.key, 1);
                        m.result[0] = "You won your " + BET + " emeralds back and " + won.name + "!";
                    } else {
                        give(p, BET + 1);
                        m.result[0] = "You won! " + (BET + 1) + " emeralds.";
                    }
                } else if (w < 0) {
                    give(p, BET);
                    m.result[0] = "A draw: you get your " + BET + " emeralds back.";
                } else {
                    m.result[0] = opp + " keeps your " + BET + " emeralds.";
                }
            }
            case DUEL -> {
                int pot = m.bets[0] + m.bets[1];
                for (int side = 0; side < 2; side++) {
                    ServerPlayer sp = server.getPlayerList().getPlayer(m.players[side]);
                    String other = m.game.sides[1 - side].name;
                    if (w < 0) {
                        m.result[side] = m.bets[side] > 0 ? "A draw: you get your " + m.bets[side] + " emeralds back." : "A draw!";
                        if (sp != null && m.bets[side] > 0) give(sp, m.bets[side]);
                    } else if (w == side) {
                        m.result[side] = pot > 0 ? "You beat " + other + " and win " + pot + " emeralds!" : "You beat " + other + "!";
                        if (sp != null && pot > 0) give(sp, pot);
                    } else {
                        m.result[side] = other + " wins" + (pot > 0 ? " the " + pot + " emeralds." : ".");
                    }
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            if (m.players[side] == null || m.result[side] == null) continue;
            ServerPlayer sp = server.getPlayerList().getPlayer(m.players[side]);
            if (sp != null) sp.sendSystemMessage(Component.literal(m.result[side]).withStyle(w == side ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        }
    }

    private static CardDef randomCard(Deck d) {
        List<CardDef> cards = new ArrayList<>();
        for (CardDef c : d.resolve(GwentData.cards())) if (Collection.collectible(c)) cards.add(c);
        return cards.get(rng.nextInt(cards.size()));
    }

    private static boolean takeEmeralds(ServerPlayer p, int n) {
        if (p.getInventory().countItem(Items.EMERALD) < n) return false;
        p.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), n, p.inventoryMenu.getCraftSlots());
        return true;
    }

    private static void give(ServerPlayer p, int emeralds) {
        ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(Items.EMERALD, emeralds));
    }
}
