package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Generated decks: starter decks and the random decks villagers play with. */
public final class Decks {
    private Decks() {}

    /**
     * A new player's starter deck: the faction's first leader, its 22 weakest non-hero units,
     * and a few common special cards.
     */
    public static Deck starter(CardRegistry reg, String faction) {
        List<CardDef> units = new ArrayList<>();
        for (CardDef d : reg.ofFaction(faction))
            if (d.type == CardDef.Type.UNIT && d.count > 0)
                for (int i = 0; i < d.count; i++) units.add(d);
        units.sort(Comparator.comparingInt((CardDef d) -> d.strength).thenComparing(d -> d.key));
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < Math.min(Deck.MIN_UNITS, units.size()); i++) cards.add(units.get(i).key);
        for (String ability : List.of("frost", "fog", "horn", "decoy")) {
            CardDef sp = reg.specialByAbility(ability);
            if (sp != null) cards.add(sp.key);
        }
        return new Deck(faction, reg.leadersOf(faction).get(0).key, cards);
    }

    /**
     * A random deck. {@code quality} 0..1: low picks mostly weak cards, high picks strong cards and heroes.
     */
    public static Deck random(CardRegistry reg, String faction, double quality, Random rng) {
        List<CardDef> leaders = reg.leadersOf(faction);
        CardDef leader = leaders.get(rng.nextInt(leaders.size()));
        List<CardDef> pool = new ArrayList<>();
        for (CardDef d : reg.usableBy(faction)) for (int i = 0; i < d.count; i++) pool.add(d);
        List<CardDef> units = new ArrayList<>(), specials = new ArrayList<>();
        for (CardDef d : pool) (d.isUnit() ? units : specials).add(d);

        int unitTarget = Deck.MIN_UNITS + rng.nextInt(6);
        int specialTarget = 2 + rng.nextInt(1 + (int) Math.round(quality * 6));
        List<String> cards = new ArrayList<>();
        cards.addAll(pickWeighted(units, unitTarget, quality, rng));
        cards.addAll(pickWeighted(specials, Math.min(specialTarget, Deck.MAX_SPECIALS), 0.5, rng));
        return new Deck(faction, leader.key, cards);
    }

    /** Picks n cards without replacement, weighting by strength according to quality. */
    private static List<String> pickWeighted(List<CardDef> pool, int n, double quality, Random rng) {
        List<CardDef> left = new ArrayList<>(pool);
        List<String> out = new ArrayList<>();
        Map<String, Integer> taken = new HashMap<>();
        while (out.size() < n && !left.isEmpty()) {
            double total = 0;
            double[] w = new double[left.size()];
            for (int i = 0; i < left.size(); i++) {
                CardDef d = left.get(i);
                double s = d.isUnit() ? Math.max(1, Ai.cardWorth(d)) : 5;
                // quality 0 favours weak cards, 1 favours strong ones
                w[i] = Math.pow(s, 2 * quality - 1) * (d.isHero() ? quality * quality + 0.05 : 1);
                total += w[i];
            }
            double r = rng.nextDouble() * total;
            int pick = 0;
            while (pick < w.length - 1 && (r -= w[pick]) > 0) pick++;
            CardDef d = left.remove(pick);
            taken.merge(d.key, 1, Integer::sum);
            out.add(d.key);
        }
        return out;
    }
}
