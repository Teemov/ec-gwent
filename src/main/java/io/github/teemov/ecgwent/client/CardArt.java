package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.CardDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Draws cards: frame, live mob model, strength badge and name. */
public final class CardArt {
    private static final Map<String, Entity> cache = new HashMap<>();
    private static final Set<String> broken = new HashSet<>();

    private CardArt() {}

    public static void clearCache() {
        cache.clear();
        broken.clear();
    }

    /**
     * Draws a card. {@code strength} overrides the printed strength (board value); pass Integer.MIN_VALUE for the printed one.
     * Small cards (h < 60) skip the name strip.
     */
    public static void draw(GuiGraphics g, CardDef d, int x, int y, int w, int h, int strength, boolean highlight, float lookX, float lookY) {
        Font font = Minecraft.getInstance().font;
        int frame = Texts.color(d);
        g.fill(x, y, x + w, y + h, frame);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF1C1818);
        boolean big = h >= 60;
        int nameH = big ? Math.max(12, h / 7) : 0;
        int artBottom = y + h - 1 - nameH;
        g.enableScissor(x + 1, y + 1, x + w - 1, artBottom);
        drawSubject(g, entitySpec(d), d.heldItem, x + 1, y + 1, w - 2, artBottom - y - 1, lookX, lookY);
        g.disableScissor();
        if (big) {
            g.fill(x + 1, artBottom, x + w - 1, y + h - 1, d.isHero() ? 0xFF2A2418 : 0xFFECE3D0);
            g.pose().pushPose();
            float s = Math.min(1f, (w - 6f) / Math.max(1, font.width(d.name)));
            s = Math.max(0.5f, s);
            g.pose().translate(x + 3, artBottom + (nameH - 8 * s) / 2f + 1, 0);
            g.pose().scale(s, s, 1);
            g.drawString(font, d.name, 0, 0, d.isHero() ? 0xFFE9C84A : 0xFF1F1A1A, false);
            g.pose().popPose();
        }
        if (d.isUnit()) {
            int value = strength == Integer.MIN_VALUE ? d.strength : strength;
            int bs = Math.max(9, Math.min(16, w / 3));
            int bx = x + 1, by = y + 1;
            g.fill(bx, by, bx + bs, by + bs, d.isHero() ? 0xFF1F1A1A : 0xFFF2ECE0);
            String t = String.valueOf(value);
            int col = d.isHero() ? 0xFFE9C84A : (value > d.strength ? 0xFF1E7A1E : value < d.strength ? 0xFFB02020 : 0xFF1F1A1A);
            g.drawString(font, t, bx + (bs - font.width(t)) / 2 + 1, by + (bs - 8) / 2 + 1, col, false);
        }
        if (highlight) {
            g.fill(x, y, x + w, y + 1, 0xFFFFFF80);
            g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFF80);
            g.fill(x, y, x + 1, y + h, 0xFFFFFF80);
            g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFF80);
        }
    }

    public static String entitySpec(CardDef d) {
        if (d.npc != null) return "npc:" + d.npc + "|" + d.entity;
        return d.entity;
    }

    /** Draws a mob (or an item stand-in) filling the box. Spec: "ns:type", "npc:<uuid>|ns:type", "entity:<id>|ns:type". */
    public static void drawSubject(GuiGraphics g, String spec, String heldItem, int x, int y, int w, int h, float lookX, float lookY) {
        Entity e = entity(spec, heldItem);
        if (e instanceof LivingEntity le && !broken.contains(spec)) {
            float eh = Math.max(0.3f, le.getBbHeight()), ew = Math.max(0.6f, le.getBbWidth());
            int scale = (int) Math.max(2, Math.min(h * 0.82f / eh, w * 0.9f / ew));
            int cx = x + w / 2, by = y + h - 2;
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, by, scale, lookX, lookY, le);
            } catch (RuntimeException ex) {
                broken.add(spec);
                EcGwent.LOGGER.warn("Can't draw {} on a card: {}", spec, ex.toString());
            }
            return;
        }
        ItemStack icon = new ItemStack(iconFor(spec));
        int size = Math.min(w, h);
        float s = Math.max(0.5f, size * 0.7f / 16f);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - 8 * s, y + h / 2f - 8 * s, 0);
        g.pose().scale(s, s, 1);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
    }

    private static Item iconFor(String spec) {
        if (spec.contains("lightning")) return Items.LIGHTNING_ROD;
        if (spec.contains("tornado")) return Items.HEART_OF_THE_SEA;
        if (spec.startsWith("smallships:")) return Items.OAK_BOAT;
        return Items.PAPER;
    }

    private static Entity entity(String spec, String heldItem) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String fallback = spec;
        if (spec.startsWith("npc:") || spec.startsWith("entity:")) {
            String[] parts = spec.split("\\|", 2);
            fallback = parts.length > 1 ? parts[1] : "minecraft:villager";
            Entity live = null;
            if (spec.startsWith("entity:")) {
                try { live = mc.level.getEntity(Integer.parseInt(parts[0].substring(7))); } catch (NumberFormatException ignored) {}
            } else {
                try {
                    UUID id = UUID.fromString(parts[0].substring(4));
                    for (Entity en : mc.level.entitiesForRendering()) if (en.getUUID().equals(id)) { live = en; break; }
                } catch (IllegalArgumentException ignored) {}
            }
            if (live != null) return live;
        }
        String key = fallback + (heldItem == null ? "" : "#" + heldItem);
        if (cache.containsKey(key)) return cache.get(key);
        Entity e = null;
        try {
            e = EntityType.byString(fallback).map(t -> t.create(mc.level)).orElse(null);
            if (e == null && fallback.startsWith("easy_npc:")) e = EntityType.VILLAGER.create(mc.level);
            if (e instanceof LivingEntity le && heldItem != null) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(heldItem));
                if (item != null) le.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
            }
        } catch (RuntimeException ex) {
            EcGwent.LOGGER.warn("Can't create {} for a card: {}", fallback, ex.toString());
        }
        cache.put(key, e);
        return e;
    }
}
