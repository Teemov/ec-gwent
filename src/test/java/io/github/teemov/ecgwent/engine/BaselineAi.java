package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The computer opponent. Tries each legal move on a copy of the game, scores the result by points gained
 * versus cards spent, and decides when to pass the way a sensible Witcher 3 player would.
 */
final class BaselineAi {
    /** Rough worth of one card in hand, in points. */
    private static final int CARD_VALUE = 6;

    private BaselineAi() {}

    /** Does whatever this side has to do right now (choice, redraw or turn). Returns false if it had nothing to do. */
    public static boolean act(Game g, int side) {
        switch (g.phase) {
            case CHOOSE_FIRST:
                if (g.choice.side != side) return false;
                g.chooseFirst(side, false); // going second is the advantage
                return true;
            case REDRAW:
                if (g.sides[side].redrawDone) return false;
                redraw(g, side);
                return true;
            case CHOICE:
                if (g.choice.side != side) return false;
                answer(g, side);
                return true;
            case PLAY:
                if (g.current != side) return false;
                g.apply(side, chooseMove(g, side));
                return true;
            default:
                return false;
        }
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

    static Game.Move chooseMove(Game g, int side) {
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
