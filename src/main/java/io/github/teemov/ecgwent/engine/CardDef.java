package io.github.teemov.ecgwent.engine;

import java.util.Set;

/** One card as defined in the faction JSON files. Immutable. */
public final class CardDef {
    public enum Type { UNIT, HERO, SPECIAL, WEATHER, LEADER }

    /** Unique key: {@code faction:id}. */
    public final String key;
    public final String id;
    public final String name;
    public final String faction;
    public final Type type;
    /** "close", "ranged", "siege" or "agile" for units; null for other types. */
    public final String placement;
    public final int strength;
    public final Set<String> abilities;
    /** Copies that exist (the deck limit). 0 for cards that can only be summoned. */
    public final int count;
    public final String musterGroup;
    /** Key of the card summoned when this one leaves the board (Avenger). */
    public final String avengerKey;
    /** Key of the card this berserker turns into under Mardroeme. */
    public final String transformKey;
    public final String leaderAbility;
    /** Minecraft entity id drawn on the card. */
    public final String entity;
    /** Easy NPC UUID for story-NPC cards, else null. */
    public final String npc;
    public final String heldItem;
    public final String w3Name;
    /** Optional hand-made card face (texture location) and its pixel size; null = generated card. */
    public String image;
    public int imageW, imageH;

    CardDef(String key, String id, String name, String faction, Type type, String placement, int strength,
            Set<String> abilities, int count, String musterGroup, String avengerKey, String transformKey,
            String leaderAbility, String entity, String npc, String heldItem, String w3Name) {
        this.key = key;
        this.id = id;
        this.name = name;
        this.faction = faction;
        this.type = type;
        this.placement = placement;
        this.strength = strength;
        this.abilities = abilities;
        this.count = count;
        this.musterGroup = musterGroup;
        this.avengerKey = avengerKey;
        this.transformKey = transformKey;
        this.leaderAbility = leaderAbility;
        this.entity = entity;
        this.npc = npc;
        this.heldItem = heldItem;
        this.w3Name = w3Name;
    }

    public boolean has(String ability) { return abilities.contains(ability); }
    public boolean isHero() { return type == Type.HERO; }
    public boolean isUnit() { return type == Type.UNIT || type == Type.HERO; }
    public boolean isAgile() { return "agile".equals(placement); }

    /** The row a non-agile unit goes to. */
    public Row row() { return Row.parse(placement); }

    @Override public String toString() { return name; }
}
