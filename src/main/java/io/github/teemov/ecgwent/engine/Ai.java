package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The computer opponent. Tries each legal move on a copy of the game, scores the result by points gained
 * versus cards spent, and decides when to pass the way a sensible Witcher 3 player would.
 */
public final class Ai {
    /** Rough worth of one card in hand, in points. */
    private static final int CARD_VALUE = 6;

    private Ai() {}

    /** How well the computer plays. */
    public enum Difficulty {
        EASY, MODERATE, HARD;

        public static Difficulty parse(String s, Difficulty fallback) {
            if (s == null) return fallback;
            switch (s.toLowerCase()) {
                case "easy": return EASY;
                case "moderate": case "medium": case "normal": return MODERATE;
                case "hard": return HARD;
                default: return fallback;
            }
        }

        /** Villager trade levels: novice/apprentice easy, journeyman moderate, expert/master hard. */
        public static Difficulty forVillagerLevel(int level) {
            return level <= 2 ? EASY : level == 3 ? MODERATE : HARD;
        }

        public String id() { return name().toLowerCase(); }
    }

    /** Does whatever this side has to do right now (hard difficulty). */
    public static boolean act(Game g, int side) {
        return act(g, side, Difficulty.HARD, ThreadLocalRandom.current());
    }

    /** Does whatever this side has to do right now (choice, redraw or turn). Returns false if it had nothing to do. */
    public static boolean act(Game g, int side, Difficulty level, Random rnd) {
        switch (g.phase) {
            case CHOOSE_FIRST:
                if (g.choice.side != side) return false;
                g.chooseFirst(side, level == Difficulty.EASY && rnd.nextBoolean()); // going second is the advantage
                return true;
            case REDRAW:
                if (g.sides[side].redrawDone) return false;
                if (level == Difficulty.EASY) g.finishRedraw(side);
                else redraw(g, side);
                return true;
            case CHOICE:
                if (g.choice.side != side) return false;
                if (level == Difficulty.EASY && g.choice.count > 0 && rnd.nextInt(2) == 0) answerRandom(g, side, rnd);
                else answer(g, side);
                return true;
            case PLAY:
                if (g.current != side) return false;
                g.apply(side, chooseMove(g, side, level, rnd));
                return true;
            default:
                return false;
        }
    }

    static Game.Move chooseMove(Game g, int side, Difficulty level, Random rnd) {
        switch (level) {
            case EASY: {
                if (rnd.nextInt(100) < 35) {
                    List<Game.Move> moves = new ArrayList<>(g.legalMoves(side));
                    moves.removeIf(m -> m.kind == Game.Move.Kind.PASS || m.kind == Game.Move.Kind.LEADER);
                    if (!moves.isEmpty()) return moves.get(rnd.nextInt(moves.size()));
                }
                return chooseGreedy(g, side);
            }
            case MODERATE:
                return rnd.nextInt(100) < 30 ? chooseGreedy(g, side) : chooseMove(g, side);
            default:
                return chooseMove(g, side);
        }
    }

    private static void answerRandom(Game g, int side, Random rnd) {
        List<Card> opts = new ArrayList<>(g.choice.options);
        java.util.Collections.shuffle(opts, rnd);
        List<Integer> picks = new ArrayList<>();
        for (int i = 0; i < g.choice.count && i < opts.size(); i++) picks.add(opts.get(i).uid);
        g.choose(side, picks);
    }

    private static void redraw(Game g, int side) {
        Side s = g.sides[side];
        List<Card> weak = new ArrayList<>();
        for (Card c : s.hand)
            if (c.def.type == CardDef.Type.UNIT && c.def.abilities.isEmpty() && c.def.strength <= 2) weak.add(c);
        for (int i = 0; i < weak.size() && s.redrawsLeft > 0 && !s.redrawDone; i++) g.redraw(side, weak.get(i).uid);
        if (!s.redrawDone) g.finishRedraw(side);
    }

    static int cardWorth(CardDef d) {
        int v = d.strength;
        if (d.has("spy")) v = 20;
        if (d.has("medic")) v += 6;
        if (d.has("muster")) v += 4;
        return v;
    }

    private static void answer(Game g, int side) {
        Game.Choice ch = g.choice;
        List<Card> opts = new ArrayList<>(ch.options);
        switch (ch.kind) {
            case PEEK:
                g.choose(side, List.of());
                return;
            case DISCARD_TWO: {
                opts.sort(Comparator.comparingInt(c -> cardWorth(c.def)));
                g.choose(side, List.of(opts.get(0).uid, opts.get(1).uid));
                return;
            }
            case LEADER_WEATHER: {
                Card best = null;
                int bestVal = Integer.MIN_VALUE;
                for (Card c : opts) {
                    Game t = g.copy();
                    t.choose(side, List.of(c.uid));
                    int v = t.score(side) - t.score(1 - side);
                    if (v > bestVal) { bestVal = v; best = c; }
                }
                g.choose(side, List.of(best.uid));
                return;
            }
            default: {
                opts.sort(Comparator.comparingInt(c -> -cardWorth(c.def)));
                g.choose(side, List.of(opts.get(0).uid));
            }
        }
    }

    /** Value of having made a move: points gained relative to the opponent, plus card advantage. */
    private static int evaluate(Game before, Game after, int side) {
        Side b = before.sides[side], bo = before.sides[1 - side];
        Side a = after.sides[side], ao = after.sides[1 - side];
        int tempo = (after.score(side) - after.score(1 - side)) - (before.score(side) - before.score(1 - side));
        int cards = (a.hand.size() - ao.hand.size()) - (b.hand.size() - bo.hand.size());
        return tempo + CARD_VALUE * cards;
    }

    /** Applies a move on a copy and lets the AI finish any choice it triggers. */
    private static Game simulate(Game g, int side, Game.Move m) {
        Game t = g.copy();
        t.apply(side, m);
        int guard = 0;
        while (t.phase == Game.Phase.CHOICE && t.choice.side == side && guard++ < 10) answer(t, side);
        return t;
    }

    /** How much a card is worth keeping for later rounds. Spies are free (they draw cards), heroes are saved. */
    static int keepValue(CardDef d) {
        if (d.has("spy")) return -8;
        int v = d.strength;
        if (d.isHero()) v += 4;
        if (d.has("medic")) v += 3;
        if (d.has("muster")) v += 2;
        if (d.type == CardDef.Type.WEATHER) v = 4;
        if (d.has("scorch") && !d.isUnit()) v = 7;
        if (d.has("horn") && !d.isUnit()) v = 6;
        if (d.has("decoy")) v = 4;
        return v;
    }

    private static int cost(Game g, int side, Game.Move m) {
        if (m.kind == Game.Move.Kind.LEADER) return g.round == 3 || g.sides[side].gems == 1 ? 0 : 5;
        Card c = Game.find(g.sides[side].hand, m.uid);
        return c == null ? 0 : keepValue(c.def);
    }

    private record Scored(Game.Move move, int value, int newDiff, int cost) {}

    /**
     * Picks a move with the whole match in mind: the aim is to win two rounds, and the card that matters is the one
     * still in hand for the deciding round. Round 1 is won cheaply or given up cheaply; a won round is followed by
     * an early pass; a round that decides the match is fought with everything.
     */
    static Game.Move chooseMove(Game g, int side) {
        Side me = g.sides[side], op = g.sides[1 - side];
        int diff = g.score(side) - g.score(1 - side);
        boolean winsDraws = me.perk(g.reg, "win_draws") && !op.perk(g.reg, "win_draws");
        boolean ahead = diff > 0 || (diff == 0 && winsDraws);
        boolean mustWin = me.gems == 1;            // losing this round loses the match
        boolean decisive = mustWin || op.gems == 1; // winning or losing this round decides a lot
        int myCards = me.hand.size(), opCards = op.hand.size();

        List<Game.Move> moves = new ArrayList<>(g.legalMoves(side));
        moves.removeIf(m -> m.kind == Game.Move.Kind.PASS);
        if (moves.isEmpty()) return Game.Move.pass();

        List<Scored> scored = new ArrayList<>();
        for (Game.Move m : moves) {
            Game t;
            try {
                t = simulate(g, side, m);
            } catch (IllegalStateException e) {
                continue;
            }
            int v = evaluate(g, t, side);
            int nd = t.phase == Game.Phase.OVER || t.round != g.round ? diff : t.score(side) - t.score(1 - side);
            int c = cost(g, side, m);
            // Outside the deciding round, keeping strong cards matters as much as the points they bring now.
            if (!mustWin) v -= Math.max(0, c - 4) / (decisive ? 3 : 2);
            if (m.kind == Game.Move.Kind.LEADER && !decisive) v -= 4;
            scored.add(new Scored(m, v, nd, c));
        }
        if (scored.isEmpty()) return Game.Move.pass();
        scored.sort((x, y) -> Integer.compare(y.value, x.value));
        Scored best = scored.get(0);
        Scored spy = null;
        for (Scored s : scored) if (s.cost < 0 && (spy == null || s.value > spy.value)) spy = s;

        // ---- The opponent has passed: take the round only if it's worth the cards.
        if (op.passed) {
            if (ahead) return Game.Move.pass();
            Scored cheapest = cheapestLead(scored, winsDraws);
            if (cheapest != null) return cheapest.move;
            int budget = decisive ? 99 : Math.max(1, myCards - opCards + 1);
            Game.Move first = greedyWin(g, side, budget, winsDraws);
            return first != null ? first : Game.Move.pass();
        }

        // ---- The round decides the match: fight, but don't throw cards away.
        if (mustWin) {
            if (ahead && opCards == 0) return Game.Move.pass();
            if (!ahead) {
                Scored cheapest = cheapestLead(scored, winsDraws);
                if (cheapest != null) return cheapest.move;
            }
            return best.move;
        }

        // ---- We already won a round: make the opponent spend cards. Spies first, then pass.
        if (op.gems == 1) {
            if (spy != null) return spy.move;
            if (myCards >= opCards - 1 || ahead) return Game.Move.pass();
            return cheapestUseful(scored, best).move;
        }

        // ---- Round 1 (both still on two gems): win it cheaply or give it up cheaply.
        if (spy != null && spy.value > 0) return spy.move;
        int cardEdge = myCards - opCards;
        if (myCards <= 3) return Game.Move.pass();      // never empty the hand before the decisive rounds
        if (ahead && cardEdge >= 0) return Game.Move.pass();          // they must spend to win it
        if (ahead && diff >= 12 && cardEdge >= -1) return Game.Move.pass();
        if (!ahead && cardEdge <= -2) return Game.Move.pass();        // already overspent: let it go
        if (!ahead) {
            Scored cheapest = cheapestLead(scored, winsDraws);
            if (cheapest != null) return cheapest.move;
            // Can't take the lead with one card: only keep going if it's close.
            if (-diff > 15) return Game.Move.pass();
        }
        return cheapestUseful(scored, best).move;
    }

    /** The cheapest move (by keep value) that puts us in the lead. */
    private static Scored cheapestLead(List<Scored> scored, boolean winsDraws) {
        Scored pick = null;
        for (Scored s : scored) {
            boolean leads = s.newDiff > 0 || (s.newDiff == 0 && winsDraws);
            if (leads && (pick == null || s.cost < pick.cost || (s.cost == pick.cost && s.value > pick.value))) pick = s;
        }
        return pick;
    }

    /** A reasonable cheap move: among moves almost as good as the best, the one that spends the least. */
    private static Scored cheapestUseful(List<Scored> scored, Scored best) {
        Scored pick = best;
        for (Scored s : scored) if (s.value >= best.value - 3 && s.cost < pick.cost) pick = s;
        return pick;
    }

    /** First move of the shortest greedy line that takes the lead within {@code budget} cards (opponent has passed). */
    private static Game.Move greedyWin(Game g, int side, int budget, boolean winsDraws) {
        Game t = g.copy();
        Game.Move first = null;
        for (int step = 0; step < budget; step++) {
            if (t.phase != Game.Phase.PLAY || t.current != side) return null;
            Game.Move bestMove = null;
            Game bestGame = null;
            int bestDiff = Integer.MIN_VALUE;
            for (Game.Move m : t.legalMoves(side)) {
                if (m.kind == Game.Move.Kind.PASS) continue;
                Game u;
                try {
                    u = simulate(t, side, m);
                } catch (IllegalStateException e) {
                    continue;
                }
                if (u.phase == Game.Phase.OVER || u.round != t.round) continue;
                int d = u.score(side) - u.score(1 - side);
                if (d > bestDiff) { bestDiff = d; bestMove = m; bestGame = u; }
            }
            if (bestMove == null) return null;
            if (first == null) first = bestMove;
            if (bestDiff > 0 || (bestDiff == 0 && winsDraws)) return first;
            t = bestGame;
        }
        return null;
    }
    /** The original greedy AI (chases every round): the core of the easy level. */
    private static Game.Move chooseGreedy(Game g, int side) {
        Side me = g.sides[side], op = g.sides[1 - side];
        int diff = g.score(side) - g.score(1 - side);
        boolean winsDraws = me.perk(g.reg, "win_draws") && !op.perk(g.reg, "win_draws");
        boolean ahead = diff > 0 || (diff == 0 && winsDraws);
        boolean mustWin = me.gems == 1; // losing this round loses the match
        int myCards = me.hand.size(), opCards = op.hand.size();

        List<Game.Move> moves = new ArrayList<>(g.legalMoves(side));
        moves.removeIf(m -> m.kind == Game.Move.Kind.PASS);
        if (moves.isEmpty()) return Game.Move.pass();

        // Score every move once.
        List<Object[]> scored = new ArrayList<>();
        for (Game.Move m : moves) {
            Game t;
            try {
                t = simulate(g, side, m);
            } catch (IllegalStateException e) {
                continue;
            }
            int v = evaluate(g, t, side);
            if (m.kind == Game.Move.Kind.LEADER && g.round == 1) v -= 3; // keep the leader for later if it's no better
            int newDiff = t.score(side) - t.score(1 - side);
            if (t.phase == Game.Phase.OVER || t.round != g.round) newDiff = diff; // move ended the round
            scored.add(new Object[]{m, v, newDiff});
        }
        if (scored.isEmpty()) return Game.Move.pass();
        scored.sort((x, y) -> Integer.compare((int) y[1], (int) x[1]));
        Game.Move best = (Game.Move) scored.get(0)[0];

        if (op.passed) {
            if (ahead) return Game.Move.pass();
            // Cheapest move that takes the lead.
            Object[] cheapest = null;
            for (Object[] s : scored) {
                int nd = (int) s[2];
                if (nd > 0 || (nd == 0 && winsDraws)) {
                    if (cheapest == null || (int) s[1] > (int) cheapest[1]) cheapest = s;
                }
            }
            if (cheapest != null) return (Game.Move) cheapest[0];
            // Can we still catch up at all?
            int potential = 0;
            for (Card c : me.hand) if (c.def.isUnit()) potential += c.def.strength;
            if (potential + diff <= 0 && !mustWin) return Game.Move.pass();
            return best;
        }

        if (!mustWin) {
            // Comfortable lead and no card disadvantage: make the opponent spend cards.
            if (diff >= 15 && myCards >= opCards - 1) return Game.Move.pass();
            // Hopeless first round: save cards for the next ones.
            if (g.round == 1 && diff <= -25 && myCards >= opCards) return Game.Move.pass();
            // Already won a round: don't throw cards at a losing round.
            if (op.gems == 1 && diff <= -15) return Game.Move.pass();
        } else if (ahead && opCards == 0) {
            return Game.Move.pass();
        }
        return best;
    }
}
