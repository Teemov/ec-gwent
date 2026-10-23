package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Player-facing descriptions and faction colours. */
public final class Texts {
    private Texts() {}

    public static final Map<String, Integer> FACTION_COLOR = Map.of(
            "monsters", 0xFF8A2A2A, "illagers", 0xFF46505E, "valarians", 0xFF2A4A7A, "orcs", 0xFF4A6A2A,
            "islanders", 0xFF2A6A6A, "neutral", 0xFF6A5A3A, "special", 0xFF5A3A6A);
    public static final int HERO_GOLD = 0xFFC9A227;

    /** Card-back colours taken from the card art: frame, side-bar field, symbol. */
    private static final Map<String, int[]> BACK = Map.of(
            "valarians", new int[]{0xFFC4C8D0, 0xFFB86426, 0xFFECE0C6},
            "orcs", new int[]{0xFFC4C8D0, 0xFF7A221E, 0xFFE2D6B4},
            "monsters", new int[]{0xFFC4C8D0, 0xFF482C5C, 0xFFD6E6BE},
            "illagers", new int[]{0xFFC4C8D0, 0xFF383E46, 0xFFE2D6B4},
            "islanders", new int[]{0xFFC4C8D0, 0xFF166E7C, 0xFFE2D6B4},
            "special", new int[]{0xFFE2B46E, 0xFF425064, 0xFFE2B46E},
            "neutral", new int[]{0xFFC4C8D0, 0xFF6A5A3A, 0xFFE2D6B4});

    public static int[] back(String faction) { return BACK.getOrDefault(faction, BACK.get("neutral")); }

    private static final Map<String, String> ABILITY = Map.ofEntries(
            Map.entry("bond", "Tight Bond: strength multiplied by the number of identical cards in its row."),
            Map.entry("morale", "Morale: +1 to every other unit in its row."),
            Map.entry("horn", "Horn: doubles the strength of the row."),
            Map.entry("muster", "Muster: plays every card of its group from your deck."),
            Map.entry("spy", "Spy: goes to your opponent's side and counts for them. You draw 2 cards."),
            Map.entry("medic", "Medic: play a unit from your graveyard."),
            Map.entry("scorch", "Scorch: destroys the strongest card(s) on the board, on both sides."),
            Map.entry("scorch_close", "Scorch – Close: destroys the enemy's strongest close-combat unit(s) if that row totals 10 or more."),
            Map.entry("scorch_ranged", "Scorch – Ranged: destroys the enemy's strongest ranged unit(s) if that row totals 10 or more."),
            Map.entry("scorch_siege", "Scorch – Siege: destroys the enemy's strongest siege unit(s) if that row totals 10 or more."),
            Map.entry("avenger", "Avenger: when it leaves the board, a stronger card takes its place."),
            Map.entry("berserker", "Berserker: transforms when Junk Brew is in its row."),
            Map.entry("mardroeme", "Transforms every berserker in its row."),
            Map.entry("decoy", "Decoy: swap with one of your units on the board to take it back into your hand."),
            Map.entry("frost", "Weather: every close-combat unit drops to 1 (both sides)."),
            Map.entry("fog", "Weather: every ranged unit drops to 1 (both sides)."),
            Map.entry("rain", "Weather: every siege unit drops to 1 (both sides)."),
            Map.entry("storm", "Weather: every ranged and siege unit drops to 1 (both sides)."),
            Map.entry("clear", "Clears all weather."));

    private static final Map<String, String> LEADER = Map.ofEntries(
            Map.entry("play_weather:fog", "Play Thick Fog from your deck."),
            Map.entry("play_weather:rain", "Play Thunderstorm from your deck."),
            Map.entry("play_weather:frost", "Play Snowstorm from your deck."),
            Map.entry("play_weather:any", "Play any weather card from your deck."),
            Map.entry("clear_weather", "Clear all weather."),
            Map.entry("scorch_row:close", "Destroy the enemy's strongest close-combat unit(s) if that row totals 10 or more."),
            Map.entry("scorch_row:ranged", "Destroy the enemy's strongest ranged unit(s) if that row totals 10 or more."),
            Map.entry("scorch_row:siege", "Destroy the enemy's strongest siege unit(s) if that row totals 10 or more."),
            Map.entry("double_row:close", "Double your close-combat row (unless it already has a horn)."),
            Map.entry("double_row:ranged", "Double your ranged row (unless it already has a horn)."),
            Map.entry("double_row:siege", "Double your siege row (unless it already has a horn)."),
            Map.entry("peek_hand:3", "Look at 3 random cards in your opponent's hand."),
            Map.entry("cancel_enemy_leader", "Passive: your opponent's leader can't be used."),
            Map.entry("draw_from_enemy_discard", "Take a card from your opponent's graveyard."),
            Map.entry("random_revive", "Passive: medics revive a random unit (both sides)."),
            Map.entry("restore_discard_to_hand", "Return a card from your graveyard to your hand."),
            Map.entry("discard_two_pick_one", "Discard 2 cards, then pick any card from your deck."),
            Map.entry("double_spies", "Passive: spies are twice as strong (both sides)."),
            Map.entry("extra_draw", "Passive: start the match with an extra card."),
            Map.entry("optimise_agile", "Move your agile units to whichever row makes them strongest."),
            Map.entry("shuffle_graveyards", "Shuffle both graveyards back into the decks."),
            Map.entry("half_weather_loss", "Passive: your units only lose half their strength to weather."));

    public static final Map<String, String> PERK = Map.of(
            "valarians", "Draws a card after each round it wins.",
            "illagers", "Wins any round that ends in a draw.",
            "monsters", "Keeps one random unit on the board after each round.",
            "orcs", "Decides who goes first.",
            "islanders", "Two random units return from the graveyard at the start of round 3.");

    public static int color(CardDef d) {
        if (d.isHero()) return HERO_GOLD;
        return FACTION_COLOR.getOrDefault(d.faction, 0xFF555555);
    }

    public static String rowName(CardDef d) {
        if (d.placement == null) return d.type == CardDef.Type.WEATHER ? "Weather" : d.type == CardDef.Type.LEADER ? "Leader" : "Special";
        switch (d.placement) {
            case "close": return "Close combat";
            case "ranged": return "Ranged";
            case "siege": return "Siege";
            case "agile": return "Close or ranged";
            default: return d.placement;
        }
    }

    public static List<String> describe(CardDef d) {
        List<String> l = new ArrayList<>();
        if (d.type == CardDef.Type.LEADER) {
            l.add("Leader. " + LEADER.getOrDefault(d.leaderAbility, d.leaderAbility));
            return l;
        }
        if (d.isUnit()) l.add(rowName(d) + " · strength " + d.strength);
        if (d.isHero()) l.add("Hero: immune to weather, horns and all other effects.");
        for (String a : d.abilities) if (ABILITY.containsKey(a)) l.add(ABILITY.get(a));
        if (d.count > 1) l.add("Up to " + d.count + " copies per deck.");
        return l;
    }

    public static String leaderText(String ability) { return LEADER.getOrDefault(ability, ability); }
}
