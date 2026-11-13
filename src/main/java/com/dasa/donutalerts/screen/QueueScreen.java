package com.dasa.donutalerts.screen;

import com.dasa.donutalerts.Alerts;
import com.dasa.donutalerts.Config;
import com.dasa.donutalerts.Fmt;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

public class QueueScreen extends BaseScreen {
    public QueueScreen() {
        super(Text.literal("Gamble queue"));
    }

    private static String toggleLabel() {
        return "Queue: " + (Alerts.cfg.queueEnabled ? "ON" : "OFF");
    }

    @Override
    protected void init() {
        Config c = Alerts.cfg;
        int cx = width / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal(toggleLabel()), b -> {
            c.queueEnabled = !c.queueEnabled;
            b.setMessage(Text.literal(toggleLabel()));
        }).dimensions(cx - 100, 26, 200, 20).build());

        TextFieldWidget min = new TextFieldWidget(textRenderer, cx - 100, 70, 96, 16, Text.literal("min"));
        min.setMaxLength(12);
        min.setText(Fmt.shortNum(c.queueMin));
        min.setChangedListener(s -> {
            Double v = Fmt.parseLoose(s);
            if (v != null && v >= 0) c.queueMin = v;
        });
        addDrawableChild(min);

        TextFieldWidget max = new TextFieldWidget(textRenderer, cx + 4, 70, 96, 16, Text.literal("max"));
        max.setMaxLength(12);
        max.setText(Fmt.shortNum(c.queueMax));
        max.setChangedListener(s -> {
            Double v = Fmt.parseLoose(s);
            if (v != null && v > 0) c.queueMax = v;
        });
        addDrawableChild(max);

        addDrawableChild(ButtonWidget.builder(Text.literal("Next (done)"), b -> Alerts.queueNext())
                .dimensions(cx - 100, 94, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), b -> Alerts.queueClear())
                .dimensions(cx + 2, 94, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> client.setScreen(new MainScreen()))
                .dimensions(cx - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Gamble queue"), cx, 12, 0xFF5599FF);
        ctx.drawText(textRenderer, "Min payment", cx - 100, 58, 0xFF5599FF, true);
        ctx.drawText(textRenderer, "Max payment", cx + 4, 58, 0xFF5599FF, true);
        ctx.drawText(textRenderer, "Payments in this range join the queue.", cx - 100, 120, 0xFF888888, false);
        ctx.drawText(textRenderer, "Not counted while an auction is running.", cx - 100, 130, 0xFF888888, false);

        List<Alerts.QEntry> q = Alerts.queue;
        int y = 146;
        if (q.isEmpty()) {
            ctx.drawText(textRenderer, "Queue is empty", cx - 100, y, 0xFFAAAAAA, true);
            return;
        }
        for (int i = 0; i < q.size() && i < 7; i++) {
            Alerts.QEntry e = q.get(i);
            String line = (i == 0 ? "Now: " : (i + 1) + ". ") + e.name + " - " + Fmt.money(e.amount);
            ctx.drawText(textRenderer, line, cx - 100, y, i == 0 ? 0xFF55FF55 : 0xFFFFFFFF, true);
            y += 11;
        }
        if (q.size() > 7) ctx.drawText(textRenderer, "+" + (q.size() - 7) + " more", cx - 100, y, 0xFF888888, false);
    }
}
