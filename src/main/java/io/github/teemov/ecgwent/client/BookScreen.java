package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The card book: starter pick, collection and deck builder. */
public final class BookScreen extends Screen {
    private static final int CW = 34, CH = 48, GAP = 3;

    private String faction;
    private String leader;
    private final List<String> deck = new ArrayList<>();
    private int scrollLeft, scrollRight;
    private CardDef hovered;

    public BookScreen() {
        super(Component.literal("Card Book"));
    }

    private static CardRegistry reg() { return ClientHooks.cards; }
    private static CompoundTag book() { return ClientHooks.book; }

    private boolean started() { return book().getBoolean("started"); }

    private Map<String, Integer> owned() {
        Map<String, Integer> m = new HashMap<>();
        CompoundTag o = book().getCompound("owned");
        for (String k : o.getAllKeys()) m.put(k, o.getInt(k));
        return m;
    }

    /** Called when new collection data arrives. */
    public void refresh() {
        String keep = faction;
        if (keep != null && started()) selectFaction(keep);
        else faction = null;
        init(minecraft, width, height);
    }

    @Override
    protected void init() {
        clearWidgets();
        if (!started()) {
            int y = height / 2 - 60;
            for (String f : CardRegistry.PLAYABLE_FACTIONS) {
                String name = reg().factionName(f);
                addRenderableWidget(Button.builder(Component.literal(name), b -> Net.toServer(new Net.PickStarter(f)))
                        .bounds(width / 2 - 150, y, 100, 20).build());
                y += 26;
            }
            return;
        }
        if (faction == null) selectFaction(book().getString("active").isEmpty() ? CardRegistry.PLAYABLE_FACTIONS.get(0) : book().getString("active"));
        int tabW = Math.min(90, (width - 20) / 5);
        int x = (width - tabW * 5) / 2;
        for (String f : CardRegistry.PLAYABLE_FACTIONS) {
            Button b = Button.builder(Component.literal(reg().factionName(f)), btn -> { selectFaction(f); init(minecraft, width, height); })
                    .bounds(x, 4, tabW - 2, 18).build();
            b.active = !f.equals(faction);
            addRenderableWidget(b);
            x += tabW;
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleLeader(-1)).bounds(8, height - 50, 14, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleLeader(1)).bounds(8 + 16 + CW, height - 50, 14, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save deck"), b -> save()).bounds(width - 168, height - 26, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width - 84, height - 26, 76, 20).build());
    }

    private void selectFaction(String f) {
        faction = f;
        deck.clear();
        leader = null;
        scrollLeft = scrollRight = 0;
        CompoundTag dt = book().getCompound("decks").getCompound(f);
        if (!dt.isEmpty()) {
            leader = dt.getString("leader");
            ListTag l = dt.getList("cards", Tag.TAG_STRING);
            for (int i = 0; i < l.size(); i++) deck.add(l.getString(i));
        }
        if (leader == null || leader.isEmpty()) {
            List<CardDef> ls = ownedLeaders();
            leader = ls.isEmpty() ? "" : ls.get(0).key;
        }
    }

    private List<CardDef> ownedLeaders() {
        List<CardDef> l = new ArrayList<>();
        Map<String, Integer> owned = owned();
        for (CardDef d : reg().leadersOf(faction)) if (owned.getOrDefault(d.key, 0) > 0) l.add(d);
        return l;
    }

    private void cycleLeader(int dir) {
        List<CardDef> l = ownedLeaders();
        if (l.isEmpty()) return;
        int i = 0;
        for (int k = 0; k < l.size(); k++) if (l.get(k).key.equals(leader)) i = k;
        leader = l.get(Math.floorMod(i + dir, l.size())).key;
    }

    private void save() {
        Net.toServer(new Net.SaveDeck(faction, leader == null ? "" : leader, new ArrayList<>(deck)));
    }

    private Map<String, Integer> deckCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : deck) m.merge(k, 1, Integer::sum);
        return m;
    }

    /** Cards you could add: owned, usable by this faction. */
    private List<CardDef> collectionList() {
        Map<String, Integer> owned = owned();
        List<CardDef> l = new ArrayList<>();
        for (CardDef d : reg().usableBy(faction)) if (owned.getOrDefault(d.key, 0) > 0) l.add(d);
        l.sort(Comparator.comparing((CardDef d) -> !d.faction.equals(faction)).thenComparing(d -> !d.isUnit())
                .thenComparingInt(d -> -d.strength).thenComparing(d -> d.name));
        return l;
    }

    private List<CardDef> deckList() {
        List<CardDef> l = new ArrayList<>();
        for (String k : deckCounts().keySet()) { CardDef d = reg().get(k); if (d != null) l.add(d); }
        l.sort(Comparator.comparing((CardDef d) -> !d.isUnit()).thenComparingInt(d -> -d.strength).thenComparing(d -> d.name));
        return l;
    }

    private int cols(int panelW) { return Math.max(1, (panelW + GAP) / (CW + GAP)); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        hovered = null;
        if (!started()) {
            g.drawCenteredString(font, "Choose your starting faction", width / 2, height / 2 - 84, 0xFFFFE9A0);
            int y = height / 2 - 60;
            for (String f : CardRegistry.PLAYABLE_FACTIONS) {
                g.drawString(font, Texts.PERK.get(f), width / 2 - 44, y + 6, 0xFFDDDDDD);
                y += 26;
            }
            g.drawCenteredString(font, "You get a starter deck for it. Other cards are won from NPCs and villagers.", width / 2, y + 8, 0xFFAAAAAA);
            super.render(g, mx, my, pt);
            return;
        }
        int half = width / 2;
        int top = 28, bottom = height - 56;
        // Collection
        Map<String, Integer> owned = owned(), inDeck = deckCounts();
        g.drawString(font, "Collection", 8, top, 0xFFFFE9A0);
        List<CardDef> coll = collectionList();
        int lw = half - 12, cols = cols(lw);
        drawGrid(g, coll, 8, top + 12, lw, bottom - top - 12, cols, scrollLeft, mx, my, d -> {
            int free = owned.getOrDefault(d.key, 0) - inDeck.getOrDefault(d.key, 0);
            return "×" + Math.max(0, free);
        });
        // Deck
        g.drawString(font, "Deck", half + 4, top, 0xFFFFE9A0);
        List<CardDef> dl = deckList();
        drawGrid(g, dl, half + 4, top + 12, half - 12, bottom - top - 12, cols(half - 12), scrollRight, mx, my,
                d -> "×" + inDeck.getOrDefault(d.key, 0));
        // Leader
        CardDef ld = reg().get(leader == null ? "" : leader);
        int lx = 8 + 16, ly = height - 52;
        if (ld != null) {
            CardArt.draw(g, ld, lx, ly, CW, CH, Integer.MIN_VALUE, false, lx + CW / 2f - mx, ly + 10 - my);
            if (in(mx, my, lx, ly, CW, CH)) hovered = ld;
            g.drawString(font, ld.name, lx + CW + 20, ly + 4, 0xFFFFFFFF);
            g.drawString(font, Texts.leaderText(ld.leaderAbility), lx + CW + 20, ly + 16, 0xFFBBBBBB);
        } else {
            g.drawString(font, "No leader owned for this faction yet", lx + CW + 20, ly + 4, 0xFFFF8080);
        }
        g.drawString(font, "Faction: " + Texts.PERK.get(faction), lx + CW + 20, ly + 28, 0xFF9999CC);
        // Stats and rule check
        int units = 0, specials = 0, strength = 0;
        for (String k : deck) { CardDef d = reg().get(k); if (d == null) continue; if (d.isUnit()) { units++; strength += d.strength; } else specials++; }
        String err = new Deck(faction, leader == null ? "" : leader, deck).validate(reg(), owned);
        String active = book().getString("active").equals(faction) ? "  (your active deck)" : "";
        g.drawString(font, "Units " + units + "/22+   Specials " + specials + "/10   Strength " + strength + active, half + 4, height - 50, 0xFFFFFFFF);
        g.drawString(font, err == null ? "Deck is legal. Save it to play with it." : err, half + 4, height - 38, err == null ? 0xFF80FF80 : 0xFFFF8080);
        super.render(g, mx, my, pt);
        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name));
            for (String s : Texts.describe(hovered)) tip.add(Component.literal(s).withStyle(net.minecraft.ChatFormatting.GRAY));
            g.renderComponentTooltip(font, tip, mx, my);
        }
    }

    private interface Badge { String of(CardDef d); }

    private void drawGrid(GuiGraphics g, List<CardDef> list, int x, int y, int w, int h, int cols, int scroll, int mx, int my, Badge badge) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x60000000);
        g.enableScissor(x - 2, y - 2, x + w + 2, y + h + 2);
        for (int i = 0; i < list.size(); i++) {
            int cx = x + (i % cols) * (CW + GAP), cy = y + (i / cols) * (CH + GAP + 8) - scroll;
            if (cy + CH + 8 < y || cy > y + h) continue;
            CardDef d = list.get(i);
            boolean hover = in(mx, my, cx, cy, CW, CH) && my >= y && my <= y + h;
            CardArt.draw(g, d, cx, cy, CW, CH, Integer.MIN_VALUE, hover, cx + CW / 2f - mx, cy + 10 - my);
            g.drawCenteredString(font, badge.of(d), cx + CW / 2, cy + CH + 1, 0xFFDDDDDD);
            if (hover) hovered = d;
        }
        g.disableScissor();
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private CardDef cardAt(List<CardDef> list, int x, int y, int w, int h, int scroll, double mx, double my) {
        int cols = cols(w);
        if (my < y || my > y + h) return null;
        for (int i = 0; i < list.size(); i++) {
            int cx = x + (i % cols) * (CW + GAP), cy = y + (i / cols) * (CH + GAP + 8) - scroll;
            if (in(mx, my, cx, cy, CW, CH)) return list.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (!started() || faction == null) return false;
        int half = width / 2, top = 40, h = height - 56 - 40;
        CardDef add = cardAt(collectionList(), 8, top, half - 12, h, scrollLeft, mx, my);
        if (add != null) {
            Map<String, Integer> owned = owned(), inDeck = deckCounts();
            int n = inDeck.getOrDefault(add.key, 0);
            if (n < owned.getOrDefault(add.key, 0) && n < add.count) deck.add(add.key);
            return true;
        }
        CardDef remove = cardAt(deckList(), half + 4, top, half - 12, h, scrollRight, mx, my);
        if (remove != null) {
            deck.remove(remove.key);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int step = (int) (-delta * (CH + GAP + 8));
        if (mx < width / 2.0) scrollLeft = Math.max(0, scrollLeft + step);
        else scrollRight = Math.max(0, scrollRight + step);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
