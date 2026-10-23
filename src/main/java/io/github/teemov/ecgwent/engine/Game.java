package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One Gwent match with Witcher 3 rules. Server-authoritative: clients send actions, the server applies them here.
 * Side 0 and side 1; all methods take the acting side's index and throw {@link IllegalStateException} on an illegal action.
 */
public final class Game {
    public enum Phase { CHOOSE_FIRST, REDRAW, PLAY, CHOICE, OVER }

    /** What a pending choice is for. */
    public enum ChoiceKind {
        /** Orc perk: pick the starting side (option uid 0 = this side, 1 = opponent). */
        FIRST_PLAYER,
        /** Medic: a unit from your graveyard to play. */
        MEDIC,
        /** Leader: a weather card from your deck to play. */
        LEADER_WEATHER,
        /** Leader: a card from your graveyard back to your hand. */
        LEADER_OWN_GRAVE,
        /** Leader: a card from the opponent's graveyard to your hand. */
        LEADER_ENEMY_GRAVE,
        /** Leader: two cards from your hand to discard. */
        DISCARD_TWO,
        /** Leader: one card from your deck to your hand. */
        PICK_FROM_DECK,
        /** Leader: look at these opponent cards, then confirm with no selection. */
        PEEK
    }

    /** A choice the given side must make before play continues. */
    public static final class Choice {
        public final int side;
        public final ChoiceKind kind;
        public final List<Card> options;
        public final int count;

        Choice(int side, ChoiceKind kind, List<Card> options, int count) {
            this.side = side;
            this.kind = kind;
            this.options = options;
            this.count = count;
        }
    }

    public final CardRegistry reg;
    private final Random rng;
    public final Side[] sides = new Side[2];
    public final EnumSet<Row> weather = EnumSet.noneOf(Row.class);
    public Phase phase;
    public int round = 1;
    public int current;
    private int roundStarter;
    /** Who starts round 1 (-1 until decided) and whether a coin toss decided it. */
    public int firstStarter = -1;
    public boolean coinToss;
    /** Emeralds each side put on the match, and whether the higher bet decided who starts. */
    public final int[] bets = new int[2];
    public boolean startByBet;
    public Choice choice;
    /** null while running; -1 for a drawn match, else the winning side. */
    public Integer winner;
    public boolean doubleSpies;
    public boolean randomRevive;
    private int nextUid = 2;
    /** Leader horns are not real cards; they vanish at the end of the round. */
    private final Set<Card> virtualCards = new HashSet<>();
    /** Avenger summons waiting for the next round: card and side. */
    private final List<Object[]> nextRoundSummons = new ArrayList<>();
    /** Messages for the players, in order. Side -1 = both. */
    public final List<String> log = new ArrayList<>();
    public final List<Integer> logSide = new ArrayList<>();

    public Game(CardRegistry reg, Deck a, String nameA, Deck b, String nameB, long seed) {
        this(reg, a, nameA, b, nameB, seed, 0, 0);
    }

    /**
     * A match with bets (duels). The higher bet goes first; equal bets fall back to the normal rules
     * (the Orc perk, else a coin toss).
     */
    public Game(CardRegistry reg, Deck a, String nameA, Deck b, String nameB, long seed, int betA, int betB) {
        this.reg = reg;
        this.rng = new Random(seed);
        Deck[] decks = {a, b};
        String[] names = {nameA, nameB};
        for (int i = 0; i < 2; i++) {
            Side s = new Side(i, names[i], decks[i].faction, reg.get(decks[i].leaderKey));
            sides[i] = s;
            for (CardDef d : decks[i].resolve(reg)) s.deck.add(newCard(d));
            Collections.shuffle(s.deck, rng);
        }
        for (Side s : sides) {
            switch (s.leaderAbility()) {
                case "cancel_enemy_leader": other(s).leaderBlocked = true; s.leaderUsed = true; break;
                case "random_revive": randomRevive = true; s.leaderUsed = true; break;
                case "double_spies": doubleSpies = true; s.leaderUsed = true; break;
                case "extra_draw": case "half_weather_loss": s.leaderUsed = true; break;
                default: break;
            }
        }
        for (Side s : sides) draw(s, "extra_draw".equals(s.leaderAbility()) ? 11 : 10);
        bets[0] = betA;
        bets[1] = betB;
        boolean orc0 = sides[0].perk(reg, "choose_first_player"), orc1 = sides[1].perk(reg, "choose_first_player");
        if (betA != betB) {
            roundStarter = betA > betB ? 0 : 1;
            firstStarter = roundStarter;
            startByBet = true;
            say(-1, sides[roundStarter].name + " bet the most and goes first.");
            phase = Phase.REDRAW;
        } else if (orc0 != orc1) {
            int chooser = orc0 ? 0 : 1;
            phase = Phase.CHOOSE_FIRST;
            choice = new Choice(chooser, ChoiceKind.FIRST_PLAYER, List.of(), 1);
        } else {
            roundStarter = rng.nextBoolean() ? 0 : 1;
            firstStarter = roundStarter;
            coinToss = true;
            say(-1, sides[roundStarter].name + " won the coin toss and goes first.");
            phase = Phase.REDRAW;
        }
    }

    private Game(Game g) {
        reg = g.reg;
        rng = new Random(g.rng.nextLong());
        for (int i = 0; i < 2; i++) sides[i] = g.sides[i].copy();
        weather.addAll(g.weather);
        phase = g.phase;
        round = g.round;
        current = g.current;
        roundStarter = g.roundStarter;
        firstStarter = g.firstStarter;
        coinToss = g.coinToss;
        startByBet = g.startByBet;
        bets[0] = g.bets[0];
        bets[1] = g.bets[1];
        choice = g.choice;
        winner = g.winner;
        doubleSpies = g.doubleSpies;
        randomRevive = g.randomRevive;
        nextUid = g.nextUid;
        virtualCards.addAll(g.virtualCards);
        for (Object[] o : g.nextRoundSummons) nextRoundSummons.add(o.clone());
    }

    /** A copy for the AI to try moves on. */
    public Game copy() { return new Game(this); }

    private Card newCard(CardDef d) { return new Card(nextUid++, d); }

    public Side other(Side s) { return sides[1 - s.index]; }

    private void say(int side, String msg) {
        log.add(msg);
        logSide.add(side);
    }

    private void draw(Side s, int n) {
        for (int i = 0; i < n && !s.deck.isEmpty(); i++) s.hand.add(s.deck.remove(0));
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException(msg);
    }

    // ------------------------------------------------------------------ start of match

    /** Orc perk answer: true = the choosing side starts. */
    public void chooseFirst(int side, boolean meFirst) {
        check(phase == Phase.CHOOSE_FIRST && choice.side == side, "Not your choice");
        roundStarter = meFirst ? side : 1 - side;
        firstStarter = roundStarter;
        choice = null;
        say(-1, sides[side].name + " lets " + sides[roundStarter].name + " go first.");
        phase = Phase.REDRAW;
    }

    /** Swaps one hand card for a random deck card (at most twice, before the first round). */
    public void redraw(int side, int uid) {
        Side s = sides[side];
        check(phase == Phase.REDRAW && !s.redrawDone && s.redrawsLeft > 0, "No redraws left");
        Card c = find(s.hand, uid);
        check(c != null, "Not in hand");
        s.hand.remove(c);
        if (!s.deck.isEmpty()) s.hand.add(s.deck.remove(rng.nextInt(s.deck.size())));
        s.deck.add(rng.nextInt(s.deck.size() + 1), c);
        if (--s.redrawsLeft == 0) finishRedraw(side);
    }

    public void finishRedraw(int side) {
        check(phase == Phase.REDRAW, "Not redrawing");
        sides[side].redrawDone = true;
        if (sides[0].redrawDone && sides[1].redrawDone) {
            phase = Phase.PLAY;
            current = roundStarter;
            say(-1, "Round 1 begins.");
            startTurn();
        }
    }

    // ------------------------------------------------------------------ scoring

    public int strength(Side s, Row row, Card c) {
        CardDef d = c.def;
        if (d.has("decoy")) return 0;
        int total = d.strength;
        if (d.isHero()) return total;
        if (weather.contains(row)) {
            int min = "half_weather_loss".equals(s.leaderAbility()) ? total / 2 : 1;
            total = Math.min(min, total);
        }
        if (doubleSpies && d.has("spy")) total *= 2;
        List<Card> cards = s.rows.get(row);
        if (d.has("bond")) {
            int same = 0;
            for (Card o : cards) if (o.def.key.equals(d.key)) same++;
            if (same > 1) total *= same;
        }
        int morale = 0, horns = 0;
        for (Card o : cards) {
            if (o.def.has("morale")) morale++;
            if (o.def.has("horn")) horns++;
        }
        if (d.has("morale")) morale--;
        if (d.has("horn")) horns--;
        Card sp = s.special.get(row);
        if (sp != null && sp.def.has("horn")) horns++;
        total += Math.max(0, morale);
        if (horns > 0) total *= 2;
        return total;
    }

    public int rowScore(Side s, Row row) {
        int t = 0;
        for (Card c : s.rows.get(row)) t += strength(s, row, c);
        return t;
    }

    public int score(Side s) {
        int t = 0;
        for (Row r : Row.values()) t += rowScore(s, r);
        return t;
    }

    public int score(int side) { return score(sides[side]); }

    // ------------------------------------------------------------------ actions

    /**
     * Plays a hand card. {@code row} is needed for agile units, horns and Mardroeme; {@code target} is the board card for a Decoy.
     */
    public void play(int side, int uid, Row row, int target) {
        Side s = sides[side];
        check(phase == Phase.PLAY && current == side && !s.passed, "Not your turn");
        Card c = find(s.hand, uid);
        check(c != null, "Not in hand");
        CardDef d = c.def;
        if (d.isUnit()) {
            Row r = d.isAgile() ? row : d.row();
            check(r != null && (!d.isAgile() || r == Row.CLOSE || r == Row.RANGED), "Pick close or ranged");
            s.hand.remove(c);
            say(-1, s.name + " plays " + d.name + ".");
            playUnit(s, c, r);
        } else if (d.has("decoy")) {
            Card t = null;
            Row tr = null;
            for (Row r : Row.values()) {
                Card f = find(s.rows.get(r), target);
                if (f != null) { t = f; tr = r; }
            }
            check(t != null && !t.def.isHero() && !t.def.has("decoy"), "Pick one of your non-hero units");
            s.hand.remove(c);
            List<Card> l = s.rows.get(tr);
            l.set(l.indexOf(t), c);
            s.hand.add(t);
            say(-1, s.name + " swaps " + t.def.name + " back to their hand with " + d.name + ".");
        } else if (d.has("horn") || d.has("mardroeme")) {
            check(row != null && s.special.get(row) == null, "That row already has a special card");
            s.hand.remove(c);
            s.special.put(row, c);
            say(-1, s.name + " plays " + d.name + " on the " + row.id() + " row.");
            if (d.has("mardroeme")) transformBerserkers(s, row);
        } else if (d.has("scorch")) {
            s.hand.remove(c);
            say(-1, s.name + " plays " + d.name + ".");
            scorchAll();
            s.grave.add(c);
        } else if (d.type == CardDef.Type.WEATHER) {
            s.hand.remove(c);
            say(-1, s.name + " plays " + d.name + ".");
            playWeather(s, c);
        } else {
            throw new IllegalStateException("Can't play that");
        }
        afterAction();
    }

    public void pass(int side) {
        Side s = sides[side];
        check(phase == Phase.PLAY && current == side && !s.passed, "Not your turn");
        s.passed = true;
        say(-1, s.name + " passes.");
        endTurn();
    }

    public boolean canUseLeader(int side) {
        Side s = sides[side];
        if (s.leader == null || s.leaderUsed || s.leaderBlocked) return false;
        String a = s.leaderAbility();
        if (a.startsWith("play_weather:")) {
            String w = a.substring(13);
            for (Card c : s.deck) if (c.def.type == CardDef.Type.WEATHER && (w.equals("any") || c.def.has(w))) return true;
            return false;
        }
        if (a.startsWith("double_row:")) return s.special.get(Row.parse(a.substring(11))) == null;
        switch (a) {
            case "clear_weather": return !weather.isEmpty();
            case "peek_hand:3": return !other(s).hand.isEmpty();
            case "draw_from_enemy_discard": return !other(s).grave.isEmpty();
            case "restore_discard_to_hand": return !s.grave.isEmpty();
            case "discard_two_pick_one": return s.hand.size() >= 2 && !s.deck.isEmpty();
            default: return true;
        }
    }

    public void useLeader(int side) {
        Side s = sides[side];
        check(phase == Phase.PLAY && current == side && !s.passed && canUseLeader(side), "Leader not available");
        s.leaderUsed = true;
        Side o = other(s);
        String a = s.leaderAbility();
        say(-1, s.name + " uses the leader ability of " + s.leader.name + ".");
        if (a.startsWith("play_weather:")) {
            String w = a.substring(13);
            List<Card> opts = new ArrayList<>();
            for (Card c : s.deck) if (c.def.type == CardDef.Type.WEATHER && (w.equals("any") || c.def.has(w))) opts.add(c);
            if (w.equals("any")) {
                choice = new Choice(side, ChoiceKind.LEADER_WEATHER, opts, 1);
            } else {
                Card c = opts.get(0);
                s.deck.remove(c);
                playWeather(s, c);
            }
        } else if (a.startsWith("double_row:")) {
            Card horn = newCard(reg.specialByAbility("horn"));
            virtualCards.add(horn);
            s.special.put(Row.parse(a.substring(11)), horn);
        } else if (a.startsWith("scorch_row:")) {
            scorchRow(o, Row.parse(a.substring(11)));
        } else {
            switch (a) {
                case "clear_weather": clearWeather(); break;
                case "peek_hand:3": {
                    List<Card> h = new ArrayList<>(o.hand);
                    Collections.shuffle(h, rng);
                    choice = new Choice(side, ChoiceKind.PEEK, new ArrayList<>(h.subList(0, Math.min(3, h.size()))), 0);
                    break;
                }
                case "draw_from_enemy_discard": choice = new Choice(side, ChoiceKind.LEADER_ENEMY_GRAVE, new ArrayList<>(o.grave), 1); break;
                case "restore_discard_to_hand": choice = new Choice(side, ChoiceKind.LEADER_OWN_GRAVE, new ArrayList<>(s.grave), 1); break;
                case "discard_two_pick_one": choice = new Choice(side, ChoiceKind.DISCARD_TWO, new ArrayList<>(s.hand), 2); break;
                case "optimise_agile": optimiseAgile(s); break;
                case "shuffle_graveyards":
                    for (Side x : sides) {
                        x.deck.addAll(x.grave);
                        x.grave.clear();
                        Collections.shuffle(x.deck, rng);
                    }
                    break;
                default: break;
            }
        }
        afterAction();
    }

    /** Answers the pending choice with the chosen option uids. */
    public void choose(int side, List<Integer> uids) {
        check(phase == Phase.CHOICE && choice != null && choice.side == side, "Nothing to choose");
        Choice ch = choice;
        check(uids.size() == ch.count || (ch.kind == ChoiceKind.MEDIC && uids.isEmpty()), "Pick " + ch.count);
        List<Card> picked = new ArrayList<>();
        for (int u : uids) {
            Card c = find(ch.options, u);
            check(c != null && !picked.contains(c), "Invalid pick");
            picked.add(c);
        }
        choice = null;
        phase = Phase.PLAY;
        Side s = sides[side];
        Side o = other(s);
        switch (ch.kind) {
            case MEDIC:
                if (!picked.isEmpty()) revive(s, picked.get(0));
                break;
            case LEADER_WEATHER:
                s.deck.remove(picked.get(0));
                playWeather(s, picked.get(0));
                break;
            case LEADER_OWN_GRAVE:
                s.grave.remove(picked.get(0));
                s.hand.add(picked.get(0));
                break;
            case LEADER_ENEMY_GRAVE:
                o.grave.remove(picked.get(0));
                s.hand.add(picked.get(0));
                break;
            case DISCARD_TWO:
                for (Card c : picked) { s.hand.remove(c); s.grave.add(c); }
                choice = new Choice(side, ChoiceKind.PICK_FROM_DECK, new ArrayList<>(s.deck), 1);
                break;
            case PICK_FROM_DECK:
                s.deck.remove(picked.get(0));
                s.hand.add(picked.get(0));
                break;
            default:
                break;
        }
        afterAction();
    }

    // ------------------------------------------------------------------ resolution

    private void playUnit(Side s, Card c, Row r) {
        CardDef d = c.def;
        Side o = other(s);
        if (d.has("spy")) {
            o.rows.get(r).add(c);
            draw(s, 2);
        } else {
            s.rows.get(r).add(c);
        }
        Side owner = d.has("spy") ? o : s;
        if (d.has("muster") && d.musterGroup != null) {
            List<Card> pulled = new ArrayList<>();
            for (Card x : s.deck) if (d.musterGroup.equals(x.def.musterGroup)) pulled.add(x);
            for (Card x : pulled) {
                s.deck.remove(x);
                Row xr = x.def.isAgile() ? Row.CLOSE : x.def.row();
                s.rows.get(xr).add(x);
                if (x.def.has("berserker")) checkBerserker(s, x, xr);
            }
            if (!pulled.isEmpty()) say(-1, d.name + " calls " + pulled.size() + " more to the battle.");
        }
        if (d.has("scorch_close")) scorchRow(o, Row.CLOSE);
        if (d.has("scorch_ranged")) scorchRow(o, Row.RANGED);
        if (d.has("scorch_siege")) scorchRow(o, Row.SIEGE);
        if (d.has("scorch")) scorchAll();
        if (d.has("mardroeme")) transformBerserkers(owner, r);
        if (d.has("berserker")) checkBerserker(owner, c, r);
        if (d.has("medic")) {
            List<Card> opts = new ArrayList<>();
            for (Card x : s.grave) if (x.def.isUnit() && !x.def.isHero()) opts.add(x);
            if (!opts.isEmpty()) {
                if (randomRevive) revive(s, opts.get(rng.nextInt(opts.size())));
                else choice = new Choice(s.index, ChoiceKind.MEDIC, opts, 1);
            }
        }
    }

    private void revive(Side s, Card c) {
        s.grave.remove(c);
        say(-1, s.name + " brings back " + c.def.name + ".");
        Row r = c.def.isAgile() ? bestAgileRow(s, c) : c.def.row();
        playUnit(s, c, r);
    }

    private Row bestAgileRow(Side s, Card c) {
        s.rows.get(Row.CLOSE).add(c);
        int close = strength(s, Row.CLOSE, c);
        s.rows.get(Row.CLOSE).remove(c);
        s.rows.get(Row.RANGED).add(c);
        int ranged = strength(s, Row.RANGED, c);
        s.rows.get(Row.RANGED).remove(c);
        return ranged > close ? Row.RANGED : Row.CLOSE;
    }

    private void optimiseAgile(Side s) {
        for (Row r : List.of(Row.CLOSE, Row.RANGED)) {
            for (Card c : new ArrayList<>(s.rows.get(r))) {
                if (!c.def.isAgile()) continue;
                s.rows.get(r).remove(c);
                s.rows.get(bestAgileRow(s, c)).add(c);
            }
        }
    }

    private boolean hasMardroeme(Side s, Row r) {
        Card sp = s.special.get(r);
        if (sp != null && sp.def.has("mardroeme")) return true;
        for (Card c : s.rows.get(r)) if (c.def.has("mardroeme")) return true;
        return false;
    }

    private void checkBerserker(Side s, Card c, Row r) {
        if (hasMardroeme(s, r)) transform(s, c, r);
    }

    private void transformBerserkers(Side s, Row r) {
        for (Card c : new ArrayList<>(s.rows.get(r))) if (c.def.has("berserker")) transform(s, c, r);
    }

    private void transform(Side s, Card c, Row r) {
        if (c.def.transformKey == null) return;
        CardDef into = reg.require(c.def.transformKey);
        List<Card> l = s.rows.get(r);
        int i = l.indexOf(c);
        if (i < 0) return;
        Card n = newCard(into);
        l.set(i, n);
        say(-1, c.def.name + " turns into " + into.name + "!");
        Row nr = into.isAgile() || into.row() == null ? r : into.row();
        if (nr != r) {
            l.remove(n);
            s.rows.get(nr).add(n);
        }
    }

    private void playWeather(Side s, Card c) {
        if (c.def.has("clear")) {
            clearWeather();
            s.grave.add(c);
            return;
        }
        s.weatherPlayed.add(c);
        if (c.def.has("frost")) weather.add(Row.CLOSE);
        if (c.def.has("fog")) weather.add(Row.RANGED);
        if (c.def.has("rain")) weather.add(Row.SIEGE);
        if (c.def.has("storm")) { weather.add(Row.RANGED); weather.add(Row.SIEGE); }
    }

    private void clearWeather() {
        weather.clear();
        for (Side x : sides) {
            x.grave.addAll(x.weatherPlayed);
            x.weatherPlayed.clear();
        }
        say(-1, "The weather clears.");
    }

    /** Destroys the strongest non-hero unit(s) in a row if that row totals 10 or more. */
    private void scorchRow(Side target, Row r) {
        if (rowScore(target, r) < 10) return;
        int max = 0;
        for (Card c : target.rows.get(r)) if (!c.def.isHero() && !c.def.has("decoy")) max = Math.max(max, strength(target, r, c));
        if (max <= 0) return;
        for (Card c : new ArrayList<>(target.rows.get(r)))
            if (!c.def.isHero() && !c.def.has("decoy") && strength(target, r, c) == max) destroy(target, r, c);
    }

    /** Destroys the strongest non-hero unit(s) on the whole board. */
    private void scorchAll() {
        int max = 0;
        for (Side x : sides) for (Row r : Row.values()) for (Card c : x.rows.get(r))
            if (!c.def.isHero() && !c.def.has("decoy")) max = Math.max(max, strength(x, r, c));
        if (max <= 0) return;
        List<Object[]> kill = new ArrayList<>();
        for (Side x : sides) for (Row r : Row.values()) for (Card c : x.rows.get(r))
            if (!c.def.isHero() && !c.def.has("decoy") && strength(x, r, c) == max) kill.add(new Object[]{x, r, c});
        for (Object[] k : kill) destroy((Side) k[0], (Row) k[1], (Card) k[2]);
    }

    private void destroy(Side s, Row r, Card c) {
        s.rows.get(r).remove(c);
        toGrave(s, c);
        say(-1, c.def.name + " is destroyed.");
        if (c.def.avengerKey != null) summonAvenger(s, c);
    }

    private void summonAvenger(Side s, Card c) {
        CardDef a = reg.require(c.def.avengerKey);
        Card n = newCard(a);
        s.rows.get(a.isAgile() ? Row.CLOSE : a.row()).add(n);
        say(-1, a.name + " takes its place!");
    }

    private void toGrave(Side s, Card c) {
        if (!virtualCards.remove(c)) s.grave.add(c);
    }

    // ------------------------------------------------------------------ turns and rounds

    private void afterAction() {
        if (choice != null) {
            phase = Phase.CHOICE;
            return;
        }
        endTurn();
    }

    private void endTurn() {
        if (sides[0].passed && sides[1].passed) {
            endRound();
            return;
        }
        Side o = sides[1 - current];
        if (!o.passed) current = o.index;
        startTurn();
    }

    /** Passes automatically for a side that has nothing left to do. */
    private void startTurn() {
        Side s = sides[current];
        if (phase == Phase.PLAY && s.hand.isEmpty() && !canUseLeader(current)) {
            s.passed = true;
            say(-1, s.name + " has no cards left and passes.");
            endTurn();
        }
    }

    private void endRound() {
        int a = score(0), b = score(1);
        sides[0].roundScores.add(a);
        sides[1].roundScores.add(b);
        int winnerSide;
        if (a == b) {
            boolean n0 = sides[0].perk(reg, "win_draws"), n1 = sides[1].perk(reg, "win_draws");
            winnerSide = n0 == n1 ? -1 : (n0 ? 0 : 1);
        } else {
            winnerSide = a > b ? 0 : 1;
        }
        if (winnerSide < 0) {
            sides[0].gems--;
            sides[1].gems--;
            say(-1, "Round " + round + " is a draw (" + a + " – " + b + ").");
        } else {
            sides[1 - winnerSide].gems--;
            say(-1, sides[winnerSide].name + " wins round " + round + " (" + a + " – " + b + ").");
            Side w = sides[winnerSide];
            if (w.perk(reg, "draw_on_round_win")) {
                draw(w, 1);
                say(-1, w.name + " draws a card for winning the round.");
            }
        }
        // Clean the board.
        for (Side s : sides) {
            Card keep = null;
            if (s.perk(reg, "keep_random_unit")) {
                List<Card> units = new ArrayList<>();
                for (Card c : s.boardCards()) if (c.def.isUnit()) units.add(c);
                if (!units.isEmpty()) keep = units.get(rng.nextInt(units.size()));
            }
            for (Row r : Row.values()) {
                for (Card c : new ArrayList<>(s.rows.get(r))) {
                    if (c == keep) continue;
                    s.rows.get(r).remove(c);
                    toGrave(s, c);
                    if (c.def.avengerKey != null) nextRoundSummons.add(new Object[]{s, c});
                }
                Card sp = s.special.remove(r);
                if (sp != null) toGrave(s, sp);
            }
            if (keep != null) say(-1, keep.def.name + " stays on the battlefield.");
        }
        weather.clear();
        for (Side s : sides) { s.grave.addAll(s.weatherPlayed); s.weatherPlayed.clear(); }

        if (sides[0].gems <= 0 || sides[1].gems <= 0 || round == 3) {
            phase = Phase.OVER;
            if (sides[0].gems == sides[1].gems) winner = -1;
            else winner = sides[0].gems > sides[1].gems ? 0 : 1;
            say(-1, winner < 0 ? "The match is a draw." : sides[winner].name + " wins the match!");
            return;
        }
        round++;
        if (winnerSide >= 0) roundStarter = winnerSide;
        current = roundStarter;
        for (Side s : sides) s.passed = false;
        for (Object[] o : nextRoundSummons) summonAvenger((Side) o[0], (Card) o[1]);
        nextRoundSummons.clear();
        if (round == 3) {
            for (Side s : sides) {
                if (!s.perk(reg, "revive_two_in_round_three")) continue;
                List<Card> units = new ArrayList<>();
                for (Card c : s.grave) if (c.def.isUnit() && !virtualCards.contains(c)) units.add(c);
                Collections.shuffle(units, rng);
                for (Card c : units.subList(0, Math.min(2, units.size()))) {
                    s.grave.remove(c);
                    s.rows.get(c.def.isAgile() ? Row.CLOSE : c.def.row()).add(c);
                    say(-1, c.def.name + " returns to the battle for " + s.name + ".");
                }
            }
        }
        say(-1, "Round " + round + " begins. " + sides[current].name + " goes first.");
        phase = Phase.PLAY;
        startTurn();
    }

    // ------------------------------------------------------------------ helpers

    public static Card find(List<Card> l, int uid) {
        for (Card c : l) if (c.uid == uid) return c;
        return null;
    }

    /** All legal moves for the side to act (used by the AI and for validation hints). */
    public List<Move> legalMoves(int side) {
        List<Move> moves = new ArrayList<>();
        Side s = sides[side];
        if (phase != Phase.PLAY || current != side || s.passed) return moves;
        for (Card c : s.hand) {
            CardDef d = c.def;
            if (d.isUnit()) {
                if (d.isAgile()) {
                    moves.add(Move.play(c.uid, Row.CLOSE, -1));
                    moves.add(Move.play(c.uid, Row.RANGED, -1));
                } else {
                    moves.add(Move.play(c.uid, d.row(), -1));
                }
            } else if (d.has("decoy")) {
                for (Card t : s.boardCards())
                    if (!t.def.isHero() && !t.def.has("decoy")) moves.add(Move.play(c.uid, null, t.uid));
            } else if (d.has("horn") || d.has("mardroeme")) {
                for (Row r : Row.values()) if (s.special.get(r) == null) moves.add(Move.play(c.uid, r, -1));
            } else {
                moves.add(Move.play(c.uid, null, -1));
            }
        }
        if (canUseLeader(side)) moves.add(Move.leader());
        moves.add(Move.pass());
        return moves;
    }

    public void apply(int side, Move m) {
        switch (m.kind) {
            case PLAY: play(side, m.uid, m.row, m.target); break;
            case LEADER: useLeader(side); break;
            case PASS: pass(side); break;
            default: throw new IllegalStateException();
        }
    }

    /** A move in the PLAY phase. */
    public static final class Move {
        public enum Kind { PLAY, LEADER, PASS }
        public final Kind kind;
        public final int uid;
        public final Row row;
        public final int target;

        private Move(Kind kind, int uid, Row row, int target) {
            this.kind = kind;
            this.uid = uid;
            this.row = row;
            this.target = target;
        }

        public static Move play(int uid, Row row, int target) { return new Move(Kind.PLAY, uid, row, target); }
        public static Move leader() { return new Move(Kind.LEADER, -1, null, -1); }
        public static Move pass() { return new Move(Kind.PASS, -1, null, -1); }

        @Override public String toString() { return kind + (uid >= 0 ? " " + uid : "") + (row != null ? " " + row : "") + (target >= 0 ? " ->" + target : ""); }
    }
}
