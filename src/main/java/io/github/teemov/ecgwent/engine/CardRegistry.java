package io.github.teemov.ecgwent.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All cards, loaded from the faction JSON files. */
public final class CardRegistry {
    public static final List<String> PLAYABLE_FACTIONS = List.of("valarians", "illagers", "monsters", "orcs", "islanders");
    /** Factions whose cards any deck may use. */
    public static final Set<String> SHARED = Set.of("neutral", "special");

    private final Map<String, CardDef> cards = new LinkedHashMap<>();
    private final Map<String, String> factionNames = new LinkedHashMap<>();
    private final Map<String, String> perks = new LinkedHashMap<>();

    /** Adds every card from one faction file's JSON text. */
    @SuppressWarnings("unchecked")
    public void load(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        String faction = (String) root.get("faction");
        factionNames.put(faction, (String) root.get("name"));
        perks.put(faction, (String) root.get("perk"));
        for (Object o : (List<Object>) root.get("cards")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String id = (String) c.get("id");
            CardDef.Type type = CardDef.Type.valueOf(((String) c.get("type")).toUpperCase());
            Set<String> abilities = new HashSet<>();
            if (c.get("abilities") != null) for (Object a : (List<Object>) c.get("abilities")) abilities.add((String) a);
            String avenger = (String) c.get("avenger");
            String transform = (String) c.get("transforms");
            CardDef def = new CardDef(faction + ":" + id, id, (String) c.get("name"), faction, type,
                    (String) c.get("row"), c.get("strength") == null ? 0 : ((Double) c.get("strength")).intValue(),
                    Collections.unmodifiableSet(abilities), ((Double) c.get("count")).intValue(),
                    (String) c.get("muster"), avenger == null ? null : faction + ":" + avenger,
                    transform == null ? null : faction + ":" + transform, (String) c.get("ability"),
                    (String) c.get("entity"), (String) c.get("npc"), (String) c.get("item"), (String) c.get("w3"));
            if (c.get("image") instanceof String img) {
                def.image = img;
                List<Object> size = (List<Object>) c.get("image_size");
                def.imageW = size == null ? 144 : ((Double) size.get(0)).intValue();
                def.imageH = size == null ? 200 : ((Double) size.get(1)).intValue();
            }
                        if (cards.put(def.key, def) != null) throw new IllegalStateException("Duplicate card " + def.key);
        }
    }

    public CardDef get(String key) { return cards.get(key); }

    public CardDef require(String key) {
        CardDef d = cards.get(key);
        if (d == null) throw new IllegalArgumentException("Unknown card " + key);
        return d;
    }

    public Collection<CardDef> all() { return cards.values(); }

    public List<CardDef> ofFaction(String faction) {
        List<CardDef> l = new ArrayList<>();
        for (CardDef d : cards.values()) if (d.faction.equals(faction)) l.add(d);
        return l;
    }

    /** Cards a deck of this faction may contain (its own, neutral and special). */
    public List<CardDef> usableBy(String faction) {
        List<CardDef> l = new ArrayList<>();
        for (CardDef d : cards.values())
            if ((d.faction.equals(faction) || SHARED.contains(d.faction)) && d.type != CardDef.Type.LEADER && d.count > 0) l.add(d);
        return l;
    }

    public List<CardDef> leadersOf(String faction) {
        List<CardDef> l = new ArrayList<>();
        for (CardDef d : cards.values()) if (d.faction.equals(faction) && d.type == CardDef.Type.LEADER) l.add(d);
        return l;
    }

    public String factionName(String faction) { return factionNames.getOrDefault(faction, faction); }
    public String perk(String faction) { return perks.get(faction); }

    /** Finds a weather or special card by its ability (e.g. "fog"). */
    public CardDef specialByAbility(String ability) {
        for (CardDef d : cards.values())
            if ((d.type == CardDef.Type.WEATHER || d.type == CardDef.Type.SPECIAL) && d.has(ability)) return d;
        return null;
    }
}
