package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.EcGwent;
import io.github.teemov.ecgwent.engine.CardDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Draws cards: frame, faction backdrop, live mob model, strength badge and name.
 * Screens that draw on a scaled canvas call {@link #setCanvas} so clipping lands in the right place.
 */
public final class CardArt {
    private static final Map<String, Entity> cache = new HashMap<>();
    private static final Set<String> broken = new HashSet<>();
    private static float cs = 1, cx = 0, cy = 0;

    /** Height of the z range a card's model can occupy; stack overlapping cards this far apart. */
    public static final int Z_STEP = 250;

    private CardArt() {}

    public static void clearCache() {
        cache.clear();
        fresh.clear();
        broken.clear();
    }

    /** The canvas transform of the current screen (scale and offset in GUI pixels). */
    public static void setCanvas(float scale, float offsetX, float offsetY) {
        cs = scale;
        cx = offsetX;
        cy = offsetY;
    }

    /** Clips to a rectangle given in canvas coordinates. */
    public static void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.enableScissor((int) Math.floor(cx + x1 * cs), (int) Math.floor(cy + y1 * cs), (int) Math.ceil(cx + x2 * cs), (int) Math.ceil(cy + y2 * cs));
    }

    /**
     * Draws a card. {@code strength} overrides the printed strength (board value); Integer.MIN_VALUE = printed.
     * Cards shorter than 52 skip the name strip.
     */
    public static void draw(GuiGraphics g, CardDef d, int x, int y, int w, int h, int strength, boolean highlight, float lookX, float lookY) {
        Font font = Minecraft.getInstance().font;
        if (d.image != null) {
            drawImage(g, font, d, x, y, w, h, strength, highlight);
            return;
        }
        g.fill(x, y, x + w, y + h, highlight ? 0xFFFFE070 : Texts.color(d));
        boolean named = h >= 52;
        int nameH = named ? 11 : 0;
        int artTop = y + 1, artBottom = y + h - 1 - nameH;
        g.fill(x + 1, artTop, x + w - 1, y + h - 1, 0xFF1C1818);
        if (!flat) {
            scissor(g, x + 1, artTop, x + w - 1, artBottom);
            backdrop(g, d.faction, x + 1, artTop, w - 2, artBottom - artTop);
            drawSubject(g, entitySpec(d), d.heldItem, x + 1, artTop, w - 2, artBottom - artTop, lookX, lookY);
            g.disableScissor();
        }
        // Everything printed on the card sits in front of the model.
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        if (named) {
            g.fill(x + 1, artBottom, x + w - 1, y + h - 1, d.isHero() ? 0xFF2A2418 : 0xFFECE3D0);
            small(g, font, d.name, x + 3, artBottom + 2, w - 5, d.isHero() ? 0xFFE9C84A : 0xFF1F1A1A, 0.75f);
        }
        if (d.isUnit()) {
            int value = strength == Integer.MIN_VALUE ? d.strength : strength;
            String t = String.valueOf(value);
            int bs = w >= 40 ? 13 : 10;
            g.fill(x + 1, y + 1, x + 1 + bs, y + 1 + bs, d.isHero() ? 0xFF1F1A1A : 0xFFF2ECE0);
            int col = d.isHero() ? 0xFFE9C84A : (value > d.strength ? 0xFF1E7A1E : value < d.strength ? 0xFFB02020 : 0xFF1F1A1A);
            float fs = w >= 40 ? 1f : 0.75f;
            g.pose().pushPose();
            g.pose().translate(x + 1 + (bs - font.width(t) * fs) / 2f + 0.5f, y + 1 + (bs - 7 * fs) / 2f, 0);
            g.pose().scale(fs, fs, 1);
            g.drawString(font, t, 0, 0, col, false);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    /** A hand-made card face. The printed strength is part of the image; a changed strength gets a badge on top. */
    private static void drawImage(GuiGraphics g, Font font, CardDef d, int x, int y, int w, int h, int strength, boolean highlight) {
        g.blit(new ResourceLocation(d.image), x, y, w, h, 0f, 0f, d.imageW, d.imageH, d.imageW, d.imageH);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        if (highlight) {
            int c = 0xFFFFE070;
            g.fill(x - 1, y - 1, x + w + 1, y, c);
            g.fill(x - 1, y + h, x + w + 1, y + h + 1, c);
            g.fill(x - 1, y, x, y + h, c);
            g.fill(x + w, y, x + w + 1, y + h, c);
        }
        if (d.isUnit() && strength != Integer.MIN_VALUE && strength != d.strength) {
            // Same spot as the printed strength circle (top-left of the face).
            float bs = Math.max(9f, w * 0.2f);
            float bx = x + w * 0.13f - bs / 2f, by = y + h * 0.095f - bs / 2f;
            g.pose().pushPose();
            g.pose().translate(bx, by, 0);
            int b = Math.round(bs);
            g.fill(0, 0, b, b, 0xFF1F1A1A);
            g.fill(1, 1, b - 1, b - 1, strength > d.strength ? 0xFF2E8B2E : 0xFFB02020);
            String t = String.valueOf(strength);
            float fs = Math.min(1f, (b - 2) / (float) Math.max(6, font.width(t)));
            g.pose().translate(b / 2f - font.width(t) * fs / 2f, b / 2f - 3.5f * fs, 0);
            g.pose().scale(fs, fs, 1);
            g.drawString(font, t, 0, 0, 0xFFFFFFFF, false);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    /** Draws text scaled to {@code scale}, shrinking further if it doesn't fit {@code maxW}. */
    public static void small(GuiGraphics g, Font font, String text, float x, float y, int maxW, int color, float scale) {
        float s = Math.min(scale, maxW / (float) Math.max(1, font.width(text)));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** The block each faction's card art is painted on. */
    private static final Map<String, Block> BACKDROP = Map.of(
            "monsters", Blocks.SCULK, "illagers", Blocks.DARK_OAK_PLANKS, "valarians", Blocks.STONE_BRICKS,
            "orcs", Blocks.MOSSY_COBBLESTONE, "islanders", Blocks.PRISMARINE_BRICKS, "neutral", Blocks.SPRUCE_PLANKS,
            "special", Blocks.AMETHYST_BLOCK);

    /** Tiles the faction's block texture over an area, darkened so the mob stands out. Caller sets the clip. */
    public static void backdrop(GuiGraphics g, String faction, int x, int y, int w, int h) {
        Block block = BACKDROP.get(faction);
        if (block == null || w <= 0 || h <= 0) return;
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(block.defaultBlockState());
        int tile = Math.max(8, Math.min(24, w / 2));
        for (int ty = y; ty < y + h; ty += tile)
            for (int tx = x; tx < x + w; tx += tile)
                g.blit(tx, ty, 0, tile, tile, sprite);
        g.fill(x, y, x + w, y + h, 0x99000000);
        g.fillGradient(x, y + h / 2, x + w, y + h, 0x00000000, 0x80000000);
    }

    public static String entitySpec(CardDef d) {
        if (d.npc != null) return "npc:" + d.npc + "|" + d.entity;
        return d.entity;
    }

    /** Draws a mob (or an item stand-in) filling the box. Spec: "ns:type", "npc:<uuid>|ns:type", "entity:<id>|ns:type". */
    /** When true, cards skip their 3D model (used while a page is mid-flip and the drawing is squashed). */
    public static boolean flat;

    /**
     * A head-and-shoulders portrait: the entity is scaled so the top ~0.85 block (around the eyes) fills the box.
     * Caller sets the clip to the box.
     */
    public static void drawPortrait(GuiGraphics g, String spec, int x, int y, int w, int h, float lookX, float lookY) {
        Entity e = entity(spec, null);
        if (!(e instanceof LivingEntity le) || broken.contains(spec)) {
            drawSubject(g, spec, null, x, y, w, h, lookX, lookY);
            return;
        }
        float eye = le.getEyeHeight(), height = le.getBbHeight();
        float top = Math.min(height, eye + 0.38f);
        float span = Math.min(top, 0.85f);
        int scale = (int) Math.max(4, Math.min(h / span, w / Math.max(0.6f, le.getBbWidth() * 1.1f)));
        int feetY = y + 2 + Math.round(top * scale);
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + w / 2, feetY, scale, lookX, lookY, le);
        } catch (RuntimeException ex) {
            broken.add(spec);
        }
    }

    public static void drawSubject(GuiGraphics g, String spec, String heldItem, int x, int y, int w, int h, float lookX, float lookY) {
        if (flat) return;
        Entity e = entity(spec, heldItem);
        if (e instanceof LivingEntity le && !broken.contains(spec)) {
            float eh = Math.max(0.3f, le.getBbHeight()), ew = Math.max(0.6f, le.getBbWidth());
            int scale = (int) Math.max(2, Math.min(h * 0.85f / eh, w * 0.95f / ew));
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + w / 2, y + h - 2, scale, lookX, lookY, le);
            } catch (RuntimeException ex) {
                broken.add(spec);
                EcGwent.LOGGER.warn("Can't draw {} on a card: {}", spec, ex.toString());
            }
            return;
        }
        ItemStack icon = new ItemStack(iconFor(spec));
        float s = Math.max(0.5f, Math.min(w, h) * 0.6f / 16f);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - 8 * s, y + h / 2f - 8 * s, 100);
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
        if (spec.equals("self")) return mc.player;
        if (spec.startsWith("player:")) {
            try {
                return mc.level.getPlayerByUUID(UUID.fromString(spec.substring(7)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        String key = spec + (heldItem == null ? "" : "#" + heldItem);
        if (cache.containsKey(key)) return cache.get(key);
        String type = spec;
        Entity live = null;
        if (spec.startsWith("npc:") || spec.startsWith("entity:")) {
            String[] parts = spec.split("\\|", 2);
            type = parts.length > 1 ? parts[1] : "minecraft:villager";
            if (spec.startsWith("entity:")) {
                try { live = mc.level.getEntity(Integer.parseInt(parts[0].substring(7))); } catch (NumberFormatException ignored) {}
            } else {
                try {
                    UUID id = UUID.fromString(parts[0].substring(4));
                    for (Entity en : mc.level.entitiesForRendering()) if (en.getUUID().equals(id)) { live = en; break; }
                } catch (IllegalArgumentException ignored) {}
            }
            // Not loaded yet: draw the fallback for now, but try the real one again next time.
            if (live == null) return create(type, heldItem);
        }
        Entity e = live != null ? copy(live) : create(type, heldItem);
        cache.put(key, e);
        return e;
    }

    /** A detached copy of a real entity: same look, without its name tag or other mods' data (no health bars). */
    private static Entity copy(Entity live) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Entity e = live.getType().create(mc.level);
            if (e == null) return null;
            CompoundTag tag = live.saveWithoutId(new CompoundTag());
            tag.remove("ForgeCaps");
            tag.remove("CustomName");
            tag.remove("Passengers");
            e.load(tag);
            e.setCustomName(null);
            e.setCustomNameVisible(false);
            return e;
        } catch (RuntimeException ex) {
            EcGwent.LOGGER.warn("Can't copy {} for a card: {}", live, ex.toString());
            return create(EntityType.getKey(live.getType()).toString(), null);
        }
    }

    private static final Map<String, Entity> fresh = new HashMap<>();

    private static Entity create(String type, String heldItem) {
        String key = type + (heldItem == null ? "" : "#" + heldItem);
        if (fresh.containsKey(key)) return fresh.get(key);
        Minecraft mc = Minecraft.getInstance();
        Entity e = null;
        try {
            e = EntityType.byString(type).map(t -> t.create(mc.level)).orElse(null);
            if (e == null && type.startsWith("easy_npc:")) e = EntityType.VILLAGER.create(mc.level);
            if (e instanceof LivingEntity le && heldItem != null) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(heldItem));
                if (item != null) le.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
            }
        } catch (RuntimeException ex) {
            EcGwent.LOGGER.warn("Can't create {} for a card: {}", type, ex.toString());
        }
        fresh.put(key, e);
        return e;
    }
}
