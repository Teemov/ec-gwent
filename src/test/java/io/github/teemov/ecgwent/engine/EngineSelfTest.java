package io.github.teemov.ecgwent.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plain-Java self test for the rules engine (no Minecraft needed):
 * {@code java EngineSelfTest <path to gwent_cards folder>}.
 */
public final class EngineSelfTest {
    private static int failures;

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/main/resources/data/ec_gwent/gwent_cards");
        CardRegistry reg = new CardRegistry();
        try (var files = Files.list(dir)) {
            for (Path p : files.sorted().toList()) reg.load(Files.readString(p));
        }
        System.out.println("Loaded " + reg.all().size() + " card definitions");

        // Every reference resolves.
        for (CardDef d : reg.all()) {
            if (d.avengerKey != null) expect(reg.get(d.avengerKey) != null, d.key + " avenger");
            if (d.transformKey != null) expect(reg.get(d.transformKey) != null, d.key + " transform");
        }

        // Starter decks are legal and usable.
        for (String f : CardRegistry.PLAYABLE_FACTIONS) {
            Deck d = Decks.starter(reg, f);
            String err = d.validate(reg, null);
            expect(err == null, "starter " + f + ": " + err);
        }

        // NPC opponents: reward exists, generated deck is legal (same seed rule as GwentData.deckOf).
        Path ops = dir.resolveSibling("gwent_opponents");
        if (Files.isDirectory(ops)) {
            try (var files = Files.list(ops)) {
                for (Path p : files.sorted().toList()) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> o = (java.util.Map<String, Object>) Json.parse(Files.readString(p));
                    String id = p.getFileName().toString().replace(".json", "");
                    expect(reg.get((String) o.get("reward")) != null, id + " reward " + o.get("reward"));
                    Deck d = Decks.random(reg, (String) o.get("faction"), (Double) o.get("quality"), new Random(id.hashCode()));
                    expect(d.validate(reg, null) == null, id + " deck: " + d.validate(reg, null));
                }
            }
        }

        scoringTests(reg);
        abilityTests(reg);

        // Random AI-vs-AI matches.
        Random rng = new Random(42);
        int games = Integer.parseInt(System.getProperty("games", "3000"));
        int[] wins = new int[3];
        for (int i = 0; i < games; i++) {
            String fa = CardRegistry.PLAYABLE_FACTIONS.get(rng.nextInt(5)), fb = CardRegistry.PLAYABLE_FACTIONS.get(rng.nextInt(5));
            Deck a = Decks.random(reg, fa, rng.nextDouble(), rng), b = Decks.random(reg, fb, rng.nextDouble(), rng);
            expect(a.validate(reg, null) == null, "random deck " + fa + ": " + a.validate(reg, null));
            expect(b.validate(reg, null) == null, "random deck " + fb + ": " + b.validate(reg, null));
            Game g = new Game(reg, a, "A", b, "B", rng.nextLong());
            int steps = 0;
            try {
                while (g.phase != Game.Phase.OVER && steps++ < 2000) {
                    boolean acted = Ai.act(g, 0) | Ai.act(g, 1);
                    if (steps % 7 == 0) { Json.parse(View.of(g, 0, "minecraft:villager", null)); Json.parse(View.of(g, 1, "x", "r")); }
                    if (!acted) { expect(false, "stuck in " + g.phase); break; }
                }
            } catch (RuntimeException e) {
                expect(false, "match " + i + " " + fa + " vs " + fb + ": " + e);
                e.printStackTrace();
                if (failures > 5) break;
                continue;
            }
            expect(g.phase == Game.Phase.OVER, "match " + i + " ended");
            expect(g.round <= 3, "at most 3 rounds");
            wins[g.winner == null ? 2 : (g.winner < 0 ? 2 : g.winner)]++;
        }
        System.out.println("AI vs AI: side A " + wins[0] + ", side B " + wins[1] + ", draws " + wins[2]);

        // Stronger random decks should beat weaker ones most of the time.
        int strongWins = 0;
        for (int i = 0; i < 400; i++) {
            String f = CardRegistry.PLAYABLE_FACTIONS.get(i % 5);
            Game g = new Game(reg, Decks.random(reg, f, 0.95, rng), "Strong", Decks.random(reg, f, 0.05, rng), "Weak", rng.nextLong());
            while (g.phase != Game.Phase.OVER) { Ai.act(g, 0); Ai.act(g, 1); }
            if (g.winner != null && g.winner == 0) strongWins++;
        }
        System.out.println("Strong deck beat weak deck in " + strongWins + "/400");
        expect(strongWins > 280, "deck quality matters");

        // New AI vs the previous one: same decks, both seatings.
        int newWins = 0, oldWins = 0, h2h = Integer.parseInt(System.getProperty("h2h", "1500"));
        long newR1Spent = 0, oldR1Spent = 0, r1Count = 0, newDumped = 0, oldDumped = 0;
        for (int i = 0; i < h2h; i++) {
            String fa = CardRegistry.PLAYABLE_FACTIONS.get(rng.nextInt(5)), fb = CardRegistry.PLAYABLE_FACTIONS.get(rng.nextInt(5));
            double q = 0.3 + rng.nextDouble() * 0.6;
            Deck a = Decks.random(reg, fa, q, rng), b = Decks.random(reg, fb, q, rng);
            long seed = rng.nextLong();
            for (int seat = 0; seat < 2; seat++) {
                Game hg = new Game(reg, a, "A", b, "B", seed);
                int newSide = seat;
                int[] handAtStart = new int[2];
                boolean r1Recorded = false;
                int guard = 0;
                while (hg.phase != Game.Phase.OVER && guard++ < 3000) {
                    if (hg.phase == Game.Phase.PLAY && hg.round == 1 && handAtStart[0] == 0 && handAtStart[1] == 0) {
                        handAtStart[0] = hg.sides[0].hand.size();
                        handAtStart[1] = hg.sides[1].hand.size();
                    }
                    if (!r1Recorded && hg.round == 2) {
                        r1Recorded = true;
                        r1Count++;
                        newR1Spent += handAtStart[newSide] - hg.sides[newSide].hand.size();
                        oldR1Spent += handAtStart[1 - newSide] - hg.sides[1 - newSide].hand.size();
                        int nh = hg.sides[newSide].hand.size(), oh = hg.sides[1 - newSide].hand.size();
                        if (nh <= 2 && oh >= 5) newDumped++;
                        if (oh <= 2 && nh >= 5) oldDumped++;
                    }
                    boolean acted = (newSide == 0 ? Ai.act(hg, 0) : BaselineAi.act(hg, 0)) | (newSide == 1 ? Ai.act(hg, 1) : BaselineAi.act(hg, 1));
                    if (!acted) break;
                }
                expect(hg.phase == Game.Phase.OVER, "head-to-head match ended");
                if (hg.winner != null && hg.winner == newSide) newWins++;
                else if (hg.winner != null && hg.winner == 1 - newSide) oldWins++;
            }
        }
        System.out.printf("New AI vs old AI: %d wins, %d losses, %d draws (%.1f%% of decided)%n", newWins, oldWins,
                2 * h2h - newWins - oldWins, 100.0 * newWins / Math.max(1, newWins + oldWins));
        System.out.printf("Cards spent in round 1: new AI %.2f, old AI %.2f%n", newR1Spent / (double) r1Count, oldR1Spent / (double) r1Count);
        System.out.printf("Left round 1 with <=2 cards vs >=5: new AI %.1f%%, old AI %.1f%%%n", 100.0 * newDumped / r1Count, 100.0 * oldDumped / r1Count);
        expect(newWins > oldWins, "new AI beats the old one");
        expect(newDumped * 4 < oldDumped + 4, "new AI doesn't empty its hand in round 1");

        // Bets: the higher bet starts, equal bets use the coin.
        for (int i = 0; i < 20; i++) {
            Game bg = new Game(reg, Decks.starter(reg, "valarians"), "A", Decks.starter(reg, "monsters"), "B", i, 3, 10);
            expect(bg.firstStarter == 1 && bg.startByBet && !bg.coinToss, "higher bet starts");
            Game eq = new Game(reg, Decks.starter(reg, "valarians"), "A", Decks.starter(reg, "monsters"), "B", i, 5, 5);
            expect(eq.coinToss && !eq.startByBet, "equal bets toss a coin");
        }

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    /** A game in the PLAY phase with empty hands, so cards can be placed by hand. */
    private static Game emptyGame(CardRegistry reg, String fa, String fb) {
        Game g = new Game(reg, Decks.starter(reg, fa), "A", Decks.starter(reg, fb), "B", 1);
        while (g.phase == Game.Phase.CHOOSE_FIRST || g.phase == Game.Phase.REDRAW) {
            if (g.phase == Game.Phase.CHOOSE_FIRST) g.chooseFirst(g.choice.side, true);
            else { if (!g.sides[0].redrawDone) g.finishRedraw(0); if (!g.sides[1].redrawDone) g.finishRedraw(1); }
        }
        for (Side s : g.sides) { s.deck.addAll(s.hand); s.hand.clear(); }
        return g;
    }

    private static Card give(Game g, int side, String key) {
        Card c = new Card(9000 + g.sides[side].hand.size() + 100 * side, g.reg.require(key));
        g.sides[side].hand.add(c);
        return c;
    }

    private static void put(Game g, int side, String key, Row r) {
        g.sides[side].rows.get(r).add(new Card(8000 + g.sides[side].boardCards().size() + 100 * side, g.reg.require(key)));
    }

    private static void scoringTests(CardRegistry reg) {
        Game g = emptyGame(reg, "valarians", "monsters");
        Side a = g.sides[0];
        // Tight bond: three Pikemen (4) = 12 each.
        for (int i = 0; i < 3; i++) put(g, 0, "valarians:valarian_pikeman", Row.CLOSE);
        expect(g.rowScore(a, Row.CLOSE) == 36, "bond 3x4 -> 36, got " + g.rowScore(a, Row.CLOSE));
        // Morale: Siege Engineer (1, morale) + Ballista (6) -> 1 + 7.
        put(g, 0, "valarians:valarian_siege_engineer", Row.SIEGE);
        put(g, 0, "valarians:ballista", Row.SIEGE);
        expect(g.rowScore(a, Row.SIEGE) == 8, "morale -> 8, got " + g.rowScore(a, Row.SIEGE));
        // Horn on siege doubles: (1 + 7) * 2 = 16.
        a.special.put(Row.SIEGE, new Card(7777, reg.specialByAbility("horn")));
        expect(g.rowScore(a, Row.SIEGE) == 16, "horn -> 16, got " + g.rowScore(a, Row.SIEGE));
        // Frost on close: pikemen become 1 each, bond 3 -> 3 each = 9.
        g.weather.add(Row.CLOSE);
        expect(g.rowScore(a, Row.CLOSE) == 9, "frost + bond -> 9, got " + g.rowScore(a, Row.CLOSE));
        // Heroes ignore weather.
        put(g, 0, "valarians:valarian_troll", Row.CLOSE);
        expect(g.rowScore(a, Row.CLOSE) == 19, "hero ignores frost -> 19, got " + g.rowScore(a, Row.CLOSE));
        // Horn unit (Stuve, 2) doubles others but not itself.
        Game h = emptyGame(reg, "valarians", "monsters");
        put(h, 0, "neutral:stuve", Row.CLOSE);
        put(h, 0, "valarians:valarian_swordsman", Row.CLOSE);
        expect(h.rowScore(h.sides[0], Row.CLOSE) == 12, "horn unit -> 2 + 10, got " + h.rowScore(h.sides[0], Row.CLOSE));
    }

    private static void abilityTests(CardRegistry reg) {
        // Spy goes to the opponent and draws two.
        Game g = emptyGame(reg, "valarians", "monsters");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        Card spy = give(g, 0, "valarians:valarian_spymaster");
        int deck = g.sides[0].deck.size();
        g.play(0, spy.uid, null, -1);
        expect(g.sides[1].rows.get(Row.CLOSE).contains(spy), "spy on opponent side");
        expect(g.sides[0].hand.size() == 2 && g.sides[0].deck.size() == deck - 2, "spy draws 2");

        // Muster pulls every Zombie from the deck.
        g = emptyGame(reg, "monsters", "valarians");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        Card z = null;
        for (Card c : g.sides[0].deck) if (c.def.key.equals("monsters:zombie")) z = c;
        expect(z != null, "starter has zombies");
        if (z != null) {
            g.sides[0].deck.remove(z);
            g.sides[0].hand.add(z);
            g.play(0, z.uid, null, -1);
            int zombies = 0;
            for (Card c : g.sides[0].rows.get(Row.CLOSE)) if (c.def.key.equals("monsters:zombie")) zombies++;
            expect(zombies == 3, "muster -> 3 zombies, got " + zombies);
        }

        // Scorch kills the strongest non-hero card(s) on both sides.
        g = emptyGame(reg, "valarians", "monsters");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        put(g, 0, "valarians:ballista", Row.SIEGE);
        put(g, 1, "monsters:ghast", Row.SIEGE);
        put(g, 1, "monsters:the_wither", Row.RANGED);
        Card sc = give(g, 0, "special:lightning_strike");
        g.play(0, sc.uid, null, -1);
        expect(g.sides[0].rows.get(Row.SIEGE).isEmpty() && g.sides[1].rows.get(Row.SIEGE).isEmpty(), "scorch kills both 6s");
        expect(g.sides[1].rows.get(Row.RANGED).size() == 1, "scorch spares heroes");

        // Avenger: the Cow becomes the golem when destroyed.
        g = emptyGame(reg, "valarians", "monsters");
        g.phase = Game.Phase.PLAY;
        g.current = 1;
        put(g, 0, "neutral:cow", Row.RANGED);
        put(g, 0, "valarians:valarian_crossbowman", Row.RANGED);
        put(g, 0, "valarians:valarian_crossbowman", Row.RANGED);
        // Only the Cow at 0 strength: make it the strongest by scorching an empty board except cow? Use round end instead.
        g.pass(1);
        g.pass(0);
        boolean golem = false;
        for (Card c : g.sides[0].boardCards()) if (c.def.key.equals("neutral:village_defense_golem")) golem = true;
        expect(golem, "cow summons golem next round");

        // Medic brings back a unit, letting the player choose.
        g = emptyGame(reg, "valarians", "monsters");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        g.sides[0].grave.add(new Card(5555, reg.require("valarians:ballista")));
        Card medic = give(g, 0, "valarians:valarian_field_surgeon");
        g.play(0, medic.uid, null, -1);
        expect(g.phase == Game.Phase.CHOICE && g.choice.kind == Game.ChoiceKind.MEDIC, "medic asks");
        g.choose(0, List.of(5555));
        expect(g.sides[0].rows.get(Row.SIEGE).size() == 2, "medic revived ballista");

        // Berserker + Mardroeme transform.
        g = emptyGame(reg, "islanders", "monsters");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        put(g, 0, "islanders:junkpig", Row.CLOSE);
        Card brew = give(g, 0, "islanders:junk_brew");
        g.play(0, brew.uid, Row.CLOSE, -1);
        expect(g.sides[0].rows.get(Row.CLOSE).get(0).def.key.equals("islanders:junkbrute"), "junkpig -> junkbrute");

        // Decoy returns a unit to hand.
        g = emptyGame(reg, "valarians", "monsters");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        Card target = new Card(4444, reg.require("valarians:ballista"));
        g.sides[0].rows.get(Row.SIEGE).add(target);
        Card decoy = give(g, 0, "special:armor_stand");
        g.play(0, decoy.uid, null, 4444);
        expect(g.sides[0].hand.contains(target), "decoy returns card");

        // Nilfgaard (Illagers) win drawn rounds.
        g = emptyGame(reg, "illagers", "valarians");
        g.phase = Game.Phase.PLAY;
        g.current = 0;
        g.pass(0);
        g.pass(1);
        expect(g.sides[0].gems == 2 && g.sides[1].gems == 1, "illagers win the draw");
    }
}
