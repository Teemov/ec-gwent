package io.github.teemov.ecgwent.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.engine.Decks;
import io.github.teemov.ecgwent.engine.Json;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Card definitions and NPC opponents, loaded from data packs ({@code data/<ns>/gwent_cards}, {@code gwent_opponents}). */
public final class GwentData {
    private static CardRegistry cards = new CardRegistry();
    /** The raw JSON of each faction file, sent to clients so they know the cards too. */
    private static List<String> cardJson = new ArrayList<>();
    private static Map<String, Opponent> opponents = new LinkedHashMap<>();

    private GwentData() {}

    public static CardRegistry cards() { return cards; }
    public static List<String> cardJson() { return cardJson; }
    public static Map<String, Opponent> opponents() { return opponents; }

    /** A story NPC you can challenge. */
    public static final class Opponent {
        public final String id, name, npc, entity, reward;
        public final Deck deck;

        Opponent(String id, String name, String npc, String entity, String reward, Deck deck) {
            this.id = id;
            this.name = name;
            this.npc = npc;
            this.entity = entity;
            this.reward = reward;
            this.deck = deck;
        }
    }

    public static final class CardsListener extends SimpleJsonResourceReloadListener {
        public CardsListener() { super(new Gson(), "gwent_cards"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
            CardRegistry reg = new CardRegistry();
            List<String> json = new ArrayList<>();
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                try {
                    String text = e.getValue().toString();
                    reg.load(text);
                    json.add(text);
                } catch (RuntimeException ex) {
                    EcGwent.LOGGER.error("Bad card file {}: {}", e.getKey(), ex.toString());
                }
            });
            cards = reg;
            cardJson = json;
            EcGwent.LOGGER.info("Loaded {} Gwent cards", reg.all().size());
        }
    }

    public static final class OpponentsListener extends SimpleJsonResourceReloadListener {
        public OpponentsListener() { super(new Gson(), "gwent_opponents"); }

        @Override
        @SuppressWarnings("unchecked")
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
            Map<String, Opponent> map = new LinkedHashMap<>();
            pending.clear();
            for (Map.Entry<ResourceLocation, JsonElement> e : files.entrySet()) {
                String id = e.getKey().getPath();
                try {
                    Map<String, Object> o = (Map<String, Object>) Json.parse(e.getValue().toString());
                    map.put(id, new Opponent(id, (String) o.get("name"), (String) o.get("npc"),
                            (String) o.getOrDefault("entity", "easy_npc:humanoid"), (String) o.get("reward"), null));
                    pending.put(id, o);
                } catch (RuntimeException ex) {
                    EcGwent.LOGGER.error("Bad opponent file {}: {}", e.getKey(), ex.toString());
                }
            }
            opponents = map;
            EcGwent.LOGGER.info("Loaded {} Gwent opponents", map.size());
        }
    }

    /** Opponent decks are built lazily, because the card files may load after the opponent files. */
    private static final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static Deck deckOf(Opponent op) {
        Map<String, Object> o = pending.get(op.id);
        String faction = (String) o.get("faction");
        if (o.get("cards") instanceof List) {
            List<String> keys = new ArrayList<>();
            for (Object k : (List<Object>) o.get("cards")) keys.add((String) k);
            return new Deck(faction, (String) o.get("leader"), keys);
        }
        double quality = o.get("quality") instanceof Double ? (Double) o.get("quality") : 0.5;
        Deck d = Decks.random(cards, faction, quality, new Random(op.id.hashCode()));
        if (o.get("leader") instanceof String) d = new Deck(faction, (String) o.get("leader"), d.cards);
        return d;
    }

    public static boolean isKnownCard(String key) {
        CardDef d = cards.get(key);
        return d != null;
    }
}
