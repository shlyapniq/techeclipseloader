package com.shlyapniq.mixin.client;

import com.shlyapniq.UpdateManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Shadow @Final @Mutable
    private static Component COPYRIGHT_TEXT;

    @Inject(method = "render", at = @At("HEAD"))
    private void modifyCopyright(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        COPYRIGHT_TEXT = Component.literal("✌ Tech Eclipse");
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void renderNewsPanel(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        String news = UpdateManager.getNews();
        if (news == null || news.trim().isEmpty()) return;

        int panelWidth = Math.max(120, Math.min(250, this.width / 4));
        int padding = 8;
        int maxTextWidth = panelWidth - padding * 2;
        int lineHeight = 10;

        // Разбиваем текст по /n и переносим длинные строки
        String[] segments = news.split("/n|\n");
        List<String> lines = new ArrayList<>();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                wrapLine(trimmed, maxTextWidth, lines);
            }
        }

        if (lines.isEmpty()) return;

        // Размеры панели
        int titleHeight = lineHeight + 6;
        int panelHeight = padding + titleHeight + lines.size() * lineHeight + padding;
        int panelX = 8;
        int panelY = (this.height - panelHeight) / 2;

        // Полупрозрачный тёмный фон
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0000000);

        // Рамка
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0x60FFFFFF);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);

        // Заголовок "Новости" (жёлтый)
        graphics.drawString(this.font, "Новости ⌚", panelX + padding, panelY + padding, 0xFFFF00);

        // Разделительная линия
        graphics.fill(
                panelX + padding, panelY + padding + lineHeight + 2,
                panelX + panelWidth - padding, panelY + padding + lineHeight + 3,
                0x60FFFFFF
        );

        // Текст новостей (белый)
        int textY = panelY + padding + titleHeight;
        for (String line : lines) {
            graphics.drawString(this.font, line, panelX + padding, textY, 0xFFFFFF);
            textY += lineHeight;
        }
    }

    /**
     * Переносит строку по словам, чтобы вписать в maxWidth пикселей.
     */
    private void wrapLine(String text, int maxWidth, List<String> out) {
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String test = current.isEmpty() ? word : current + " " + word;
            if (this.font.width(test) > maxWidth && !current.isEmpty()) {
                out.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(test);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
    }
}
