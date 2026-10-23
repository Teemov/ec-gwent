package io.github.teemov.ecgwent.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.CardDef;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * /gwent book | version | forfeit                (everyone)
 * /gwent challenge <players> <opponent>          (level 2: Easy NPC dialogs run this)
 * /gwent give <players> <card> [count] | reset <players> | config villagerReward <emeralds|cards>   (level 2)
 */
public final class GwentCommands {
    private GwentCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("gwent")
                .then(Commands.literal("version").requires(Access::allowed).executes(c -> {
                    String v = ModList.get().getModContainerById(EcGwent.MODID).map(m -> m.getModInfo().getVersion().toString()).orElse("?");
                    c.getSource().sendSuccess(() -> Component.literal("EC Gwent " + v + ", " + GwentData.cards().all().size()
                            + " cards, " + GwentData.opponents().size() + " opponents"), false);
                    return 1;
                }))
                .then(Commands.literal("book").requires(Access::allowed).executes(c -> {
                    ServerHooks.sendBook(c.getSource().getPlayerOrException(), true);
                    return 1;
                }))
                .then(Commands.literal("duel").requires(Access::allowed)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> duel(c, 0))
                                .then(Commands.argument("bet", IntegerArgumentType.integer(0, Matches.MAX_DUEL_BET))
                                        .executes(c -> duel(c, IntegerArgumentType.getInteger(c, "bet"))))))
                .then(Commands.literal("forfeit").requires(Access::allowed).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Matches.action(p, io.github.teemov.ecgwent.net.Net.MatchAction.of("forfeit"));
                    return 1;
                }))
                .then(Commands.literal("challenge").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("opponent", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(GwentData.opponents().keySet(), b))
                                        .executes(c -> challenge(c, null))
                                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("easy", "moderate", "hard"), b))
                                                .executes(c -> challenge(c, StringArgumentType.getString(c, "difficulty")))))))
                .then(Commands.literal("give").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("card", StringArgumentType.greedyString())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(cardKeys(), b))
                                        .executes(c -> give(c, 1)))))
                .then(Commands.literal("giveall").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players()).executes(GwentCommands::giveAll)))
                .then(Commands.literal("unlock").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(c -> setUnlocked(c, true, false))
                                .then(Commands.literal("silent").executes(c -> setUnlocked(c, true, true)))))
                .then(Commands.literal("lock").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players()).executes(c -> setUnlocked(c, false, true))))
                .then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("players", EntityArgument.players()).executes(c -> {
                            for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) Collection.reset(p);
                            c.getSource().sendSuccess(() -> Component.literal("Card collections reset."), true);
                            return 1;
                        })))
                .then(Commands.literal("config").requires(s -> s.hasPermission(2))
                        .executes(c -> {
                            GwentConfig cfg = GwentConfig.get(c.getSource().getServer());
                            c.getSource().sendSuccess(() -> Component.literal("villagerReward = " + cfg.villagerReward
                                    + ", requireUnlock = " + cfg.requireUnlock), false);
                            return 1;
                        })
                        .then(Commands.literal("requireUnlock")
                                .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
                                    GwentConfig cfg = GwentConfig.get(c.getSource().getServer());
                                    cfg.requireUnlock = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "value");
                                    cfg.setDirty();
                                    for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                                        Access.refreshCommands(p);
                                        ServerHooks.syncAccess(p);
                                    }
                                    c.getSource().sendSuccess(() -> Component.literal(cfg.requireUnlock
                                            ? "Players need /gwent unlock before they can play." : "Everyone can play Gwent."), true);
                                    return 1;
                                })))
                        .then(Commands.literal("villagerReward")
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("emeralds", "cards"), b))
                                        .executes(c -> {
                                            String v = StringArgumentType.getString(c, "value");
                                            if (!v.equals("emeralds") && !v.equals("cards")) {
                                                c.getSource().sendFailure(Component.literal("Use emeralds or cards"));
                                                return 0;
                                            }
                                            GwentConfig cfg = GwentConfig.get(c.getSource().getServer());
                                            cfg.villagerReward = v;
                                            cfg.setDirty();
                                            c.getSource().sendSuccess(() -> Component.literal("Winning against villagers now gives "
                                                    + (v.equals("cards") ? "a card" : "an extra emerald") + "."), true);
                                            return 1;
                                        })))));
    }

    private static int duel(CommandContext<CommandSourceStack> c, int bet) throws CommandSyntaxException {
        String err = Matches.inviteDuel(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), bet);
        if (err != null) { c.getSource().sendFailure(Component.literal(err)); return 0; }
        return 1;
    }

    private static int setUnlocked(CommandContext<CommandSourceStack> c, boolean on, boolean silent) throws CommandSyntaxException {
        java.util.Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "players");
        for (ServerPlayer p : ps) {
            boolean was = Collection.unlocked(p);
            Collection.setUnlocked(p, on);
            Access.refreshCommands(p);
            ServerHooks.syncAccess(p);
            if (on && !was && !silent)
                p.sendSystemMessage(Component.literal("You've learned to play Gwent! Press K to open your card book.").withStyle(ChatFormatting.GOLD));
        }
        c.getSource().sendSuccess(() -> Component.literal((on ? "Unlocked" : "Locked") + " Gwent for " + ps.size() + " player(s)."), true);
        return ps.size();
    }

    private static List<String> cardKeys() {
        List<String> l = new ArrayList<>();
        for (CardDef d : GwentData.cards().all()) if (d.count > 0 || d.type == CardDef.Type.LEADER) l.add(d.key);
        return l;
    }

    private static int challenge(CommandContext<CommandSourceStack> c, String difficulty) throws CommandSyntaxException {
        String op = StringArgumentType.getString(c, "opponent");
        io.github.teemov.ecgwent.engine.Ai.Difficulty level = io.github.teemov.ecgwent.engine.Ai.Difficulty.parse(difficulty, null);
        if (difficulty != null && level == null) {
            c.getSource().sendFailure(Component.literal("Difficulty must be easy, moderate or hard"));
            return 0;
        }
        int ok = 0;
        for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
            String err = Matches.challengeNpc(p, op, level);
            if (err == null) ok++;
            else c.getSource().sendFailure(Component.literal(err));
        }
        return ok;
    }

    private static int give(CommandContext<CommandSourceStack> c, int n) throws CommandSyntaxException {
        String key = StringArgumentType.getString(c, "card").trim();
        if (GwentData.cards().get(key) == null) {
            c.getSource().sendFailure(Component.literal("Unknown card " + key));
            return 0;
        }
        java.util.Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "players");
        for (ServerPlayer p : ps) {
            Collection.add(p, key, n);
            p.sendSystemMessage(Component.literal("You received " + GwentData.cards().get(key).name + ".").withStyle(ChatFormatting.GOLD));
        }
        return ps.size();
    }

    /** Testing aid: every collectible card at its full copy count, plus every leader. */
    private static int giveAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
            if (!Collection.started(p)) Collection.start(p, GwentData.cards(), "valarians");
            java.util.Map<String, Integer> owned = Collection.owned(p);
            for (CardDef d : GwentData.cards().all()) {
                int want = d.type == CardDef.Type.LEADER ? 1 : d.count;
                int have = owned.getOrDefault(d.key, 0);
                if (want > have) Collection.add(p, d.key, want - have);
            }
            p.sendSystemMessage(Component.literal("You now own every card.").withStyle(ChatFormatting.GOLD));
        }
        return 1;
    }

    @SuppressWarnings("unused")
    private static IntegerArgumentType unused;
}
