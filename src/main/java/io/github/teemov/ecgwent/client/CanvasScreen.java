package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.engine.CardDef;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen laid out on a fixed 640×360 canvas that is scaled to fit the window, so the layout is the same
 * at every GUI scale and window size. Buttons are drawn by the screen itself so they scale with it.
 */
public abstract class CanvasScreen extends Screen {
    protected static final int VW = 640, VH = 360;
    /** z layers: cards stack from 0 upwards; overlays and the preview go above all of them. */
    protected static final int Z_OVERLAY = 5000, Z_PREVIEW = 7000;

    protected float scale = 1, offX, offY;
    private final List<Btn> buttons = new ArrayList<>();
    private CardDef preview;
    private int previewStrength;
    private int previewX, previewY;

    private record Btn(int x, int y, int w, int h, boolean enabled, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    protected CanvasScreen(String title) {
        super(Component.literal(title));
    }

    /** Draws the screen in canvas coordinates; mx/my are the mouse in canvas coordinates. */
    protected abstract void draw(GuiGraphics g, int mx, int my, float pt);

    /** A click that no button took. */
    protected boolean clicked(double mx, double my, int button) { return false; }

    protected boolean scrolled(double mx, double my, double delta) { return false; }

    @Override
    public final void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        scale = Math.min(width / (float) VW, height / (float) VH);
        offX = (width - VW * scale) / 2f;
        offY = (height - VH * scale) / 2f;
        int mx = (int) ((mouseX - offX) / scale), my = (int) ((mouseY - offY) / scale);
        buttons.clear();
        preview = null;
        CardArt.setCanvas(scale, offX, offY);
        g.pose().pushPose();
        g.pose().translate(offX, offY, 0);
        g.pose().scale(scale, scale, 1);
        draw(g, mx, my, pt);
        if (preview != null) drawPreview(g, mx, my);
        g.pose().popPose();
        CardArt.setCanvas(1, 0, 0);
    }

    private double cx(double mouseX) { return (mouseX - offX) / scale; }
    private double cy(double mouseY) { return (mouseY - offY) / scale; }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = cx(mouseX), my = cy(mouseY);
        if (button == 0) {
            for (int i = buttons.size() - 1; i >= 0; i--) {
                Btn b = buttons.get(i);
                if (b.contains(mx, my)) {
                    if (b.enabled) {
                        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                        b.action.run();
                    }
                    return true;
                }
            }
        }
        return clicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return scrolled(cx(mouseX), cy(mouseY), delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ drawing helpers

    /** A button; draw it inside the layer it belongs to (call within an overlay's z translation for overlays). */
    protected void button(GuiGraphics g, int x, int y, int w, int h, String label, boolean enabled, int mx, int my, Runnable action) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, enabled ? (hover ? 0xFFE0C060 : 0xFF8A7440) : 0xFF404040);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, enabled ? (hover ? 0xFF4A3E22 : 0xFF2E2716) : 0xFF262626);
        int col = enabled ? (hover ? 0xFFFFF0B0 : 0xFFF0E0B0) : 0xFF808080;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, col, false);
        buttons.add(new Btn(x, y, w, h, enabled, action));
    }

    /** A dark panel with a thin gold edge. */
    protected static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF6A5A30);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xE8141210);
    }

    protected void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, false);
    }

    protected void small(GuiGraphics g, String s, int x, int y, int maxW, int color) {
        CardArt.small(g, font, s, x, y, maxW, color, 0.75f);
    }

    /** Wrapped text at 75% size. Returns the height used. */
    protected int wrapped(GuiGraphics g, String s, int x, int y, int w, int color) {
        float sc = 0.75f;
        List<FormattedCharSequence> lines = font.split(FormattedText.of(s), (int) (w / sc));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(sc, sc, 1);
        int ly = 0;
        for (FormattedCharSequence l : lines) {
            g.drawString(font, l, 0, ly, color, false);
            ly += 10;
        }
        g.pose().popPose();
        return (int) Math.ceil(ly * sc);
    }

    /** Shows a large version of this card next to the mouse at the end of the frame. */
    protected void preview(CardDef d, int strength) {
        preview = d;
        previewStrength = strength;
    }

    private void drawPreview(GuiGraphics g, int mx, int my) {
        int w = 84, h = 118, boxW = 150;
        List<String> lines = Texts.describe(preview);
        int x = mx + 14 + w + boxW > VW ? mx - 14 - w - boxW : mx + 14;
        int y = Math.max(4, Math.min(VH - h - 4, my - h / 2));
        g.pose().pushPose();
        g.pose().translate(0, 0, Z_PREVIEW);
        CardArt.draw(g, preview, x, y, w, h, previewStrength, false, x + w / 2f - mx, y + 30 - my);
        g.pose().translate(0, 0, 300);
        int tx = x + w + 4, th = 14;
        for (String l : lines) th += (font.split(FormattedText.of(l), (int) ((boxW - 8) / 0.75f)).size()) * 8 + 2;
        panel(g, tx, y, boxW, Math.max(th, 30));
        text(g, preview.name, tx + 4, y + 4, preview.isHero() ? 0xFFE9C84A : 0xFFFFFFFF);
        int ly = y + 15;
        for (String l : lines) ly += wrapped(g, l, tx + 4, ly, boxW - 8, 0xFFD0D0D0) + 2;
        g.pose().popPose();
    }

    @SuppressWarnings("unused")
    private static ChatFormatting unused;
}
