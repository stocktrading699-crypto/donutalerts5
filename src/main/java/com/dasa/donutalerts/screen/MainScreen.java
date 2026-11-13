package com.dasa.donutalerts.screen;

import com.dasa.donutalerts.Alerts;
import com.dasa.donutalerts.Config;
import com.dasa.donutalerts.Fmt;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class MainScreen extends BaseScreen {
    private TextFieldWidget minField;

    public MainScreen() {
        super(Text.literal("DonutAlerts"));
    }

    private static String toggleLabel() {
        return "Alerts: " + (Alerts.cfg.enabled ? "ON" : "OFF");
    }

    private static String followLabel() {
        return "Alerts only during auctions: " + (Alerts.cfg.autoWithAuction ? "ON" : "OFF");
    }

    @Override
    protected void init() {
        Config c = Alerts.cfg;
        int cx = width / 2;
        int y = 26;

        addDrawableChild(ButtonWidget.builder(Text.literal(toggleLabel()), b -> {
            c.enabled = !c.enabled;
            b.setMessage(Text.literal(toggleLabel()));
        }).dimensions(cx - 100, y, 200, 20).build());

        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal(followLabel()), b -> {
            c.autoWithAuction = !c.autoWithAuction;
            b.setMessage(Text.literal(followLabel()));
        }).dimensions(cx - 100, y, 200, 20).build());

        // global minimum
        y += 42;
        minField = new TextFieldWidget(textRenderer, cx - 100, y, 70, 18, Text.literal("min"));
        minField.setMaxLength(12);
        minField.setText(Fmt.shortNum(c.globalMin));
        minField.setChangedListener(s -> {
            Double v = Fmt.parseLoose(s);
            if (v != null && v >= 0) c.globalMin = v;
        });
        addDrawableChild(minField);

        String[] presets = {"1M", "10M", "50M", "100M"};
        int[] widths = {24, 28, 28, 34};
        int px = cx - 26;
        for (int i = 0; i < presets.length; i++) {
            String p = presets[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(p), b -> minField.setText(p))
                    .dimensions(px, y - 1, widths[i], 20).build());
            px += widths[i] + 2;
        }

        y += 28;
        addDrawableChild(new SliderWidget(cx - 100, y, 200, 20,
                Text.literal("Volume: " + (int) (c.volume * 100) + "%"), c.volume) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Volume: " + (int) (value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                Alerts.cfg.volume = (float) value;
            }
        });

        y += 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Notification tiers..."),
                b -> client.setScreen(new TierScreen())).dimensions(cx - 100, y, 200, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Auction..."),
                b -> client.setScreen(new AuctionScreen())).dimensions(cx - 100, y, 200, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Gamble queue..."),
                b -> client.setScreen(new QueueScreen())).dimensions(cx - 100, y, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Move / resize..."),
                b -> client.setScreen(new HudEditScreen())).dimensions(cx + 2, y, 98, 20).build());
        y += 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("DonutAlerts"), cx, 12, 0xFFFFD700);
        ctx.drawText(textRenderer, "Only alert for payments of at least:", cx - 100, 80, 0xFFFFFFFF, true);
    }
}
