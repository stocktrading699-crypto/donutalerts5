package com.dasa.donutalerts.screen;

import com.dasa.donutalerts.Alerts;
import com.dasa.donutalerts.Config;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

import java.util.List;

public class HudEditScreen extends BaseScreen {
    // 0 = payment alerts, 1 = auction box, 2 = gamble queue
    private int drag = -1;
    private int offX, offY;

    public HudEditScreen() {
        super(Text.literal("Move boxes"));
    }

    private PlayerListEntry mySkin() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return null;
        return mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
    }

    private List<Alerts.QEntry> sampleQueue() {
        String me = Alerts.sampleName();
        return List.of(new Alerts.QEntry(me, 50_000_000),
                new Alerts.QEntry("PlayerTwo", 75_000_000),
                new Alerts.QEntry("PlayerThree", 60_000_000));
    }

    private int[] pos(int id) {
        return id == 0 ? Alerts.toastPos(width, height) : id == 1 ? Alerts.auctionPos(width, height) : Alerts.queuePos(width, height);
    }

    private int bw(int id) {
        return id == 0 ? Alerts.toastW() : id == 1 ? Alerts.aucW() : Alerts.queueW();
    }

    private int bh(int id) {
        return id == 0 ? Alerts.toastH() : id == 1 ? Alerts.aucH()
                : Math.round(Alerts.queueBoxH(sampleQueue().size()) * Alerts.cfg.queueScale);
    }

    private int hit(double mx, double my) {
        int[] order = {2, 1, 0};
        for (int id : order) {
            int[] p = pos(id);
            if (mx >= p[0] && mx <= p[0] + bw(id) && my >= p[1] && my <= p[1] + bh(id)) return id;
        }
        return -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x55000000);
        PlayerListEntry skin = mySkin();
        Config c = Alerts.cfg;
        MatrixStack m = ctx.getMatrices();

        Alerts.drawBannerSample(ctx, width, height, skin);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Big center alerts (scroll anywhere else to resize)"), width / 2, (int) (height * 0.2f) - 12, 0xFFAAAAAA);

        int[] t = pos(0);
        m.push();
        m.translate(t[0], t[1], 0f);
        m.scale(c.toastScale, c.toastScale, 1f);
        Alerts.drawToast(ctx, 0, 0, Alerts.sampleName(), 5_000_000, 0x55FF55, skin);
        m.pop();
        ctx.drawText(textRenderer, "Payment alerts", t[0], t[1] - 10, 0xFFFFFFFF, true);

        int[] a = pos(1);
        m.push();
        m.translate(a[0], a[1], 0f);
        m.scale(c.auctionScale, c.auctionScale, 1f);
        Alerts.drawAuctionBox(ctx, 0, 0, true, 30_000, 10_000_000, 1, "Elytra", "minecraft:elytra", Alerts.sampleName(), 12_000_000, skin);
        m.pop();
        ctx.drawText(textRenderer, "Auction box", a[0], a[1] - 10, 0xFFFFFFFF, true);

        int[] q = pos(2);
        m.push();
        m.translate(q[0], q[1], 0f);
        m.scale(c.queueScale, c.queueScale, 1f);
        Alerts.drawQueueBox(ctx, 0, 0, sampleQueue());
        m.pop();
        ctx.drawText(textRenderer, "Gamble queue" + (c.queueEnabled ? "" : " (OFF)"), q[0], q[1] - 10, 0xFFFFFFFF, true);

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Drag boxes to move. Scroll wheel over a box to resize it. Esc when done."), width / 2, 6, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int id = hit(mx, my);
            if (id >= 0) {
                int[] p = pos(id);
                drag = id;
                offX = (int) mx - p[0];
                offY = (int) my - p[1];
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (drag >= 0) {
            Config c = Alerts.cfg;
            double fx = Math.max(0, Math.min(1, (mx - offX) / (double) width));
            double fy = Math.max(0, Math.min(1, (my - offY) / (double) height));
            if (drag == 0) {
                c.toastX = fx;
                c.toastY = fy;
            } else if (drag == 1) {
                c.auctionX = fx;
                c.auctionY = fy;
            } else {
                c.queueX = fx;
                c.queueY = fy;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        drag = -1;
        Alerts.cfg.save();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        Config c = Alerts.cfg;
        float d = (float) (vertical * 0.1);
        int id = hit(mx, my);
        if (id == 0) c.toastScale = Alerts.clampScale(c.toastScale + d);
        else if (id == 1) c.auctionScale = Alerts.clampScale(c.auctionScale + d);
        else if (id == 2) c.queueScale = Alerts.clampScale(c.queueScale + d);
        else c.bannerScale = Alerts.clampScale(c.bannerScale + d);
        c.save();
        return true;
    }
}
