package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.engine.CardRegistry;
import io.github.teemov.ecgwent.engine.Deck;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The card book: a leather binder with 3×3 card pockets per page, one section per faction (plus neutral and special
 * cards), pages that flip, and a deck box beside it for building the deck.
 */
public final class BookScreen extends CanvasScreen {
    // Book geometry (canvas coordinates)
    private static final int BOOK_X0 = 8, BOOK_X1 = 434, BOOK_Y0 = 30, BOOK_Y1 = 346;
    private static final int PAGE_Y0 = 37, PAGE_Y1 = 339, SPINE = (BOOK_X0 + BOOK_X1) / 2;
    private static final int LEFT_X0 = 16, RIGHT_X1 = BOOK_X1 - 8;
    private static final int PAGE_W = SPINE - LEFT_X0;
    private static final int CW = 46, CH = 64, STEP_X = 62, STEP_Y = 82, POCKETS = 9;
    // Deck box
    private static final int BOX_X0 = 442, BOX_X1 = 632, BOX_Y0 = 14, BOX_Y1 = 346;
    private static final int LIST_Y0 = 96, LIST_Y1 = 268, ROW_H = 12;
    private static final long FLIP_MS = 380;

    private String faction;
    private String leader;
    private final List<String> deck = new ArrayList<>();
    private int spread;          // index of the visible spread (two pages)
    private int flipFrom = -1;   // spread being flipped away from, or -1
    private long flipStart;
    private int listScroll;

    /** One pocket: a card (owned or not) and the section it belongs to. */
    private record Slot(CardDef card, String section) {}

    private record Hit(int x, int y, int w, int h, CardDef card, boolean list) {}

    private final List<Hit> hits = new ArrayList<>();

    /** Clickable areas drawn by the book itself (tabs, page corners), checked before the cards. */
    private record Area(int x, int y, int w, int h, Runnable action) {}

    private final List<Area> areas = new ArrayList<>();

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

    public void refresh() {
        if (faction != null && started()) selectFaction(faction, false);
    }

    @Override
    protected void init() {
        if (faction == null && started()) {
            String active = book().getString("active");
            selectFaction(active.isEmpty() ? CardRegistry.PLAYABLE_FACTIONS.get(0) : active, true);
        }
    }

    private void selectFaction(String f, boolean resetPage) {
        if (!f.equals(faction)) resetPage = true;
        faction = f;
        deck.clear();
        leader = null;
        if (resetPage) { spread = 0; flipFrom = -1; listScroll = 0; }
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

    // ---------------------------------------------------------------- book contents

    /** Every collectible card for this faction's book, in sections: leaders, faction units, neutral, special. */
    private List<Slot> slots() {
        List<Slot> out = new ArrayList<>();
        Comparator<CardDef> order = Comparator.comparing((CardDef d) -> !d.isHero()).thenComparingInt(d -> -d.strength).thenComparing(d -> d.name);
        List<CardDef> leaders = new ArrayList<>(reg().leadersOf(faction));
        List<CardDef> units = new ArrayList<>(), neutral = new ArrayList<>(), special = new ArrayList<>();
        for (CardDef d : reg().ofFaction(faction)) if (d.type != CardDef.Type.LEADER && d.count > 0) (d.isUnit() ? units : special).add(d);
        for (CardDef d : reg().ofFaction("neutral")) if (d.count > 0) neutral.add(d);
        List<CardDef> shared = new ArrayList<>();
        for (CardDef d : reg().ofFaction("special")) if (d.count > 0) shared.add(d);
        units.sort(order);
        neutral.sort(order);
        String name = reg().factionName(faction);
        pad(out, leaders, name + " leaders");
        List<CardDef> own = new ArrayList<>(units);
        own.addAll(special); // faction specials such as Junk Brew
        pad(out, own, name);
        pad(out, neutral, "Neutral");
        pad(out, shared, "Special & weather");
        return out;
    }

    /** Adds a section, starting it on a fresh page. */
    private static void pad(List<Slot> out, List<CardDef> cards, String section) {
        if (cards.isEmpty()) return;
        while (out.size() % POCKETS != 0) out.add(new Slot(null, null));
        for (CardDef d : cards) out.add(new Slot(d, section));
    }

    private int pageCount() { return (slots().size() + POCKETS - 1) / POCKETS; }
    private int spreadCount() { return (pageCount() + 1) / 2; }

    /** Collected / total distinct cards of a section, and the copies. */
    private int[] progress(String section) {
        Map<String, Integer> owned = owned();
        int have = 0, total = 0, copies = 0, maxCopies = 0;
        for (Slot s : slots()) {
            if (s.card == null || !section.equals(s.section)) continue;
            total++;
            int n = owned.getOrDefault(s.card.key, 0);
            if (n > 0) have++;
            int max = s.card.type == CardDef.Type.LEADER ? 1 : s.card.count;
            copies += Math.min(n, max);
            maxCopies += max;
        }
        return new int[]{have, total, copies, maxCopies};
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        areas.clear();
        if (!started()) { drawStarter(g, mx, my); return; }
        drawTabs(g, mx, my);
        drawBookCover(g);
        long t = Util.getMillis() - flipStart;
        List<Slot> slots = slots();
        if (flipFrom >= 0 && t < FLIP_MS) {
            drawFlip(g, slots, t / (float) FLIP_MS, mx, my);
        } else {
            flipFrom = -1;
            drawPage(g, slots, spread * 2, LEFT_X0, mx, my, true);
            drawPage(g, slots, spread * 2 + 1, SPINE, mx, my, true);
        }
        drawSpine(g);
        drawPageArrows(g, mx, my);
        drawDeckBox(g, mx, my);
    }

    private void drawTabs(GuiGraphics g, int mx, int my) {
        int x = BOOK_X0 + 12;
        for (String f : CardRegistry.PLAYABLE_FACTIONS) {
            boolean sel = f.equals(faction);
            int w = 78, y = sel ? 12 : 16;
            int[] c = Texts.back(f);
            boolean hov = mx >= x && mx < x + w && my >= y && my < BOOK_Y0;
            g.fill(x, y, x + w, BOOK_Y0 + 2, 0xFF221A1E);
            g.fill(x + 1, y + 1, x + w - 1, BOOK_Y0 + 2, hov && !sel ? lighten(c[1]) : c[1]);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, c[2]);
            String label = reg().factionName(f) + (book().getString("active").equals(f) ? " ★" : "");
            small(g, label, x + (w - (int) (font.width(label) * 0.75f)) / 2, y + 6, w - 4, c[2]);
            if (!sel) areas.add(new Area(x, y, w, BOOK_Y0 - y, () -> selectFaction(f, true)));
            x += w + 3;
        }
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 30), gg = Math.min(255, ((c >> 8) & 255) + 30), b = Math.min(255, (c & 255) + 30);
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }

    /** Leather cover with stitching. */
    private static void drawBookCover(GuiGraphics g) {
        g.fill(BOOK_X0 - 2, BOOK_Y0 - 1, BOOK_X1 + 2, BOOK_Y1 + 3, 0x90000000); // shadow
        g.fill(BOOK_X0, BOOK_Y0, BOOK_X1, BOOK_Y1, 0xFF3A2414);
        g.fill(BOOK_X0 + 1, BOOK_Y0 + 1, BOOK_X1 - 1, BOOK_Y1 - 1, 0xFF5A3A22);
        for (int x = BOOK_X0 + 4; x < BOOK_X1 - 4; x += 6) {
            g.fill(x, BOOK_Y0 + 3, x + 3, BOOK_Y0 + 4, 0xFFB08A5A);
            g.fill(x, BOOK_Y1 - 4, x + 3, BOOK_Y1 - 3, 0xFFB08A5A);
        }
        // page block edges (a few sheets showing under the open pages)
        for (int i = 3; i >= 1; i--) {
            g.fill(LEFT_X0 - i, PAGE_Y0 + i, SPINE, PAGE_Y1 + i, 0xFFD8C8A0 - i * 0x0A0A0A);
            g.fill(SPINE, PAGE_Y0 + i, RIGHT_X1 + i, PAGE_Y1 + i, 0xFFD8C8A0 - i * 0x0A0A0A);
        }
    }

    private static void drawSpine(GuiGraphics g) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 2500);
        for (int i = 0; i < 10; i++) {
            int a = (10 - i) * 9 << 24;
            g.fill(SPINE - i - 1, PAGE_Y0, SPINE - i, PAGE_Y1, a);
            g.fill(SPINE + i, PAGE_Y0, SPINE + i + 1, PAGE_Y1, a);
        }
        g.fill(SPINE - 1, PAGE_Y0 - 3, SPINE + 1, PAGE_Y1 + 3, 0xFF3A2414);
        g.pose().popPose();
    }

    /** Parchment with faint ruled lines. */
    private static void paper(GuiGraphics g, int x0, int x1) {
        g.fill(x0, PAGE_Y0, x1, PAGE_Y1, 0xFFEFE2C2);
        for (int y = PAGE_Y0 + 6; y < PAGE_Y1; y += 9) g.fill(x0 + 4, y, x1 - 4, y + 1, 0x0C6A4A20);
        g.fill(x0, PAGE_Y0, x1, PAGE_Y0 + 1, 0x30000000);
        g.fill(x0, PAGE_Y1 - 1, x1, PAGE_Y1, 0x30000000);
    }

    /** Draws one page (x0 = its left edge). {@code interactive} false while it's part of a flip. */
    private void drawPage(GuiGraphics g, List<Slot> slots, int page, int x0, int mx, int my, boolean interactive) {
        int x1 = x0 + PAGE_W;
        paper(g, x0, x1);
        int pages = pageCount();
        if (page >= pages) return;
        Map<String, Integer> owned = owned(), inDeck = deckCounts();
        int first = page * POCKETS;
        // Header: the section this page belongs to, with progress
        Slot head = null;
        for (int i = first; i < Math.min(slots.size(), first + POCKETS); i++) if (slots.get(i).card != null) { head = slots.get(i); break; }
        if (head != null) {
            int[] p = progress(head.section);
            String title = head.section;
            text(g, title, x0 + 10, PAGE_Y0 + 7, 0xFF4A2A10);
            String prog = p[0] + " / " + p[1] + " collected";
            int pw = (int) (font.width(prog) * 0.75f);
            small(g, prog, x1 - 10 - pw, PAGE_Y0 + 8, 100, p[0] == p[1] ? 0xFF2E7A2E : 0xFF6A4A2A);
            // progress bar
            int bx0 = x0 + 10, bx1 = x1 - 10, by = PAGE_Y0 + 18;
            g.fill(bx0, by, bx1, by + 2, 0x30000000);
            g.fill(bx0, by, bx0 + (bx1 - bx0) * p[0] / Math.max(1, p[1]), by + 2, p[0] == p[1] ? 0xFF2E7A2E : 0xFFB08A3E);
        }
        // Pockets
        int gx = x0 + (PAGE_W - (STEP_X * 2 + CW)) / 2, gy = PAGE_Y0 + 26;
        for (int k = 0; k < POCKETS; k++) {
            int i = first + k;
            int cx = gx + (k % 3) * STEP_X, cy = gy + (k / 3) * STEP_Y;
            // the plastic pocket
            g.fill(cx - 3, cy - 3, cx + CW + 3, cy + CH + 3, 0x26000000);
            g.fill(cx - 3, cy - 3, cx + CW + 3, cy - 2, 0x40FFFFFF);
            if (i >= slots.size() || slots.get(i).card == null) continue;
            CardDef d = slots.get(i).card;
            int n = owned.getOrDefault(d.key, 0);
            boolean hov = interactive && mx >= cx && mx < cx + CW && my >= cy && my < cy + CH;
            g.pose().pushPose();
            g.pose().translate(0, 0, (k % 3) * CardArt.Z_STEP);
            if (n <= 0) {
                missing(g, d, cx, cy);
            } else {
                boolean isLeader = d.type == CardDef.Type.LEADER;
                boolean chosen = isLeader && d.key.equals(leader);
                CardArt.draw(g, d, cx, cy, CW, CH, Integer.MIN_VALUE, hov || chosen, cx + CW / 2f - mx, cy + 12 - my);
                g.pose().translate(0, 0, CardArt.Z_STEP - 10);
                String label;
                int col = 0xFF4A2A10;
                if (isLeader) {
                    label = chosen ? "leader" : "click to lead";
                    col = chosen ? 0xFF2E7A2E : 0xFF6A4A2A;
                } else {
                    int used = inDeck.getOrDefault(d.key, 0), free = Math.min(n, d.count) - used;
                    label = (n > d.count ? d.count : n) + "/" + d.count + (used > 0 ? "  ·  " + used + " in deck" : "");
                    if (free <= 0) col = 0xFF8A7A5A;
                }
                small(g, label, cx + (CW - (int) (font.width(label) * 0.75f)) / 2, cy + CH + 3, CW + 14, col);
            }
            g.pose().popPose();
            if (interactive) {
                hits.add(new Hit(cx, cy, CW, CH, n > 0 ? d : null, false));
                if (hov && n > 0) preview(d, Integer.MIN_VALUE);
            }
        }
        // page number
        String num = String.valueOf(page + 1);
        small(g, num, page % 2 == 0 ? x0 + 10 : x1 - 10 - (int) (font.width(num) * 0.75f), PAGE_Y1 - 10, 20, 0xFF8A6A4A);
    }

    /** An empty pocket for a card not collected yet: its silhouette, a question mark and its slot number. */
    private void missing(GuiGraphics g, CardDef d, int x, int y) {
        int[] c = Texts.back(d.faction);
        g.fill(x, y, x + CW, y + CH, 0x40000000);
        g.fill(x + 1, y + 1, x + CW - 1, y + CH - 1, (c[1] & 0x00FFFFFF) | 0x40000000);
        String q = "?";
        g.pose().pushPose();
        g.pose().translate(x + CW / 2f - font.width(q), y + CH / 2f - 8, 0);
        g.pose().scale(2, 2, 1);
        g.drawString(font, q, 0, 0, 0x806A4A2A, false);
        g.pose().popPose();
        small(g, "not collected", x + (CW - (int) (font.width("not collected") * 0.75f)) / 2, y + CH + 3, CW + 14, 0xFF9A8A6A);
    }

    /** The turning sheet: the old right page folds over the spine, then the new left page lands. */
    private void drawFlip(GuiGraphics g, List<Slot> slots, float t, int mx, int my) {
        boolean forward = spread > flipFrom;
        int oldL = flipFrom * 2, oldR = flipFrom * 2 + 1, newL = spread * 2, newR = spread * 2 + 1;
        float e = t < 0.5f ? t * 2 : (t - 0.5f) * 2; // progress of each half
        float squash = Math.max(0.02f, t < 0.5f ? 1 - e : e);
        CardArt.flat = true;
        try {
            if (forward) {
                drawPage(g, slots, oldL, LEFT_X0, mx, my, false);       // underneath, left: old left until covered
                drawPage(g, slots, newR, SPINE, mx, my, false);         // revealed under the turning sheet
                if (t < 0.5f) sheet(g, slots, oldR, SPINE, squash, true, mx, my);
                else sheet(g, slots, newL, LEFT_X0, squash, false, mx, my);
            } else {
                drawPage(g, slots, newL, LEFT_X0, mx, my, false);
                drawPage(g, slots, oldR, SPINE, mx, my, false);
                if (t < 0.5f) sheet(g, slots, oldL, LEFT_X0, squash, false, mx, my);
                else sheet(g, slots, newR, SPINE, squash, true, mx, my);
            }
        } finally {
            CardArt.flat = false;
        }
    }

    /** A page squashed toward the spine (rightSide: the page lies right of the spine). */
    private void sheet(GuiGraphics g, List<Slot> slots, int page, int x0, float squash, boolean rightSide, int mx, int my) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 3000);
        g.pose().translate(SPINE, 0, 0);
        g.pose().scale(squash, 1, 1);
        g.pose().translate(-SPINE, 0, 0);
        drawPage(g, slots, page, x0, mx, my, false);
        // shading: darker as the sheet stands upright
        int a = (int) ((1 - squash) * 0x90);
        g.fill(x0, PAGE_Y0, x0 + PAGE_W, PAGE_Y1, a << 24);
        g.pose().popPose();
        // the sheet's shadow on the page underneath
        int sw = (int) (PAGE_W * squash);
        int sx = rightSide ? SPINE + sw : SPINE - sw - 8;
        g.pose().pushPose();
        g.pose().translate(0, 0, 2900);
        for (int i = 0; i < 8; i++) {
            int x = rightSide ? sx + i : sx + 7 - i;
            if (rightSide ? x < RIGHT_X1 : x > LEFT_X0) g.fill(x, PAGE_Y0, x + 1, PAGE_Y1, (8 - i) * 6 << 24);
        }
        g.pose().popPose();
    }

    private void drawPageArrows(GuiGraphics g, int mx, int my) {
        boolean canBack = spread > 0, canFwd = spread < spreadCount() - 1;
        g.pose().pushPose();
        g.pose().translate(0, 0, 3200);
        cornerArrow(g, LEFT_X0 + 4, PAGE_Y1 - 22, false, canBack, mx, my, () -> flip(-1));
        cornerArrow(g, RIGHT_X1 - 22, PAGE_Y1 - 22, true, canFwd, mx, my, () -> flip(1));
        String s = "Pages " + (spread * 2 + 1) + "–" + Math.min(pageCount(), spread * 2 + 2) + " of " + pageCount();
        g.pose().popPose();
        small(g, s, SPINE - (int) (font.width(s) * 0.375f), BOOK_Y1 + 3, 160, 0xFFB09070);
    }

    /** A dog-eared page corner that turns the page. */
    private void cornerArrow(GuiGraphics g, int x, int y, boolean right, boolean enabled, int mx, int my, Runnable action) {
        if (!enabled) return;
        boolean hov = mx >= x && mx < x + 18 && my >= y && my < y + 18;
        // the curled-up corner: a triangle in the page's bottom corner, with a fold shadow along its edge
        for (int i = 0; i < 18; i++) {
            int w = i + 1;
            int c = hov ? 0xFFD8C08A : 0xFFE6D6AE;
            g.fill(right ? x + 18 - w : x, y + i, right ? x + 18 : x + w, y + i + 1, c);
            g.fill(right ? x + 18 - w - 1 : x + w, y + i, right ? x + 18 - w : x + w + 1, y + i + 1, 0x50000000);
        }
        String arrow = right ? ">" : "<";
        text(g, arrow, right ? x + 11 : x + 2, y + 9, 0xFF6A4A2A);
        areas.add(new Area(x, y, 18, 18, action));
    }

    private void flip(int dir) {
        int to = Math.max(0, Math.min(spreadCount() - 1, spread + dir));
        if (to == spread || flipFrom >= 0) return;
        flipFrom = spread;
        spread = to;
        flipStart = Util.getMillis();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f));
    }

    // ---------------------------------------------------------------- deck box

    private void drawDeckBox(GuiGraphics g, int mx, int my) {
        int[] c = Texts.back(faction);
        g.fill(BOX_X0 - 2, BOX_Y0 - 1, BOX_X1 + 2, BOX_Y1 + 3, 0x90000000);
        g.fill(BOX_X0, BOX_Y0, BOX_X1, BOX_Y1, 0xFF221A1E);
        g.fill(BOX_X0 + 1, BOX_Y0 + 1, BOX_X1 - 1, BOX_Y1 - 1, c[1]);
        g.fill(BOX_X0 + 4, BOX_Y0 + 4, BOX_X1 - 4, BOX_Y1 - 4, 0xF0201810);
        text(g, "Deck", BOX_X0 + 10, BOX_Y0 + 9, c[2]);
        small(g, reg().factionName(faction), BOX_X0 + 40, BOX_Y0 + 10, 100, 0xFFAA9A7A);

        // Leader
        CardDef ld = reg().get(leader == null ? "" : leader);
        int lx = BOX_X0 + 24, ly = BOX_Y0 + 24;
        button(g, BOX_X0 + 8, ly + 14, 14, 18, "<", ownedLeaders().size() > 1, mx, my, () -> cycleLeader(-1));
        button(g, lx + 36, ly + 14, 14, 18, ">", ownedLeaders().size() > 1, mx, my, () -> cycleLeader(1));
        if (ld != null) {
            CardArt.draw(g, ld, lx, ly, 34, 47, Integer.MIN_VALUE, false, lx + 17 - mx, ly + 10 - my);
            if (mx >= lx && mx < lx + 34 && my >= ly && my < ly + 47) preview(ld, Integer.MIN_VALUE);
            String title = ld.name.contains("–") ? ld.name.substring(ld.name.indexOf('–') + 2) : ld.name;
            small(g, title, lx + 54, ly, BOX_X1 - lx - 60, 0xFFFFFFFF);
            wrapped(g, Texts.leaderText(ld.leaderAbility), lx + 54, ly + 10, BOX_X1 - lx - 62, 0xFFBBBBBB);
        } else {
            wrapped(g, "No leader owned for this faction yet.", lx + 54, ly + 10, BOX_X1 - lx - 62, 0xFFFF8080);
        }

        // Card list
        Map<String, Integer> counts = deckCounts();
        List<CardDef> list = new ArrayList<>();
        for (String k : counts.keySet()) { CardDef d = reg().get(k); if (d != null) list.add(d); }
        list.sort(Comparator.comparing((CardDef d) -> !d.isUnit()).thenComparingInt(d -> -d.strength).thenComparing(d -> d.name));
        g.fill(BOX_X0 + 8, LIST_Y0 - 2, BOX_X1 - 8, LIST_Y1 + 2, 0x60000000);
        int visible = (LIST_Y1 - LIST_Y0) / ROW_H;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - visible)));
        for (int i = 0; i < visible && i + listScroll < list.size(); i++) {
            CardDef d = list.get(i + listScroll);
            int y = LIST_Y0 + i * ROW_H;
            boolean hov = mx >= BOX_X0 + 8 && mx < BOX_X1 - 8 && my >= y && my < y + ROW_H;
            if (hov) g.fill(BOX_X0 + 8, y, BOX_X1 - 8, y + ROW_H, 0x30FFE080);
            int col = Texts.color(d);
            g.fill(BOX_X0 + 11, y + 1, BOX_X0 + 23, y + ROW_H - 1, col);
            String st = d.isUnit() ? String.valueOf(d.strength) : "★";
            small(g, st, BOX_X0 + 17 - (int) (font.width(st) * 0.375f), y + 3, 12, 0xFFFFFFFF);
            small(g, d.name, BOX_X0 + 27, y + 3, BOX_X1 - BOX_X0 - 60, d.isHero() ? 0xFFE9C84A : 0xFFE0D8C0);
            String n = "×" + counts.get(d.key);
            small(g, n, BOX_X1 - 12 - (int) (font.width(n) * 0.75f), y + 3, 20, 0xFFAAAAAA);
            hits.add(new Hit(BOX_X0 + 8, y, BOX_X1 - BOX_X0 - 16, ROW_H, d, true));
            if (hov) preview(d, Integer.MIN_VALUE);
        }
        if (list.isEmpty()) small(g, "Click cards in the book to add them.", BOX_X0 + 12, LIST_Y0 + 4, 170, 0xFF888888);
        else small(g, "click a card here to remove it", BOX_X0 + 10, LIST_Y1 + 4, 170, 0xFF888888);

        // Stats and rules
        int units = 0, specials = 0, strength = 0, heroes = 0;
        for (String k : deck) {
            CardDef d = reg().get(k);
            if (d == null) continue;
            if (d.isUnit()) { units++; strength += d.strength; if (d.isHero()) heroes++; } else specials++;
        }
        int sy = LIST_Y1 + 16;
        small(g, "Units", BOX_X0 + 10, sy, 40, 0xFFAAAAAA);
        text(g, units + "/22+", BOX_X0 + 10, sy + 8, units >= Deck.MIN_UNITS ? 0xFF80FF80 : 0xFFFF8080);
        small(g, "Specials", BOX_X0 + 62, sy, 40, 0xFFAAAAAA);
        text(g, specials + "/10", BOX_X0 + 62, sy + 8, specials <= Deck.MAX_SPECIALS ? 0xFF80FF80 : 0xFFFF8080);
        small(g, "Strength", BOX_X0 + 108, sy, 40, 0xFFAAAAAA);
        text(g, String.valueOf(strength), BOX_X0 + 108, sy + 8, 0xFFFFFFFF);
        small(g, "Heroes", BOX_X0 + 150, sy, 40, 0xFFAAAAAA);
        text(g, String.valueOf(heroes), BOX_X0 + 150, sy + 8, 0xFFFFFFFF);
        String err = new Deck(faction, leader == null ? "" : leader, deck).validate(reg(), owned());
        boolean active = book().getString("active").equals(faction);
        String status = err != null ? err : active ? "This is the deck you play with." : "Legal deck. Save it to play this faction.";
        wrapped(g, status, BOX_X0 + 10, sy + 20, BOX_X1 - BOX_X0 - 20, err != null ? 0xFFFF8080 : 0xFF80FF80);
        button(g, BOX_X0 + 10, BOX_Y1 - 26, 84, 18, active ? "Save" : "Save & use", err == null, mx, my,
                () -> Net.toServer(new Net.SaveDeck(faction, leader == null ? "" : leader, new ArrayList<>(deck))));
        button(g, BOX_X1 - 94, BOX_Y1 - 26, 84, 18, "Close", true, mx, my, this::onClose);
    }

    private void drawStarter(GuiGraphics g, int mx, int my) {
        drawBookCover(g);
        paper(g, LEFT_X0, SPINE);
        paper(g, SPINE, RIGHT_X1);
        drawSpine(g);
        text(g, "Your card book", LEFT_X0 + 14, PAGE_Y0 + 14, 0xFF4A2A10);
        wrapped(g, "Every card you win is kept in this book, safe from death and theft. Pick the faction you start with: "
                + "you get a starter deck for it. Win more cards from story characters and villagers.", LEFT_X0 + 14, PAGE_Y0 + 32, PAGE_W - 28, 0xFF5A3A1A);
        text(g, "Choose a faction", SPINE + 14, PAGE_Y0 + 14, 0xFF4A2A10);
        int y = PAGE_Y0 + 32;
        for (String f : CardRegistry.PLAYABLE_FACTIONS) {
            button(g, SPINE + 14, y, 80, 18, reg().factionName(f), true, mx, my, () -> Net.toServer(new Net.PickStarter(f)));
            wrapped(g, Texts.PERK.get(f), SPINE + 98, y + 2, PAGE_W - 110, 0xFF5A3A1A);
            y += 46;
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    protected boolean clicked(double mx, double my, int button) {
        if (!started() || faction == null || button != 0 || flipFrom >= 0) return false;
        for (Area a : areas) {
            if (mx >= a.x && mx < a.x + a.w && my >= a.y && my < a.y + a.h) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                a.action.run();
                return true;
            }
        }
        for (Hit h : hits) {
            if (mx < h.x || mx >= h.x + h.w || my < h.y || my >= h.y + h.h || h.card == null) continue;
            CardDef d = h.card;
            if (h.list) {
                deck.remove(d.key);
                return true;
            }
            if (d.type == CardDef.Type.LEADER) {
                if (d.faction.equals(faction)) leader = d.key;
                return true;
            }
            Map<String, Integer> owned = owned(), inDeck = deckCounts();
            int n = inDeck.getOrDefault(d.key, 0);
            if (n < owned.getOrDefault(d.key, 0) && n < d.count) {
                deck.add(d.key);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PUT, 1.4f));
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean scrolled(double mx, double my, double delta) {
        if (mx >= BOX_X0) { listScroll = Math.max(0, listScroll - (int) Math.signum(delta) * 2); return true; }
        flip(delta < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (started() && (key == 262 || key == 68)) { flip(1); return true; }   // right arrow / D
        if (started() && (key == 263 || key == 65)) { flip(-1); return true; }  // left arrow / A
        return super.keyPressed(key, scan, mods);
    }
}
