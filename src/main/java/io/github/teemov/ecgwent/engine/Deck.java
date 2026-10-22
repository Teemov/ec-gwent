package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A deck: faction, leader and card keys (one entry per copy). */
public final class Deck {
    public static final int MIN_UNITS = 22;
    public static final int MAX_SPECIALS = 10;

    public final String faction;
    public final String leaderKey;
    public final List<String> cards;

    public Deck(String faction, String leaderKey, List<String> cards) {
        this.faction = faction;
        this.leaderKey = leaderKey;
        this.cards = List.copyOf(cards);
    }

    /**
     * Checks the Witcher 3 deck rules. {@code owned} limits copies to what the player has (null = no limit).
     * Returns null when the deck is legal, else the reason.
     */
    public String validate(CardRegistry reg, Map<String, Integer> owned) {
        if (!CardRegistry.PLAYABLE_FACTIONS.contains(faction)) return "Unknown faction";
        CardDef leader = reg.get(leaderKey);
        if (leader == null || leader.type != CardDef.Type.LEADER || !leader.faction.equals(faction)) return "Pick a leader for this faction";
        if (owned != null && owned.getOrDefault(leaderKey, 0) < 1 && leader.count > 0) return "You don't own that leader";
        Map<String, Integer> copies = new HashMap<>();
        int units = 0, specials = 0;
        for (String k : cards) {
            CardDef d = reg.get(k);
            if (d == null) return "Unknown card " + k;
            if (d.type == CardDef.Type.LEADER) return "Leaders don't go in the deck";
            if (!d.faction.equals(faction) && !CardRegistry.SHARED.contains(d.faction)) return d.name + " belongs to another faction";
            int n = copies.merge(k, 1, Integer::sum);
            if (n > d.count) return "At most " + d.count + "× " + d.name;
            if (owned != null && n > owned.getOrDefault(k, 0)) return "You don't own enough copies of " + d.name;
            if (d.isUnit()) units++;
            else specials++;
        }
        if (units < MIN_UNITS) return "At least " + MIN_UNITS + " unit cards needed (" + units + ")";
        if (specials > MAX_SPECIALS) return "At most " + MAX_SPECIALS + " special cards (" + specials + ")";
        return null;
    }

    public List<CardDef> resolve(CardRegistry reg) {
        List<CardDef> l = new ArrayList<>();
        for (String k : cards) l.add(reg.require(k));
        return l;
    }
}
