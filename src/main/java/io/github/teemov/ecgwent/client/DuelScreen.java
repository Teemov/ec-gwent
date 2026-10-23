package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.net.Net;
import net.minecraft.client.gui.GuiGraphics;

import java.util.UUID;

/** "<player> challenges you to Gwent" with Accept / Decline. */
public final class DuelScreen extends CanvasScreen {
    private final UUID from;
    private final String name;
    private final int bet;
    private boolean answered;

    public DuelScreen(UUID from, String name, int bet) {
        super("Gwent");
        this.from = from;
        this.name = name;
        this.bet = bet;
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        int x = VW / 2 - 130, y = 110;
        panel(g, x, y, 260, 130);
        int px = x + 10, py = y + 10;
        g.fill(px - 1, py - 1, px + 51, py + 71, 0xFF6A5A30);
        CardArt.scissor(g, px, py, px + 50, py + 70);
        g.fill(px, py, px + 50, py + 70, 0xFF1C1818);
        CardArt.drawSubject(g, "player:" + from, null, px, py, 50, 70, px + 25 - mx, py + 20 - my);
        g.disableScissor();
        text(g, name, x + 70, y + 12, 0xFFFFE9A0);
        wrapped(g, "challenges you to a game of Gwent.", x + 70, y + 26, 180, 0xFFDDDDDD);
        wrapped(g, bet > 0 ? "Stakes: " + bet + " emeralds each. The winner takes " + (2 * bet) + "." : "A friendly match, nothing at stake.",
                x + 70, y + 46, 180, bet > 0 ? 0xFF80FF80 : 0xFFAAAAAA);
        button(g, x + 20, y + 98, 110, 20, "Accept", true, mx, my, () -> answer(true));
        button(g, x + 136, y + 98, 104, 20, "Decline", true, mx, my, () -> answer(false));
    }

    private void answer(boolean accept) {
        answered = true;
        Net.toServer(new Net.DuelAnswer(from, accept));
        onClose();
    }

    @Override
    public void onClose() {
        if (!answered) { answered = true; Net.toServer(new Net.DuelAnswer(from, false)); }
        super.onClose();
    }
}
