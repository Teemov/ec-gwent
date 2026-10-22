package io.github.teemov.ecgwent.server;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.engine.Decks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A player's card collection and decks. Stored in the player's persisted data, which Minecraft keeps across death.
 * Layout: {started, active, owned:{key:count}, decks:{faction:{leader, cards:[key...]}}, npcWins:[id...]}.
 */
public final class Collection {
    private static final String ROOT = "ec_gwent";

    private Collection() {}

    public static CompoundTag get(Player p) {
        CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag tag = persisted.getCompound(ROOT);
        persisted.put(ROOT, tag);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        return tag;
    }

    /** Copies the collection onto the respawned player (the persisted tag normally carries over already). */
    public static void copy(Player from, Player to) {
        CompoundTag old = from.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(ROOT);
        if (!old.isEmpty()) {
            CompoundTag persisted = to.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
            persisted.put(ROOT, old.copy());
            to.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        }
    }

    public static boolean started(Player p) { return get(p).getBoolean("started"); }

    public static Map<String, Integer> owned(Player p) {
        CompoundTag o = get(p).getCompound("owned");
        Map<String, Integer> m = new HashMap<>();
        for (String k : o.getAllKeys()) m.put(k, o.getInt(k));
        return m;
    }

    public static void add(Player p, String key, int n) {
        CompoundTag t = get(p);
        CompoundTag o = t.getCompound("owned");
        o.putInt(key, o.getInt(key) + n);
        t.put("owned", o);
    }

    public static void start(Player p, CardRegistry reg, String faction) {
        CompoundTag t = get(p);
        if (t.getBoolean("started")) return;
        Deck d = Decks.starter(reg, faction);
        for (String k : d.cards) add(p, k, 1);
        add(p, d.leaderKey, 1);
        saveDeck(p, d);
        t.putString("active", faction);
        t.putBoolean("started", true);
    }

    public static void saveDeck(Player p, Deck d) {
        CompoundTag t = get(p);
        CompoundTag decks = t.getCompound("decks");
        CompoundTag dt = new CompoundTag();
        dt.putString("leader", d.leaderKey);
        ListTag l = new ListTag();
        for (String k : d.cards) l.add(StringTag.valueOf(k));
        dt.put("cards", l);
        decks.put(d.faction, dt);
        t.put("decks", decks);
    }

    public static Deck deck(Player p, String faction) {
        CompoundTag dt = get(p).getCompound("decks").getCompound(faction);
        if (dt.isEmpty()) return null;
        List<String> cards = new ArrayList<>();
        ListTag l = dt.getList("cards", Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) cards.add(l.getString(i));
        return new Deck(faction, dt.getString("leader"), cards);
    }

    public static String active(Player p) { return get(p).getString("active"); }

    public static void setActive(Player p, String faction) { get(p).putString("active", faction); }

    /** The deck the player plays with, or null with the reason in {@code why[0]}. */
    public static Deck playable(Player p, CardRegistry reg, String[] why) {
        if (!started(p)) { why[0] = "Open your card book (K) to get your first deck."; return null; }
        Deck d = deck(p, active(p));
        if (d == null) { why[0] = "Build a deck in your card book (K) first."; return null; }
        String err = d.validate(reg, owned(p));
        if (err != null) { why[0] = "Your deck isn't ready: " + err; return null; }
        return d;
    }

    public static boolean beatNpc(Player p, String id) {
        ListTag l = get(p).getList("npcWins", Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) if (l.getString(i).equals(id)) return true;
        return false;
    }

    public static void markNpcBeaten(Player p, String id) {
        CompoundTag t = get(p);
        ListTag l = t.getList("npcWins", Tag.TAG_STRING);
        l.add(StringTag.valueOf(id));
        t.put("npcWins", l);
    }

    public static void reset(Player p) {
        CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.remove(ROOT);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** Whether a card can ever be owned (not leaders-only or summoned-only cards). */
    public static boolean collectible(CardDef d) { return d.count > 0; }
}
