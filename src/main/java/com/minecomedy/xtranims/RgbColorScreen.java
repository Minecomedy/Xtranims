package com.minecomedy.xtranims;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

public class RgbColorScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int PANEL_W  = 224;
    private static final int SLIDER_H = 14;
    private static final int PREVIEW  = 44;
    private static final int BTN_H    = 18;
    private static final int PAD      = 8;
    private static final int LH       = 9;    // font line height
    private static final int ROW_GAP  = 6;    // gap between rows

    // ── State ─────────────────────────────────────────────────────────────────
    private List<String> keys;
    private int selectedIndex = 0;
    private int r = 255, g = 255, b = 255;

    // Slider geometry (unchanged from working version)
    private int sliderX, sliderW;
    private int sliderRY, sliderGY, sliderBY;
    private boolean draggingR, draggingG, draggingB;

    private EditBox hexField;
    private EditBox nameField;
    private boolean syncingHex = false;
    // Guard name responder against being called with same value
    private String lastNameValue = "";

    // Panel position & size
    private int px, py, panelH;

    // Y positions for render() labels — avoids recomputing every frame
    private int yTitle, yNavRow, yNameLabel, yNameField;
    private int yRLabel, yGLabel, yBLabel;
    private int yHexLabel, yHexRow, yPreviewRow;
    private int ySave, yClose;

    // Cached hex string — only rebuilt when color changes
    private String cachedHex = "#FFFFFF";

    // ── Constructor ───────────────────────────────────────────────────────────
    public RgbColorScreen() {
        super(Component.literal("XtraNimations — RGB Color"));
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        keys = new ArrayList<>(RgbReflectionHelper.getDetectedLabels());
        Collections.sort(keys);

        if (keys.isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(width / 2 - 40, height / 2 + 10, 80, BTN_H).build());
            return;
        }

        if (selectedIndex >= keys.size()) selectedIndex = 0;
        loadSelected();
        cachedHex = toHex(r, g, b);

        // ── Build layout top-to-bottom ────────────────────────────────────────
        // We track a running Y cursor to place each row, then snapshot each
        // position for use in render(). Widgets are placed at the same Y.

        int cx = 0; // relative to px+PAD — added when calling .bounds()
        int cw = PANEL_W - PAD * 2;
        int y  = PAD; // relative to py

        // Title
        yTitle = y;
        y += LH + ROW_GAP;

        // Nav row  (< key >)
        yNavRow = y;
        y += BTN_H + ROW_GAP;

        // "Group name:" label + name field
        yNameLabel = y;
        y += LH + 2;
        yNameField = y;
        y += BTN_H + ROW_GAP;

        // Red label + slider
        yRLabel  = y;
        sliderRY = 0; // computed below after we know py
        y += LH + 2 + SLIDER_H + ROW_GAP;

        // Green label + slider
        yGLabel  = y;
        sliderGY = 0;
        y += LH + 2 + SLIDER_H + ROW_GAP;

        // Blue label + slider
        yBLabel  = y;
        sliderBY = 0;
        y += LH + 2 + SLIDER_H + ROW_GAP + 4; // +4 extra gap before hex

        // Hex label + field row
        yHexLabel = y;
        y += LH + 2;
        yHexRow   = y;
        yPreviewRow = y;  // preview sits at same Y as hex field
        y += Math.max(SLIDER_H, PREVIEW) + ROW_GAP + 4; // preview is taller

        // Save + Close
        ySave  = y;
        y += BTN_H + 4;
        yClose = y;
        y += BTN_H + PAD;

        panelH = y;

        // Centre panel
        px = (width  - PANEL_W) / 2;
        py = (height - panelH)  / 2;

        // Now resolve absolute Y for sliders
        sliderRY = py + yRLabel + LH + 2;
        sliderGY = py + yGLabel + LH + 2;
        sliderBY = py + yBLabel + LH + 2;

        sliderX = px + PAD;
        sliderW = cw;

        int ax = px + PAD; // absolute x

        // Nav buttons
        addRenderableWidget(Button.builder(Component.literal("<"), b -> switchGroup(-1))
                .bounds(ax, py + yNavRow, 20, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> switchGroup(+1))
                .bounds(ax + cw - 20, py + yNavRow, 20, BTN_H).build());

        // Name field
        lastNameValue = RgbColorStore.getDisplayName(keys.get(selectedIndex));
        nameField = new EditBox(font, ax, py + yNameField, cw, BTN_H,
                Component.literal("Name"));
        nameField.setMaxLength(40);
        nameField.setValue(lastNameValue);
        nameField.setResponder(text -> {
            // Guard: only act when value actually changed
            if (!text.equals(lastNameValue)) {
                lastNameValue = text;
                RgbColorStore.setNameInMemory(keys.get(selectedIndex), text);
            }
        });
        addRenderableWidget(nameField);

        // Hex field  (width leaves room for preview square on the right)
        int hexW = cw - PREVIEW - PAD;
        hexField = new EditBox(font, ax, py + yHexRow, hexW, SLIDER_H,
                Component.literal("HEX"));
        hexField.setMaxLength(7);
        hexField.setValue(cachedHex);
        hexField.setResponder(this::onHexTyped);
        addRenderableWidget(hexField);

        // Save / Close buttons
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAndApply())
                .bounds(ax, py + ySave, cw, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(ax, py + yClose, cw, BTN_H).build());
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Override renderBackground to do nothing — we draw our own dark overlay in
     * render() before the panel. This prevents the 1.21 blur shader from being
     * applied twice (once by us, once by super.render()), which caused the screen
     * to appear blurry when our panel was drawn before super.render().
     */
    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty — our render() draws the overlay directly.
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Draw the dark world overlay manually (replaces what renderBackground would do).
        this.renderTransparentBackground(gfx);
        if (keys == null || keys.isEmpty()) {
            gfx.fill(0, 0, width, height, 0x88000000);
            gfx.drawCenteredString(font, "No RGB groups found in model.",
                    width / 2, height / 2 - 12, 0xFFFFAA00);
            gfx.drawCenteredString(font, "Add a Color Filter render effect to any cube.",
                    width / 2, height / 2 + 2, 0xFFCCCCCC);
            super.render(gfx, mouseX, mouseY, partialTick);
            return;
        }

        // ── Panel background — pure fills, no texture bind overhead ──────────
        gfx.fill(px, py, px + PANEL_W, py + panelH, 0xE6141418);
        gfx.fill(px + 2, py + 2, px + PANEL_W - 2, py + 2 + LH + 6, 0xFF1E1E26);
        gfx.renderOutline(px, py, PANEL_W, panelH, 0xFF666677);
        gfx.renderOutline(px + 1, py + 1, PANEL_W - 2, panelH - 2, 0xFF2A2A36);

        int mid = px + PANEL_W / 2;
        int ax  = px + PAD;
        int cw  = PANEL_W - PAD * 2;

        // Title
        gfx.drawCenteredString(font, "§lRGB Color Picker",
                mid, py + yTitle, 0xFFFFFFFF);

        // Group key (centered, between nav arrows)
        gfx.drawCenteredString(font, "§7" + keys.get(selectedIndex),
                mid, py + yNavRow + (BTN_H - LH) / 2, 0xFFAAAAAA);

        // "Group name:" label
        gfx.drawString(font, "§7Group name:",
                ax, py + yNameLabel, 0xFFBBBBBB, false);

        // Red
        gfx.drawString(font, "§cRed: §f" + r,   ax, py + yRLabel, 0xFFFFFFFF, false);
        drawSlider(gfx, ax, sliderRY, cw, SLIDER_H, r, 0xFFCC3333, 0x88330000);

        // Green
        gfx.drawString(font, "§aGreen: §f" + g, ax, py + yGLabel, 0xFFFFFFFF, false);
        drawSlider(gfx, ax, sliderGY, cw, SLIDER_H, g, 0xFF33CC33, 0x88003300);

        // Blue
        gfx.drawString(font, "§9Blue: §f" + b,  ax, py + yBLabel, 0xFFFFFFFF, false);
        drawSlider(gfx, ax, sliderBY, cw, SLIDER_H, b, 0xFF3333CC, 0x88000033);

        // Hex label
        gfx.drawString(font, "§7Hex:", ax, py + yHexLabel, 0xFFBBBBBB, false);

        // Color preview square — right of hex field, same row
        int previewX = ax + cw - PREVIEW;
        gfx.fill(previewX, py + yPreviewRow,
                 previewX + PREVIEW, py + yPreviewRow + PREVIEW,
                 0xFF000000 | (r << 16) | (g << 8) | b);
        gfx.renderOutline(previewX, py + yPreviewRow, PREVIEW, PREVIEW, 0xFF888888);

        // Widgets on top (buttons, text fields)
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private void drawSlider(GuiGraphics gfx, int x, int y, int w, int h,
                            int value, int fill, int bg) {
        gfx.fill(x, y, x + w, y + h, bg);
        int fw = (int)((value / 255f) * w);
        if (fw > 0) gfx.fill(x, y, x + fw, y + h, fill);
        gfx.renderOutline(x, y, w, h, 0xFF555566);
        int kx = x + fw;
        gfx.fill(Math.max(x, kx - 1), y, Math.min(x + w, kx + 2), y + h, 0xFFFFFFFF);
    }

    // ── Mouse / slider input (hitboxes unchanged) ─────────────────────────────
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0) {
            if (onSlider(mx, my, sliderRY)) { draggingR = true; applySlider('R', mx); return true; }
            if (onSlider(mx, my, sliderGY)) { draggingG = true; applySlider('G', mx); return true; }
            if (onSlider(mx, my, sliderBY)) { draggingB = true; applySlider('B', mx); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingR) { applySlider('R', mx); return true; }
        if (draggingG) { applySlider('G', mx); return true; }
        if (draggingB) { applySlider('B', mx); return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingR = draggingG = draggingB = false;
        return super.mouseReleased(mx, my, btn);
    }

    private boolean onSlider(double mx, double my, int sy) {
        return mx >= sliderX && mx <= sliderX + sliderW
                && my >= sy  && my <= sy + SLIDER_H;
    }

    private void applySlider(char ch, double mx) {
        int val = Math.max(0, Math.min(255, (int)(((mx - sliderX) / sliderW) * 255f)));
        switch (ch) { case 'R' -> r = val; case 'G' -> g = val; case 'B' -> b = val; }
        updateHex();
    }

    // ── Hex field ─────────────────────────────────────────────────────────────
    private void onHexTyped(String text) {
        if (syncingHex) return;
        String s = text.startsWith("#") ? text.substring(1) : text;
        if (s.length() == 6) {
            try {
                int packed = Integer.parseInt(s, 16);
                int nr = (packed >> 16) & 0xFF;
                int ng = (packed >>  8) & 0xFF;
                int nb =  packed        & 0xFF;
                // Only act if color actually changed
                if (nr == r && ng == g && nb == b) return;
                r = nr; g = ng; b = nb;
                // Color applied on Save only
            } catch (NumberFormatException ignored) {}
        }
    }

    private void updateHex() {
        String newHex = toHex(r, g, b);
        if (newHex.equals(cachedHex)) return;
        cachedHex = newHex;
        if (hexField == null) return;
        syncingHex = true;
        hexField.setValue(cachedHex);
        syncingHex = false;
    }

    private static String toHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X", r, g, b);
    }

    // ── Preview / save / revert ───────────────────────────────────────────────
    private void saveAndApply() {
        if (keys == null || keys.isEmpty()) return;
        String key = keys.get(selectedIndex);
        RgbColorStore.setInMemory(key, r, g, b);
        // Pass cubeId map so gist models can anchor keys across hex-drift reloads
        RgbColorStore.save(key, RgbReflectionHelper.getGroupToCubeId());
        RgbReflectionHelper.applyColorsForced(); // clears guard so patch always fires
    }

    private void revertCurrent() {
        if (keys == null || keys.isEmpty()) return;
        RgbColorStore.revert(keys.get(selectedIndex));
        RgbReflectionHelper.applyColors();
    }

    private void switchGroup(int dir) {
        // Just move to the next group — do NOT revert/propagate.
        // Unsaved slider changes are discarded by reloadScreen() reading from memory.
        // Calling revertCurrent() here would trigger patchAndResend unnecessarily,
        // causing a visible model refresh flicker on every arrow click.
        selectedIndex = (selectedIndex + dir + keys.size()) % keys.size();
        reloadScreen();
    }

    private void loadSelected() {
        if (keys == null || keys.isEmpty()) return;
        int[] rgb = RgbColorStore.get(keys.get(selectedIndex));
        r = rgb[0]; g = rgb[1]; b = rgb[2];
    }

    private void reloadScreen() {
        loadSelected();
        clearWidgets();
        init();
    }

    @Override
    public void onClose() {
        // Revert in-memory to disk state without triggering patchAndResend.
        // This just resets the color store; the next applyColors (on next tick)
        // will pick up the reverted values naturally.
        if (keys != null && !keys.isEmpty()) {
            RgbColorStore.revert(keys.get(selectedIndex));
        }
        super.onClose();
    }

    // Pause the game while the screen is open.
    // Since colors are applied on Save (not live), there is no reason to keep
    // the world rendering behind the screen — pausing eliminates the dominant
    // FPS cost entirely and also stops the tick handler.
    @Override
    public boolean isPauseScreen() { return true; }
}
