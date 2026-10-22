package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The card book: starter pick, collection and deck builder. */
public final class BookScreen extends CanvasScreen {
    private static final int CW = 38, CH = 53, GAP = 5, ROW_H = CH + 12;
    private static final int GRID_Y = 44, GRID_H = 216;
    private static final int LEFT_X = 10, RIGHT_X = 326, PANEL_W = 304;

    private String faction;
    private String leader;
    private final List<String> deck = new ArrayList<>();
    private int scrollLeft, scrollRight;

    public BookScreen() {
        super("Card Book");
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

    /** New collection data arrived (after a save or the starter pick). */
    public void refresh() {
        if (faction != null && started()) selectFaction(faction);
    }

    @Override
    protected void init() {
        if (faction == null && started()) {
            String active = book().getString("active");
            selectFaction(active.isEmpty() ? CardRegistry.PLAYABLE_FACTIONS.get(0) : active);
        }
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

    private Map<String, Integer> deckCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : deck) m.merge(k, 1, Integer::sum);
        return m;
    }

    private List<CardDef> collectionList() {
        Map<String, Integer> owned = owned();
        List<CardDef> l = new ArrayList<>();
        for (CardDef d : reg().usableBy(faction)) if (owned.getOrDefault(d.key, 0) > 0) l.add(d);
        l.sort(order());
        return l;
    }

    private List<CardDef> deckList() {
        List<CardDef> l = new ArrayList<>();
        for (String k : deckCounts().keySet()) { CardDef d = reg().get(k); if (d != null) l.add(d); }
        l.sort(order());
        return l;
    }

    /** Faction units first (strongest first), then neutral units, then specials. */
    private Comparator<CardDef> order() {
        return Comparator.comparing((CardDef d) -> !d.isUnit()).thenComparing(d -> !d.faction.equals(faction))
                .thenComparingInt(d -> -d.strength).thenComparing(d -> d.name);
    }

    private static int cols() { return (PANEL_W - 8 + GAP) / (CW + GAP); }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        if (!started()) { drawStarter(g, mx, my); return; }

        // Header: title and faction tabs
        text(g, "Card Book", LEFT_X, 11, 0xFFFFE9A0);
        int tabX = 110, tabW = 102;
        for (String f : CardRegistry.PLAYABLE_FACTIONS) {
            String label = reg().factionName(f) + (book().getString("active").equals(f) ? " ★" : "");
            button(g, tabX, 6, tabW - 4, 18, label, !f.equals(faction), mx, my, () -> selectFaction(f));
            if (f.equals(faction)) g.fill(tabX, 24, tabX + tabW - 4, 26, Texts.FACTION_COLOR.get(f) | 0xFF000000);
            tabX += tabW;
        }

        Map<String, Integer> owned = owned(), inDeck = deckCounts();
        // Collection
        panel(g, LEFT_X, 30, PANEL_W, GRID_H + 20);
        text(g, "Collection", LEFT_X + 6, 34, 0xFFFFE9A0);
        small(g, "click to add", LEFT_X + 70, 35, 100, 0xFF999999);
        List<CardDef> coll = collectionList();
        drawGrid(g, coll, LEFT_X + 4, scrollLeft, mx, my, d -> {
            int left = owned.getOrDefault(d.key, 0) - inDeck.getOrDefault(d.key, 0);
            boolean capped = inDeck.getOrDefault(d.key, 0) >= d.count;
            return left <= 0 ? null : (capped ? "max" : left + " left");
        });
        // Deck
        panel(g, RIGHT_X, 30, PANEL_W, GRID_H + 20);
        text(g, "Deck", RIGHT_X + 6, 34, 0xFFFFE9A0);
        small(g, "click to remove", RIGHT_X + 36, 35, 100, 0xFF999999);
        drawGrid(g, deckList(), RIGHT_X + 4, scrollRight, mx, my, d -> "×" + inDeck.getOrDefault(d.key, 0));

        // Leader
        panel(g, LEFT_X, 272, PANEL_W, 82);
        text(g, "Leader", LEFT_X + 6, 276, 0xFFFFE9A0);
        CardDef ld = reg().get(leader == null ? "" : leader);
        int lx = LEFT_X + 22, ly = 288;
        button(g, LEFT_X + 6, ly + 16, 14, 20, "<", ownedLeaders().size() > 1, mx, my, () -> cycleLeader(-1));
        button(g, lx + 38, ly + 16, 14, 20, ">", ownedLeaders().size() > 1, mx, my, () -> cycleLeader(1));
        if (ld != null) {
            CardArt.draw(g, ld, lx, ly, 36, 52, Integer.MIN_VALUE, false, lx + 18 - mx, ly + 10 - my);
            if (in(mx, my, lx, ly, 36, 52)) preview(ld, Integer.MIN_VALUE);
            text(g, ld.name.contains("–") ? ld.name.substring(ld.name.indexOf('–') + 2) : ld.name, lx + 58, ly - 8, 0xFFFFFFFF);
            wrapped(g, Texts.leaderText(ld.leaderAbility), lx + 58, ly + 4, PANEL_W - 86, 0xFFCCCCCC);
        } else {
            wrapped(g, "You don't own a leader for this faction yet.", lx + 58, ly + 4, PANEL_W - 86, 0xFFFF8080);
        }
        wrapped(g, "Faction perk: " + Texts.PERK.get(faction), lx + 58, ly + 34, PANEL_W - 86, 0xFFA8B8E8);

        // Deck check and buttons
        panel(g, RIGHT_X, 272, PANEL_W, 82);
        int units = 0, specials = 0, strength = 0, heroes = 0;
        for (String k : deck) {
            CardDef d = reg().get(k);
            if (d == null) continue;
            if (d.isUnit()) { units++; strength += d.strength; if (d.isHero()) heroes++; } else specials++;
        }
        text(g, "Units", RIGHT_X + 8, 278, 0xFFBBBBBB);
        text(g, units + " / 22+", RIGHT_X + 8, 289, units >= Deck.MIN_UNITS ? 0xFF80FF80 : 0xFFFF8080);
        text(g, "Specials", RIGHT_X + 82, 278, 0xFFBBBBBB);
        text(g, specials + " / 10", RIGHT_X + 82, 289, specials <= Deck.MAX_SPECIALS ? 0xFF80FF80 : 0xFFFF8080);
        text(g, "Strength", RIGHT_X + 160, 278, 0xFFBBBBBB);
        text(g, String.valueOf(strength), RIGHT_X + 160, 289, 0xFFFFFFFF);
        text(g, "Heroes", RIGHT_X + 230, 278, 0xFFBBBBBB);
        text(g, String.valueOf(heroes), RIGHT_X + 230, 289, 0xFFFFFFFF);
        String err = new Deck(faction, leader == null ? "" : leader, deck).validate(reg(), owned);
        boolean active = book().getString("active").equals(faction);
        String status = err != null ? err : active ? "This is the deck you play with." : "Legal deck. Save it to play with this faction.";
        wrapped(g, status, RIGHT_X + 8, 304, PANEL_W - 16, err != null ? 0xFFFF8080 : 0xFF80FF80);
        button(g, RIGHT_X + PANEL_W - 170, 328, 80, 20, active ? "Save" : "Save & use", err == null, mx, my,
                () -> Net.toServer(new Net.SaveDeck(faction, leader == null ? "" : leader, new ArrayList<>(deck))));
        button(g, RIGHT_X + PANEL_W - 86, 328, 80, 20, "Close", true, mx, my, this::onClose);
    }

    private void drawStarter(GuiGraphics g, int mx, int my) {
        panel(g, 120, 40, 400, 280);
        g.drawCenteredString(font, "Choose your starting faction", VW / 2, 54, 0xFFFFE9A0);
        wrapped(g, "You get a starter deck for it. Win more cards by beating NPCs and villagers.", 140, 70, 360, 0xFFAAAAAA);
        int y = 96;
        for (String f : CardRegistry.PLAYABLE_FACTIONS) {
            button(g, 140, y, 100, 22, reg().factionName(f), true, mx, my, () -> Net.toServer(new Net.PickStarter(f)));
            wrapped(g, Texts.PERK.get(f), 250, y + 4, 250, 0xFFDDDDDD);
            y += 40;
        }
    }

    private interface Badge { String of(CardDef d); }

    private void drawGrid(GuiGraphics g, List<CardDef> list, int x, int scroll, int mx, int my, Badge badge) {
        int cols = cols();
        CardArt.scissor(g, x, GRID_Y, x + PANEL_W - 8, GRID_Y + GRID_H);
        for (int i = 0; i < list.size(); i++) {
            int cx = x + (i % cols) * (CW + GAP), cy = GRID_Y + (i / cols) * ROW_H - scroll;
            if (cy + ROW_H < GRID_Y || cy > GRID_Y + GRID_H) continue;
            CardDef d = list.get(i);
            boolean hover = in(mx, my, cx, cy, CW, CH) && my >= GRID_Y && my < GRID_Y + GRID_H;
            String b = badge.of(d);
            g.pose().pushPose();
            g.pose().translate(0, 0, (i % cols) * CardArt.Z_STEP);
            CardArt.draw(g, d, cx, cy, CW, CH, Integer.MIN_VALUE, hover, cx + CW / 2f - mx, cy + 10 - my);
            g.pose().translate(0, 0, CardArt.Z_STEP - 10);
            if (b == null) g.fill(cx, cy, cx + CW, cy + CH, 0xB0000000);
            String label = b == null ? "in deck" : b;
            small(g, label, cx + (CW - (int) (font.width(label) * 0.75f)) / 2, cy + CH + 2, CW, b == null ? 0xFF777777 : 0xFFDDDDDD);
            g.pose().popPose();
            if (hover) preview(d, Integer.MIN_VALUE);
        }
        g.disableScissor();
        int rows = (list.size() + cols - 1) / cols;
        if (rows * ROW_H > GRID_H) {
            int max = rows * ROW_H - GRID_H;
            int barH = Math.max(12, GRID_H * GRID_H / (rows * ROW_H));
            int barY = GRID_Y + (GRID_H - barH) * Math.min(scroll, max) / max;
            g.fill(x + PANEL_W - 10, GRID_Y, x + PANEL_W - 8, GRID_Y + GRID_H, 0x40FFFFFF);
            g.fill(x + PANEL_W - 10, barY, x + PANEL_W - 8, barY + barH, 0xC0E0C060);
        }
        if (list.isEmpty()) small(g, "No cards yet", x + 4, GRID_Y + 4, 200, 0xFF888888);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private CardDef cardAt(List<CardDef> list, int x, int scroll, double mx, double my) {
        if (my < GRID_Y || my >= GRID_Y + GRID_H) return null;
        int cols = cols();
        for (int i = 0; i < list.size(); i++) {
            int cx = x + (i % cols) * (CW + GAP), cy = GRID_Y + (i / cols) * ROW_H - scroll;
            if (in(mx, my, cx, cy, CW, CH)) return list.get(i);
        }
        return null;
    }

    @Override
    protected boolean clicked(double mx, double my, int button) {
        if (!started() || faction == null || button != 0) return false;
        CardDef add = cardAt(collectionList(), LEFT_X + 4, scrollLeft, mx, my);
        if (add != null) {
            Map<String, Integer> owned = owned(), inDeck = deckCounts();
            int n = inDeck.getOrDefault(add.key, 0);
            if (n < owned.getOrDefault(add.key, 0) && n < add.count) deck.add(add.key);
            return true;
        }
        CardDef remove = cardAt(deckList(), RIGHT_X + 4, scrollRight, mx, my);
        if (remove != null) {
            deck.remove(remove.key);
            return true;
        }
        return false;
    }

    @Override
    protected boolean scrolled(double mx, double my, double delta) {
        int step = (int) (-delta * ROW_H);
        if (mx < RIGHT_X) scrollLeft = clamp(scrollLeft + step, collectionList().size());
        else scrollRight = clamp(scrollRight + step, deckList().size());
        return true;
    }

    private static int clamp(int scroll, int count) {
        int rows = (count + cols() - 1) / cols();
        return Math.max(0, Math.min(scroll, Math.max(0, rows * ROW_H - GRID_H)));
    }
}
