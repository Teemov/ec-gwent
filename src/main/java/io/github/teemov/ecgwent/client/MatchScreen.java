package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import io.github.teemov.ecgwent.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The match board. The server is in charge; this screen shows its latest snapshot and sends the player's actions. */
public final class MatchScreen extends Screen {
    private static final String[] ROWS = {"close", "ranged", "siege"};

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
    private CardDef hovered;
    private int hoveredStrength = Integer.MIN_VALUE;

    public MatchScreen() {
        super(Component.literal("Gwent"));
    }

    private static Map<String, Object> m() { return ClientHooks.match; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> o, String k) { return (Map<String, Object>) o.get(k); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> o, String k) {
        Object v = o.get(k);
        return v == null ? Collections.emptyList() : (List<Map<String, Object>>) v;
    }

    private static int num(Map<String, Object> o, String k) { Object v = o.get(k); return v instanceof Double ? ((Double) v).intValue() : 0; }
    private static boolean bool(Map<String, Object> o, String k) { return Boolean.TRUE.equals(o.get(k)); }
    private static String str(Map<String, Object> o, String k) { Object v = o.get(k); return v == null ? "" : v.toString(); }
    private static CardDef def(String key) { return ClientHooks.cards.get(key); }

    public void refresh() {
        if (!(m().get("choice") instanceof Map)) picks.clear();
        if (selected >= 0 && list(map(m(), "me"), "hand").stream().noneMatch(c -> num(c, "uid") == selected)) selected = -1;
        init(minecraft, width, height);
    }

    private String phase() { return str(m(), "phase"); }
    private boolean over() { return m().containsKey("winner"); }

    @Override
    protected void init() {
        clearWidgets();
        Map<String, Object> me = map(m(), "me");
        int bw = 80;
        if (over()) {
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> {
                Net.toServer(Net.MatchAction.of("close"));
                ClientHooks.match = null;
                onClose();
            }).bounds(width / 2 - 40, height / 2 + 30, 80, 20).build());
            return;
        }
        Map<String, Object> choice = map(m(), "choice");
        if (phase().equals("CHOOSE_FIRST") && choice != null) {
            addRenderableWidget(Button.builder(Component.literal("I go first"), b -> Net.toServer(Net.MatchAction.of("first_me")))
                    .bounds(width / 2 - 105, height / 2, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("They go first"), b -> Net.toServer(Net.MatchAction.of("first_them")))
                    .bounds(width / 2 + 5, height / 2, 100, 20).build());
            return;
        }
        if (phase().equals("REDRAW") && num(me, "redrawsLeft") > 0) {
            addRenderableWidget(Button.builder(Component.literal("Keep this hand"), b -> Net.toServer(Net.MatchAction.of("redraw_done")))
                    .bounds(width / 2 - 50, height - 30, 100, 20).build());
            return;
        }
        if (choice != null) {
            int count = num(choice, "count");
            String label = count == 0 ? "OK" : "Confirm";
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                if (picks.size() != count) return;
                int[] arr = picks.stream().mapToInt(Integer::intValue).toArray();
                Net.toServer(new Net.MatchAction("choose", -1, "", -1, arr));
                picks.clear();
            }).bounds(width / 2 - 40, height - 30, 80, 20).build());
            return;
        }
        Button pass = Button.builder(Component.literal("Pass"), b -> Net.toServer(Net.MatchAction.of("pass")))
                .bounds(6, height - 26, bw, 20).build();
        pass.active = bool(m(), "myTurn");
        addRenderableWidget(pass);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        hits.clear();
        hovered = null;
        hoveredStrength = Integer.MIN_VALUE;
        if (m() == null) return;
        Map<String, Object> me = map(m(), "me"), them = map(m(), "them");

        int lp = Math.min(130, width / 5), rp = 56;
        int handH = Math.max(44, height / 6);
        int bx0 = lp + 4, bx1 = width - rp - 4, by0 = 16, by1 = height - handH - 8;
        int rowH = (by1 - by0) / 6;
        g.fill(bx0 - 2, by0 - 2, bx1 + 2, by1 + 2, 0xC0101010);

        String weather = str(m(), "weather");
        CardDef sel = selectedDef();
        for (int i = 0; i < 6; i++) {
            boolean mine = i >= 3;
            String row = mine ? ROWS[i - 3] : ROWS[2 - i];
            int y = by0 + i * rowH;
            Map<String, Object> side = mine ? me : them;
            Map<String, Object> r = map(map(side, "rows"), row);
            boolean target = rowTargetable(sel, mine, row);
            int bg = weather.contains(row) ? 0x60406090 : (i % 2 == 0 ? 0x30FFFFFF : 0x20FFFFFF);
            g.fill(bx0, y + 1, bx1, y + rowH - 1, bg);
            if (target) g.fill(bx0, y + 1, bx1, y + rowH - 1, 0x40FFE080);
            hits.add(new Hit(bx0, y, bx1 - bx0, rowH, "row", -1, mine, row));
            // Row score and horn slot
            String sc = String.valueOf(num(r, "score"));
            g.fill(bx0 + 2, y + rowH / 2 - 7, bx0 + 20, y + rowH / 2 + 7, 0xFF2A2A2A);
            g.drawCenteredString(font, sc, bx0 + 11, y + rowH / 2 - 4, 0xFFFFFFFF);
            int slotW = (int) ((rowH - 4) * 0.7);
            int sx = bx0 + 24;
            g.fill(sx, y + 2, sx + slotW, y + rowH - 2, 0x30000000);
            CardDef sp = def(str(r, "special"));
            if (sp != null) {
                CardArt.draw(g, sp, sx, y + 2, slotW, rowH - 4, Integer.MIN_VALUE, false, 10, 0);
                if (in(mx, my, sx, y + 2, slotW, rowH - 4)) hovered = sp;
            }
            g.drawString(font, row.substring(0, 1).toUpperCase(), sx + slotW + 2, y + 2, 0x60FFFFFF, false);
            // Cards
            List<Map<String, Object>> cards = list(r, "cards");
            int cw = slotW, ch = rowH - 4;
            int areaX = sx + slotW + 10, areaW = bx1 - areaX - 4;
            int step = cards.isEmpty() ? cw : Math.min(cw + 2, (areaW - cw) / Math.max(1, cards.size() - 1));
            int startX = areaX + Math.max(0, (areaW - (step * (cards.size() - 1) + cw)) / 2);
            for (int k = 0; k < cards.size(); k++) {
                Map<String, Object> c = cards.get(k);
                CardDef d = def(str(c, "key"));
                if (d == null) continue;
                int cx = startX + k * step;
                boolean decoyTarget = sel != null && sel.has("decoy") && mine && !d.isHero() && !d.has("decoy");
                boolean hov = in(mx, my, cx, y + 2, Math.min(step, cw), ch);
                CardArt.draw(g, d, cx, y + 2, cw, ch, num(c, "strength"), decoyTarget || hov, 12, 2);
                hits.add(new Hit(cx, y + 2, Math.min(step, cw), ch, "board", num(c, "uid"), mine, row));
                if (hov) { hovered = d; hoveredStrength = num(c, "strength"); }
            }
        }
        g.fill(bx0, by0 + rowH * 3 - 1, bx1, by0 + rowH * 3 + 1, 0xFFC9A227);

        drawPanel(g, them, 4, by0, lp - 4, false, mx, my);
        drawPanel(g, me, 4, by0 + rowH * 3 + 4, lp - 4, true, mx, my);
        // Weather and round in the middle of the left panel
        int wy = by0 + rowH * 3 - 12;
        g.drawString(font, "Round " + num(m(), "round") + (weather.isEmpty() ? "" : " · " + weather), 6, wy, 0xFF9FC6FF);

        // Right panel: decks and graveyards
        g.drawString(font, "Deck " + num(them, "deckCount"), width - rp, by0 + 2, 0xFFBBBBBB);
        g.drawString(font, "Grave " + list(them, "grave").size(), width - rp, by0 + 14, 0xFFBBBBBB);
        g.drawString(font, "Deck " + num(me, "deckCount"), width - rp, by1 - 22, 0xFFBBBBBB);
        g.drawString(font, "Grave " + list(me, "grave").size(), width - rp, by1 - 10, 0xFFBBBBBB);

        // Hand
        List<Map<String, Object>> hand = list(me, "hand");
        int hw = (int) ((handH - 6) * 0.7), hh = handH - 6;
        int hx0 = lp + 8, hxw = width - rp - hx0 - 8;
        int hstep = hand.isEmpty() ? hw : Math.min(hw + 3, (hxw - hw) / Math.max(1, hand.size() - 1));
        int hstart = hx0 + Math.max(0, (hxw - (hstep * (hand.size() - 1) + hw)) / 2);
        for (int k = 0; k < hand.size(); k++) {
            Map<String, Object> c = hand.get(k);
            CardDef d = def(str(c, "key"));
            if (d == null) continue;
            int uid = num(c, "uid");
            int cx = hstart + k * hstep, cy = height - handH + (uid == selected ? -6 : 0);
            boolean hov = in(mx, my, cx, cy, Math.min(hstep, hw), hh);
            CardArt.draw(g, d, cx, cy, hw, hh, Integer.MIN_VALUE, uid == selected || hov, cx + hw / 2f - mx, cy + 10 - my);
            hits.add(new Hit(cx, cy, Math.min(hstep, hw), hh, "hand", uid, true, null));
            if (hov) hovered = d;
        }

        // Log
        @SuppressWarnings("unchecked")
        List<Object> log = (List<Object>) m().get("log");
        if (log != null) {
            int ly = 3;
            int from = Math.max(0, log.size() - 1);
            for (int i = from; i < log.size(); i++) {
                g.drawCenteredString(font, log.get(i).toString(), (bx0 + bx1) / 2, ly, 0xFFFFE9A0);
                ly += 10;
            }
        }
        String status = statusLine();
        if (!status.isEmpty()) g.drawString(font, status, lp + 8, by1 + 1, 0xFFDDDDDD);

        super.render(g, mx, my, pt);
        drawOverlays(g, mx, my);
        if (hovered != null) drawPreview(g, hovered, hoveredStrength, mx, my);
    }

    private String statusLine() {
        if (over()) return "";
        CardDef sel = selectedDef();
        if (sel != null) {
            if (sel.has("decoy")) return "Click one of your units to take it back.";
            if (sel.has("horn") || sel.has("mardroeme")) return "Click one of your rows.";
            if (sel.type == CardDef.Type.WEATHER || sel.has("scorch") && !sel.isUnit()) return "Click the board (or the card again) to play it.";
            if (sel.isAgile()) return "Click your close or ranged row.";
            return "Click the highlighted row (or the card again) to play it. Right-click to cancel.";
        }
        if (m().containsKey("waiting") || (phase().equals("PLAY") && !bool(m(), "myTurn"))) return "Opponent is thinking…";
        if (bool(m(), "myTurn")) return "Your turn: pick a card" + (bool(map(m(), "me"), "leaderUsable") ? ", use your leader" : "") + " or pass.";
        return "";
    }

    private void drawPanel(GuiGraphics g, Map<String, Object> s, int x, int y, int w, boolean mine, int mx, int my) {
        g.fill(x, y, x + w, y + 92, 0xA0101010);
        g.drawString(font, str(s, "name"), x + 4, y + 4, mine ? 0xFF9FFF9F : 0xFFFF9F9F);
        String gems = "";
        for (int i = 0; i < 2; i++) gems += i < num(s, "gems") ? "◆" : "◇";
        g.drawString(font, gems + "  " + num(s, "handCount") + " cards", x + 4, y + 16, 0xFFE05050);
        g.drawString(font, String.valueOf(num(s, "score")), x + w - 4 - font.width(String.valueOf(num(s, "score"))), y + 4, 0xFFFFFFFF);
        if (bool(s, "passed")) g.drawString(font, "Passed", x + 4, y + 28, 0xFFAAAAAA);
        if (!mine) {
            String ent = str(m(), "opponentEntity");
            g.enableScissor(x + w - 40, y + 14, x + w - 2, y + 90);
            CardArt.backdrop(g, str(s, "faction"), x + w - 40, y + 14, 38, 76);
            CardArt.drawSubject(g, ent, null, x + w - 40, y + 14, 38, 76, x + w - 21 - mx, y + 30 - my);
            g.disableScissor();
        }
        CardDef leader = def(str(s, "leader"));
        if (leader != null) {
            int lw = 30, lh = 44, lx = x + 4, ly = y + 42;
            boolean usable = mine && bool(s, "leaderUsable");
            CardArt.draw(g, leader, lx, ly, lw, lh, Integer.MIN_VALUE, usable, 10, 2);
            if (!bool(s, "leaderReady")) g.fill(lx, ly, lx + lw, ly + lh, 0xA0000000);
            if (mine) hits.add(new Hit(lx, ly, lw, lh, "leader", -1, true, null));
            if (in(mx, my, lx, ly, lw, lh)) hovered = leader;
            if (usable) g.drawString(font, "Click to use", lx + lw + 4, ly + 18, 0xFFFFE080);
        }
    }

    private void drawOverlays(GuiGraphics g, int mx, int my) {
        Map<String, Object> me = map(m(), "me");
        if (over()) {
            g.fill(0, 0, width, height, 0xB0000000);
            String w = str(m(), "winner");
            g.drawCenteredString(font, w.equals("me") ? "Victory!" : w.equals("them") ? "Defeat" : "Draw", width / 2, height / 2 - 40, 0xFFFFE080);
            String scores = "";
            g.drawCenteredString(font, str(m(), "result"), width / 2, height / 2 - 20, 0xFFFFFFFF);
            g.drawCenteredString(font, scores, width / 2, height / 2, 0xFFAAAAAA);
            return;
        }
        Map<String, Object> choice = map(m(), "choice");
        if (phase().equals("CHOOSE_FIRST") && choice != null) {
            g.fill(0, 0, width, height, 0xA0000000);
            g.drawCenteredString(font, "Orc perk: who goes first?", width / 2, height / 2 - 20, 0xFFFFE080);
            return;
        }
        if (phase().equals("REDRAW") && num(me, "redrawsLeft") > 0) {
            g.fill(0, 0, width, height - 36, 0xA0000000);
            g.drawCenteredString(font, "Redraw up to " + num(me, "redrawsLeft") + " card(s): click a card to swap it.", width / 2, 20, 0xFFFFE080);
            drawOptions(g, list(me, "hand"), mx, my, false);
            return;
        }
        if (phase().equals("REDRAW")) {
            g.drawCenteredString(font, "Waiting for your opponent…", width / 2, height / 2, 0xFFFFE080);
            return;
        }
        if (choice != null) {
            g.fill(0, 0, width, height - 36, 0xA0000000);
            g.drawCenteredString(font, choiceTitle(str(choice, "kind"), num(choice, "count")), width / 2, 20, 0xFFFFE080);
            drawOptions(g, list(choice, "options"), mx, my, true);
        }
    }

    private static String choiceTitle(String kind, int count) {
        switch (kind) {
            case "MEDIC": return "Pick a unit to bring back";
            case "LEADER_WEATHER": return "Pick a weather card to play";
            case "LEADER_OWN_GRAVE": return "Pick a card from your graveyard";
            case "LEADER_ENEMY_GRAVE": return "Pick a card from your opponent's graveyard";
            case "DISCARD_TWO": return "Pick 2 cards to discard";
            case "PICK_FROM_DECK": return "Pick a card from your deck";
            case "PEEK": return "Cards in your opponent's hand";
            default: return "Pick " + count;
        }
    }

    private void drawOptions(GuiGraphics g, List<Map<String, Object>> opts, int mx, int my, boolean selectable) {
        int cw = 48, ch = 70, gap = 6;
        int perRow = Math.max(1, (width - 40) / (cw + gap));
        int rows = (opts.size() + perRow - 1) / perRow;
        int y0 = Math.max(36, (height - 36 - rows * (ch + gap)) / 2);
        for (int i = 0; i < opts.size(); i++) {
            int inRow = Math.min(perRow, opts.size() - (i / perRow) * perRow);
            int x0 = (width - inRow * (cw + gap)) / 2;
            int x = x0 + (i % perRow) * (cw + gap), y = y0 + (i / perRow) * (ch + gap);
            CardDef d = def(str(opts.get(i), "key"));
            if (d == null) continue;
            int uid = num(opts.get(i), "uid");
            boolean hov = in(mx, my, x, y, cw, ch);
            CardArt.draw(g, d, x, y, cw, ch, Integer.MIN_VALUE, hov || picks.contains(uid), x + cw / 2f - mx, y + 10 - my);
            hits.add(new Hit(x, y, cw, ch, "option", uid, true, null));
            if (hov) hovered = d;
        }
    }

    private void drawPreview(GuiGraphics g, CardDef d, int strength, int mx, int my) {
        int w = 76, h = 108;
        int x = mx + 12 + w > width ? mx - w - 12 : mx + 12;
        int y = Math.max(2, Math.min(height - h - 50, my - h / 2));
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        CardArt.draw(g, d, x, y, w, h, strength, false, x + w / 2f - mx, y + 20 - my);
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(d.name));
        for (String s : Texts.describe(d)) tip.add(Component.literal(s).withStyle(ChatFormatting.GRAY));
        g.pose().popPose();
        g.renderComponentTooltip(font, tip, x - 8, y + h + 14);
    }

    // ------------------------------------------------------------------ input

    private CardDef selectedDef() {
        if (selected < 0) return null;
        for (Map<String, Object> c : list(map(m(), "me"), "hand")) if (num(c, "uid") == selected) return def(str(c, "key"));
        return null;
    }

    private boolean rowTargetable(CardDef d, boolean mine, String row) {
        if (d == null) return false;
        if (d.isUnit()) {
            if (d.has("spy")) return !mine && row.equals(d.placement);
            if (d.isAgile()) return mine && !row.equals("siege");
            return mine && row.equals(d.placement);
        }
        if (d.has("horn") || d.has("mardroeme")) {
            Map<String, Object> r = map(map(map(m(), "me"), "rows"), row);
            return mine && str(r, "special").isEmpty();
        }
        return !d.has("decoy");
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (m() == null || over()) return false;
        if (button == 1) { selected = -1; return true; }
        Map<String, Object> me = map(m(), "me");
        // Overlays first
        if (phase().equals("REDRAW") && num(me, "redrawsLeft") > 0) {
            for (Hit h : hits) if (h.kind.equals("option") && h.contains(mx, my)) {
                Net.toServer(new Net.MatchAction("redraw", h.uid, "", -1, new int[0]));
                return true;
            }
            return false;
        }
        Map<String, Object> choice = map(m(), "choice");
        if (choice != null) {
            int count = num(choice, "count");
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
        // Topmost hits were added last.
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(mx, my)) continue;
            switch (h.kind) {
                case "hand":
                    if (h.uid == selected) { playSelected(null); return true; }
                    selected = h.uid;
                    return true;
                case "leader":
                    if (bool(me, "leaderUsable")) Net.toServer(Net.MatchAction.of("leader"));
                    return true;
                case "board": {
                    CardDef sel = selectedDef();
                    if (sel != null && sel.has("decoy") && h.mine) {
                        Net.toServer(new Net.MatchAction("play", selected, "", h.uid, new int[0]));
                        selected = -1;
                        return true;
                    }
                    break; // fall through to the row underneath
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
        if (d == null) return;
        if (d.has("decoy")) return;
        if (row == null) {
            if (d.isUnit() && !d.isAgile()) row = d.placement;
            else if (d.isUnit() || d.has("horn") || d.has("mardroeme")) return;
        }
        Net.toServer(new Net.MatchAction("play", selected, d.isUnit() && !d.isAgile() ? "" : (row == null ? "" : row), -1, new int[0]));
        selected = -1;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void onClose() {
        if (m() != null && !over()) ClientHooks.matchHidden = true;
        if (m() != null && !over() && minecraft != null && minecraft.player != null)
            minecraft.player.displayClientMessage(Component.literal("The match is still on. Press K to return, or /gwent forfeit."), true);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
