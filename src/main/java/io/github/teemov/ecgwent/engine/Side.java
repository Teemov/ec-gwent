package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One player's half of a match. */
public final class Side {
    public final int index;
    public final String name;
    public final String faction;
    public final CardDef leader;

    public boolean leaderUsed;
    /** Set by the opponent's "cancel leader" leader. */
    public boolean leaderBlocked;
    public final List<Card> deck = new ArrayList<>();
    public final List<Card> hand = new ArrayList<>();
    public final List<Card> grave = new ArrayList<>();
    public final Map<Row, List<Card>> rows = new EnumMap<>(Row.class);
    /** The horn / Mardroeme slot of each row. */
    public final Map<Row, Card> special = new EnumMap<>(Row.class);
    /** Weather cards this side played that are still in effect. */
    public final List<Card> weatherPlayed = new ArrayList<>();
    public int gems = 2;
    public boolean passed;
    public int redrawsLeft = 2;
    public boolean redrawDone;
    public final List<Integer> roundScores = new ArrayList<>();

    Side(int index, String name, String faction, CardDef leader) {
        this.index = index;
        this.name = name;
        this.faction = faction;
        this.leader = leader;
        for (Row r : Row.values()) rows.put(r, new ArrayList<>());
    }

    Side copy() {
        Side s = new Side(index, name, faction, leader);
        s.leaderUsed = leaderUsed;
        s.leaderBlocked = leaderBlocked;
        s.deck.addAll(deck);
        s.hand.addAll(hand);
        s.grave.addAll(grave);
        for (Row r : Row.values()) s.rows.get(r).addAll(rows.get(r));
        s.special.putAll(special);
        s.weatherPlayed.addAll(weatherPlayed);
        s.gems = gems;
        s.passed = passed;
        s.redrawsLeft = redrawsLeft;
        s.redrawDone = redrawDone;
        s.roundScores.addAll(roundScores);
        return s;
    }

    public boolean perk(CardRegistry reg, String perk) { return perk.equals(reg.perk(faction)); }

    public String leaderAbility() { return leader == null ? "" : leader.leaderAbility; }

    /** Every card on this side of the board. */
    public List<Card> boardCards() {
        List<Card> l = new ArrayList<>();
        for (Row r : Row.values()) l.addAll(rows.get(r));
        return l;
    }

    public Row rowOf(Card c) {
        for (Row r : Row.values()) if (rows.get(r).contains(c)) return r;
        return null;
    }
}
