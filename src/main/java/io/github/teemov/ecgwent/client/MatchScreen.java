package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The match board. The server is in charge; this screen shows its latest snapshot and sends the player's actions. */
public final class MatchScreen extends CanvasScreen {
    private static final String[] ROWS = {"close", "ranged", "siege"};
    private static final String[] ROW_NAMES = {"Close", "Ranged", "Siege"};
    // Layout (canvas coordinates)
    private static final int LP_X = 6, LP_W = 128;
    private static final int BX0 = 140, BX1 = 576, BY0 = 20, ROW_H = 44;
    private static final int RP_X = 582;
    private static final int HAND_Y = 292, HAND_CW = 44, HAND_CH = 62;
    private static final int SLOT_X = BX0 + 24, CARD_W = 30, CARD_H = 40, CARDS_X = SLOT_X + 36, CARDS_X1 = BX1 - 4;

    /** Clickable areas found while drawing. */
    private static final class Hit {
        final int x, y, w, h;
        final String kind; // hand, row, board, leader, option
        final int uid;
        final boolean mine;
        final String row;

        Hit(int x, int y, int w, int h, String kind, int uid, boolean mine, String row) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.kind = kind; this.uid = uid; this.mine = mine; this.row = row;
        }

        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int selected = -1;
    private final List<Integer> picks = new ArrayList<>();

    public MatchScreen() {
        super("Gwent");
    }

    // ------------------------------------------------------------------ snapshot access

    private static Map<String, Object> m() { return ClientHooks.match; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> o, String k) { return o == null ? null : (Map<String, Object>) o.get(k); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> o, String k) {
        Object v = o == null ? null : o.get(k);
        return v == null ? Collections.emptyList() : (List<Map<String, Object>>) v;
    }

    private static int num(Map<String, Object> o, String k) { Object v = o == null ? null : o.get(k); return v instanceof Double ? ((Double) v).intValue() : 0; }
    private static boolean bool(Map<String, Object> o, String k) { return o != null && Boolean.TRUE.equals(o.get(k)); }
    private static String str(Map<String, Object> o, String k) { Object v = o == null ? null : o.get(k); return v == null ? "" : v.toString(); }
    private static CardDef def(String key) { return ClientHooks.cards.get(key); }

    private String phase() { return str(m(), "phase"); }
    private boolean over() { return m() != null && m().containsKey("winner"); }
    private Map<String, Object> me() { return map(m(), "me"); }
    private Map<String, Object> them() { return map(m(), "them"); }
    private Map<String, Object> choice() { return map(m(), "choice"); }
    private boolean redrawing() { return phase().equals("REDRAW") && num(me(), "redrawsLeft") > 0; }

    public void refresh() {
        if (choice() == null) picks.clear();
        if (selected >= 0 && list(me(), "hand").stream().noneMatch(c -> num(c, "uid") == selected)) selected = -1;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        if (m() == null) return;
        boolean overlay = over() || redrawing() || choice() != null || phase().equals("CHOOSE_FIRST") || phase().equals("REDRAW");

        drawBoard(g, mx, my, !overlay);
        drawSide(g, them(), 6, false, mx, my);
        drawSide(g, me(), 230, true, mx, my);
        drawMiddle(g);
        drawCounts(g);
        if (!overlay) {
            drawHand(g, mx, my);
            String status = statusLine();
            g.drawCenteredString(font, status, (BX0 + BX1) / 2, 6, 0xFFFFE9A0);
        }
        drawOverlays(g, mx, my);
    }

    private void drawBoard(GuiGraphics g, int mx, int my, boolean interactive) {
        panel(g, BX0 - 3, BY0 - 3, BX1 - BX0 + 6, ROW_H * 6 + 6);
        String weather = str(m(), "weather");
        CardDef sel = interactive ? selectedDef() : null;
        for (int i = 0; i < 6; i++) {
            boolean mine = i >= 3;
            int ri = mine ? i - 3 : 2 - i;
            String row = ROWS[ri];
            int y = BY0 + i * ROW_H;
            Map<String, Object> r = map(map(mine ? me() : them(), "rows"), row);
            boolean target = rowTargetable(sel, mine, row);
            int bg = weather.contains(row) ? 0x704060A0 : (mine ? 0x30406030 : 0x30603030);
            g.fill(BX0, y + 1, BX1, y + ROW_H - 1, bg);
            if (target) g.fill(BX0, y + 1, BX1, y + ROW_H - 1, 0x50FFE080);
            hits.add(new Hit(BX0, y, BX1 - BX0, ROW_H, "row", -1, mine, row));
            // Row total and row name
            g.fill(BX0 + 2, y + ROW_H / 2 - 9, BX0 + 20, y + ROW_H / 2 + 7, 0xFF2A2418);
            String sc = String.valueOf(num(r, "score"));
            text(g, sc, BX0 + 11 - font.width(sc) / 2, y + ROW_H / 2 - 5, 0xFFFFFFFF);
            small(g, ROW_NAMES[ri], BX0 + 2, y + ROW_H / 2 + 9, 20, weather.contains(row) ? 0xFF9FC6FF : 0xFF8A8A8A);
            // Horn / Mardroeme slot
            g.fill(SLOT_X, y + 2, SLOT_X + CARD_W, y + 2 + CARD_H, 0x40000000);
            CardDef sp = def(str(r, "special"));
            if (sp != null) {
                CardArt.draw(g, sp, SLOT_X, y + 2, CARD_W, CARD_H, Integer.MIN_VALUE, false, 10, 0);
                if (in(mx, my, SLOT_X, y + 2, CARD_W, CARD_H)) preview(sp, Integer.MIN_VALUE);
            }
            // Units
            List<Map<String, Object>> cards = list(r, "cards");
            int areaW = CARDS_X1 - CARDS_X;
            int step = cards.size() <= 1 ? CARD_W + 2 : Math.min(CARD_W + 2, (areaW - CARD_W) / (cards.size() - 1));
            int startX = CARDS_X + Math.max(0, (areaW - (step * (cards.size() - 1) + CARD_W)) / 2);
            for (int k = 0; k < cards.size(); k++) {
                Map<String, Object> c = cards.get(k);
                CardDef d = def(str(c, "key"));
                if (d == null) continue;
                int cx = startX + k * step;
                int hitW = k == cards.size() - 1 ? CARD_W : Math.min(step, CARD_W);
                boolean decoyTarget = sel != null && sel.has("decoy") && mine && !d.isHero() && !d.has("decoy");
                boolean hov = interactive && in(mx, my, cx, y + 2, hitW, CARD_H);
                g.pose().pushPose();
                g.pose().translate(0, 0, k * CardArt.Z_STEP);
                CardArt.draw(g, d, cx, y + 2, CARD_W, CARD_H, num(c, "strength"), decoyTarget || hov, 14, 4);
                g.pose().popPose();
                hits.add(new Hit(cx, y + 2, hitW, CARD_H, "board", num(c, "uid"), mine, row));
                if (hov) preview(d, num(c, "strength"));
            }
        }
        g.fill(BX0, BY0 + ROW_H * 3 - 1, BX1, BY0 + ROW_H * 3 + 1, 0xFFC9A227);
    }

    /** One player's panel: portrait, name, gems, hand size, score, leader. */
    private void drawSide(GuiGraphics g, Map<String, Object> s, int y, boolean mine, int mx, int my) {
        int x = LP_X;
        panel(g, x, y, LP_W, mine ? 124 : 118);
        // Portrait
        int px = x + 4, py = y + 4, pw = 40, ph = 56;
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF6A5A30);
        CardArt.scissor(g, px, py, px + pw, py + ph);
        CardArt.backdrop(g, str(s, "faction"), px, py, pw, ph);
        CardArt.drawSubject(g, mine ? "self" : str(m(), "opponentEntity"), null, px, py, pw, ph, px + pw / 2f - mx, py + 15 - my);
        g.disableScissor();
        // Info
        int ix = x + 50;
        small(g, str(s, "name"), ix, y + 5, LP_W - 54, mine ? 0xFF9FFF9F : 0xFFFF9F9F);
        for (int i = 0; i < 2; i++) {
            int gx = ix + i * 11, gy = y + 15;
            g.fill(gx, gy, gx + 8, gy + 8, 0xFF401010);
            if (i < num(s, "gems")) g.fill(gx + 1, gy + 1, gx + 7, gy + 7, 0xFFE03030);
        }
        small(g, "lives", ix + 24, y + 16, 40, 0xFF999999);
        small(g, num(s, "handCount") + " cards in hand", ix, y + 27, LP_W - 54, 0xFFCCCCCC);
        text(g, String.valueOf(num(s, "score")), ix, y + 38, 0xFFFFFFFF);
        small(g, "points", ix + font.width(String.valueOf(num(s, "score"))) + 3, y + 39, 40, 0xFF999999);
        if (bool(s, "passed")) small(g, "Passed", ix, y + 51, 60, 0xFFFFB060);
        // Leader
        CardDef leader = def(str(s, "leader"));
        int ly = y + 64;
        small(g, mine ? "Your leader" : "Their leader", x + 4, ly, 60, 0xFFFFE9A0);
        if (leader != null) {
            int lx = x + 4, lcy = ly + 9, lw = 32, lh = 44;
            boolean usable = mine && bool(s, "leaderUsable");
            boolean hov = in(mx, my, lx, lcy, lw, lh);
            CardArt.draw(g, leader, lx, lcy, lw, lh, Integer.MIN_VALUE, usable && hov, 12, 2);
            if (!bool(s, "leaderReady")) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 300);
                g.fill(lx, lcy, lx + lw, lcy + lh, 0xB0000000);
                small(g, "used", lx + 6, lcy + 18, 30, 0xFFAAAAAA);
                g.pose().popPose();
            }
            if (mine) hits.add(new Hit(lx, lcy, lw, lh, "leader", -1, true, null));
            if (hov) preview(leader, Integer.MIN_VALUE);
            String title = leader.name.contains("–") ? leader.name.substring(leader.name.indexOf('–') + 2) : leader.name;
            small(g, title, lx + lw + 4, lcy, LP_W - lw - 14, 0xFFFFFFFF);
            if (mine) {
                if (usable) small(g, "Click it to use", lx + lw + 4, lcy + 10, 80, 0xFFFFE080);
                button(g, lx + lw + 4, lcy + 24, LP_W - lw - 14, 18, "Pass", bool(m(), "myTurn") && !over(), mx, my,
                        () -> Net.toServer(Net.MatchAction.of("pass")));
            } else {
                wrapped(g, Texts.leaderText(leader.leaderAbility), lx + lw + 4, lcy + 10, LP_W - lw - 14, 0xFFAAAAAA);
            }
        }
    }

    private void drawMiddle(GuiGraphics g) {
        int y = 128;
        panel(g, LP_X, y, LP_W, 98);
        text(g, "Round " + num(m(), "round"), LP_X + 4, y + 4, 0xFFFFE9A0);
        String weather = str(m(), "weather");
        if (!weather.isEmpty()) {
            String w = weather.replace("close", "Snowstorm").replace("ranged", "Fog").replace("siege", "Thunderstorm").replace(" ", ", ");
            small(g, "Weather: " + w, LP_X + 4, y + 15, LP_W - 8, 0xFF9FC6FF);
        }
        @SuppressWarnings("unchecked")
        List<Object> log = (List<Object>) m().get("log");
        if (log == null) return;
        int ly = y + 26;
        for (int i = Math.max(0, log.size() - 5); i < log.size() && ly < y + 92; i++)
            ly += wrapped(g, log.get(i).toString(), LP_X + 4, ly, LP_W - 8, i == log.size() - 1 ? 0xFFFFFFFF : 0xFF999999) + 2;
    }

    private void drawCounts(GuiGraphics g) {
        int w = VW - RP_X - 4;
        panel(g, RP_X, BY0 - 3, w, 46);
        small(g, "Their deck", RP_X + 3, BY0 + 1, w - 4, 0xFF999999);
        text(g, String.valueOf(num(them(), "deckCount")), RP_X + 3, BY0 + 9, 0xFFFFFFFF);
        small(g, "Graveyard", RP_X + 3, BY0 + 21, w - 4, 0xFF999999);
        text(g, String.valueOf(list(them(), "grave").size()), RP_X + 3, BY0 + 29, 0xFFFFFFFF);
        int y = BY0 + ROW_H * 6 - 43;
        panel(g, RP_X, y, w, 46);
        small(g, "Your deck", RP_X + 3, y + 4, w - 4, 0xFF999999);
        text(g, String.valueOf(num(me(), "deckCount")), RP_X + 3, y + 12, 0xFFFFFFFF);
        small(g, "Graveyard", RP_X + 3, y + 24, w - 4, 0xFF999999);
        text(g, String.valueOf(list(me(), "grave").size()), RP_X + 3, y + 32, 0xFFFFFFFF);
    }

    private void drawHand(GuiGraphics g, int mx, int my) {
        List<Map<String, Object>> hand = list(me(), "hand");
        int areaW = BX1 - BX0;
        int step = hand.size() <= 1 ? HAND_CW + 3 : Math.min(HAND_CW + 3, (areaW - HAND_CW) / (hand.size() - 1));
        int start = BX0 + Math.max(0, (areaW - (step * (hand.size() - 1) + HAND_CW)) / 2);
        for (int k = 0; k < hand.size(); k++) {
            Map<String, Object> c = hand.get(k);
            CardDef d = def(str(c, "key"));
            if (d == null) continue;
            int uid = num(c, "uid");
            int cx = start + k * step, cy = HAND_Y + (uid == selected ? -6 : 0);
            int hitW = k == hand.size() - 1 ? HAND_CW : Math.min(step, HAND_CW);
            boolean hov = in(mx, my, cx, cy, hitW, HAND_CH);
            g.pose().pushPose();
            g.pose().translate(0, 0, k * CardArt.Z_STEP);
            CardArt.draw(g, d, cx, cy, HAND_CW, HAND_CH, Integer.MIN_VALUE, uid == selected || hov, cx + HAND_CW / 2f - mx, cy + 10 - my);
            g.pose().popPose();
            hits.add(new Hit(cx, cy, hitW, HAND_CH, "hand", uid, true, null));
            if (hov) preview(d, Integer.MIN_VALUE);
        }
    }

    private String statusLine() {
        CardDef sel = selectedDef();
        if (sel != null) {
            if (sel.has("decoy")) return "Click one of your units to take it back into your hand.";
            if (sel.has("horn") || sel.has("mardroeme")) return "Click one of your rows.";
            if (!sel.isUnit()) return "Click the board, or the card again, to play it.";
            if (sel.isAgile()) return "Click your close or ranged row.";
            if (sel.has("spy")) return "Spy: click the highlighted enemy row, or the card again.";
            return "Click the highlighted row, or the card again. Right-click cancels.";
        }
        if (bool(m(), "myTurn")) return "Your turn: play a card" + (bool(me(), "leaderUsable") ? ", use your leader" : "") + " or pass.";
        if (bool(me(), "passed")) return "You passed. Waiting for your opponent to finish the round…";
        return "Your opponent is thinking…";
    }

    private void drawOverlays(GuiGraphics g, int mx, int my) {
        g.pose().pushPose();
        g.pose().translate(0, 0, CanvasScreen.Z_OVERLAY);
        try {
            if (over()) { drawResult(g, mx, my); return; }
            if (phase().equals("CHOOSE_FIRST")) {
                dim(g);
                if (choice() != null) {
                    g.drawCenteredString(font, "Orc perk: you decide who goes first.", VW / 2, 140, 0xFFFFE9A0);
                    button(g, VW / 2 - 106, 162, 100, 20, "I go first", true, mx, my, () -> Net.toServer(Net.MatchAction.of("first_me")));
                    button(g, VW / 2 + 6, 162, 100, 20, "They go first", true, mx, my, () -> Net.toServer(Net.MatchAction.of("first_them")));
                } else {
                    g.drawCenteredString(font, "Your opponent decides who goes first…", VW / 2, 160, 0xFFFFE9A0);
                }
                return;
            }
            if (redrawing()) {
                dim(g);
                g.drawCenteredString(font, "Your starting hand", VW / 2, 24, 0xFFFFE9A0);
                small(g, "Click up to " + num(me(), "redrawsLeft") + " card(s) to swap them for random cards from your deck.", VW / 2 - 150, 38, 300, 0xFFCCCCCC);
                drawOptions(g, list(me(), "hand"), mx, my, 56);
                button(g, VW / 2 - 60, 328, 120, 20, "Keep this hand", true, mx, my, () -> Net.toServer(Net.MatchAction.of("redraw_done")));
                return;
            }
            if (phase().equals("REDRAW")) {
                dim(g);
                g.drawCenteredString(font, "Waiting for your opponent to pick their hand…", VW / 2, 170, 0xFFFFE9A0);
                return;
            }
            Map<String, Object> ch = choice();
            if (ch != null) {
                dim(g);
                int count = num(ch, "count");
                g.drawCenteredString(font, choiceTitle(str(ch, "kind"), count), VW / 2, 24, 0xFFFFE9A0);
                drawOptions(g, list(ch, "options"), mx, my, 56);
                button(g, VW / 2 - 50, 328, 100, 20, count == 0 ? "OK" : "Confirm (" + picks.size() + "/" + count + ")", picks.size() == count, mx, my, () -> {
                    int[] arr = picks.stream().mapToInt(Integer::intValue).toArray();
                    Net.toServer(new Net.MatchAction("choose", -1, "", -1, arr));
                    picks.clear();
                });
            }
        } finally {
            g.pose().popPose();
        }
    }

    private void dim(GuiGraphics g) { g.fill(0, 0, VW, VH, 0xC0000000); }

    private void drawResult(GuiGraphics g, int mx, int my) {
        dim(g);
        String w = str(m(), "winner");
        int x = VW / 2 - 140, y = 90;
        panel(g, x, y, 280, 170);
        String title = w.equals("me") ? "Victory!" : w.equals("them") ? "Defeat" : "Draw";
        g.pose().pushPose();
        g.pose().translate(VW / 2f, y + 10, 0);
        g.pose().scale(2, 2, 1);
        g.drawCenteredString(font, title, 0, 0, w.equals("me") ? 0xFFFFE080 : 0xFFE0E0E0);
        g.pose().popPose();
        // Round-by-round scores
        @SuppressWarnings("unchecked") List<Object> mine = (List<Object>) me().get("rounds");
        @SuppressWarnings("unchecked") List<Object> theirs = (List<Object>) them().get("rounds");
        int ty = y + 38;
        text(g, "You", x + 120, ty, 0xFF9FFF9F);
        small(g, str(them(), "name"), x + 180, ty + 1, 90, 0xFFFF9F9F);
        if (mine != null && theirs != null) {
            for (int i = 0; i < Math.min(mine.size(), theirs.size()); i++) {
                ty += 12;
                int a = ((Double) mine.get(i)).intValue(), b = ((Double) theirs.get(i)).intValue();
                text(g, "Round " + (i + 1), x + 30, ty, 0xFFBBBBBB);
                text(g, String.valueOf(a), x + 120, ty, a > b ? 0xFFFFE080 : 0xFFFFFFFF);
                text(g, String.valueOf(b), x + 180, ty, b > a ? 0xFFFFE080 : 0xFFFFFFFF);
            }
        }
        wrapped(g, str(m(), "result"), x + 14, y + 112, 252, 0xFFFFFFFF);
        button(g, VW / 2 - 40, y + 142, 80, 20, "Close", true, mx, my, () -> {
            Net.toServer(Net.MatchAction.of("close"));
            ClientHooks.match = null;
            onClose();
        });
    }

    private static String choiceTitle(String kind, int count) {
        switch (kind) {
            case "MEDIC": return "Medic: pick a unit from your graveyard to play";
            case "LEADER_WEATHER": return "Pick a weather card to play";
            case "LEADER_OWN_GRAVE": return "Pick a card from your graveyard to take back";
            case "LEADER_ENEMY_GRAVE": return "Pick a card from your opponent's graveyard";
            case "DISCARD_TWO": return "Pick 2 cards to discard";
            case "PICK_FROM_DECK": return "Pick any card from your deck";
            case "PEEK": return "Three cards from your opponent's hand";
            default: return "Pick " + count;
        }
    }

    private void drawOptions(GuiGraphics g, List<Map<String, Object>> opts, int mx, int my, int top) {
        int cw = 52, ch = 74, gap = 6;
        int perRow = Math.min(10, opts.size());
        if (perRow == 0) return;
        int rows = (opts.size() + perRow - 1) / perRow;
        int y0 = top + Math.max(0, (320 - top - rows * (ch + gap)) / 2);
        for (int i = 0; i < opts.size(); i++) {
            int inRow = Math.min(perRow, opts.size() - (i / perRow) * perRow);
            int x0 = (VW - inRow * (cw + gap) + gap) / 2;
            int x = x0 + (i % perRow) * (cw + gap), y = y0 + (i / perRow) * (ch + gap);
            CardDef d = def(str(opts.get(i), "key"));
            if (d == null) continue;
            int uid = num(opts.get(i), "uid");
            boolean hov = in(mx, my, x, y, cw, ch);
            g.pose().pushPose();
            g.pose().translate(0, 0, (i % perRow) * CardArt.Z_STEP);
            CardArt.draw(g, d, x, y, cw, ch, Integer.MIN_VALUE, hov || picks.contains(uid), x + cw / 2f - mx, y + 15 - my);
            g.pose().popPose();
            hits.add(new Hit(x, y, cw, ch, "option", uid, true, null));
            if (hov) preview(d, Integer.MIN_VALUE);
        }
    }

    // ------------------------------------------------------------------ input

    private CardDef selectedDef() {
        if (selected < 0) return null;
        for (Map<String, Object> c : list(me(), "hand")) if (num(c, "uid") == selected) return def(str(c, "key"));
        return null;
    }

    private boolean rowTargetable(CardDef d, boolean mine, String row) {
        if (d == null) return false;
        if (d.isUnit()) {
            if (d.has("spy")) return !mine && row.equals(d.placement);
            if (d.isAgile()) return mine && !row.equals("siege");
            return mine && row.equals(d.placement);
        }
        if (d.has("horn") || d.has("mardroeme")) return mine && str(map(map(me(), "rows"), row), "special").isEmpty();
        return !d.has("decoy");
    }

    @Override
    protected boolean clicked(double mx, double my, int button) {
        if (m() == null || over()) return false;
        if (button == 1) { selected = -1; return true; }
        if (redrawing()) {
            for (Hit h : hits) if (h.kind.equals("option") && h.contains(mx, my)) {
                Net.toServer(new Net.MatchAction("redraw", h.uid, "", -1, new int[0]));
                return true;
            }
            return false;
        }
        Map<String, Object> ch = choice();
        if (ch != null) {
            int count = num(ch, "count");
            for (Hit h : hits) if (h.kind.equals("option") && h.contains(mx, my) && count > 0) {
                if (picks.contains(h.uid)) picks.remove((Integer) h.uid);
                else {
                    if (picks.size() >= count) picks.remove(0);
                    picks.add(h.uid);
                }
                return true;
            }
            return false;
        }
        if (!bool(m(), "myTurn")) return false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(mx, my)) continue;
            switch (h.kind) {
                case "hand":
                    if (h.uid == selected) playSelected(null);
                    else selected = h.uid;
                    return true;
                case "leader":
                    if (bool(me(), "leaderUsable")) Net.toServer(Net.MatchAction.of("leader"));
                    return true;
                case "board": {
                    CardDef sel = selectedDef();
                    if (sel != null && sel.has("decoy") && h.mine) {
                        Net.toServer(new Net.MatchAction("play", selected, "", h.uid, new int[0]));
                        selected = -1;
                        return true;
                    }
                    break;
                }
                case "row": {
                    CardDef sel = selectedDef();
                    if (sel != null && rowTargetable(sel, h.mine, h.row)) {
                        playSelected(h.row);
                        return true;
                    }
                    break;
                }
                default: break;
            }
        }
        return false;
    }

    private void playSelected(String row) {
        CardDef d = selectedDef();
        if (d == null || d.has("decoy")) return;
        if (row == null && (d.isAgile() || d.has("horn") || d.has("mardroeme"))) return;
        Net.toServer(new Net.MatchAction("play", selected, d.isUnit() && !d.isAgile() ? "" : (row == null ? "" : row), -1, new int[0]));
        selected = -1;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void onClose() {
        if (m() != null && !over()) {
            ClientHooks.matchHidden = true;
            if (minecraft != null && minecraft.player != null)
                minecraft.player.displayClientMessage(Component.literal("The match is still on. Press K to return, or /gwent forfeit."), true);
        }
        super.onClose();
    }
}
