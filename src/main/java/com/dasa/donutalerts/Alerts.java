package com.dasa.donutalerts;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Alerts {
    private Alerts() {}

    public static Config cfg = Config.load();

    // Matches: "Name paid you $1M", "Name paid you $ 11M", "Name paid you $75K", "$1,000,000"
    private static final Pattern PAY = Pattern.compile(
            "(\\S+)\\s+paid you\\s+\\$\\s*([\\d,]*\\.?\\d+)\\s*([KkMmBbTt]?)\\b");

    public static final int TOAST_W = 180, TOAST_H = 38;
    public static final int AUC_W = 230, AUC_H = 62;
    public static final int QUEUE_W = 200;

    // ---------------------------------------------------------------- state
    public static final class Toast {
        final String name;
        final double amount;
        final Config.Tier tier;
        final PlayerListEntry skin;
        final long start = System.currentTimeMillis();

        Toast(String name, double amount, Config.Tier tier, PlayerListEntry skin) {
            this.name = name;
            this.amount = amount;
            this.tier = tier;
            this.skin = skin;
        }
    }

    private static final List<Toast> toasts = new ArrayList<>();
    private static Toast banner;
    private static final List<Config.Effect> pending = new ArrayList<>();

    // auction
    private static boolean aActive, aEnded;
    private static long aEnd, aEndedUntil;
    private static double aMin, aBid;
    private static int aQty;
    private static String aItem = "", aItemId = "minecraft:elytra", aBidder;
    private static PlayerListEntry aSkin;
    private static boolean aSum;
    private static long offAt = 0; // when to auto-switch alerts off after an auction (end + late-bid window)
    private static final Map<String, Double> aTotals = new HashMap<>();

    private static String lastKey = "";
    private static long lastKeyTime = 0;

    // ---------------------------------------------------------------- gamble queue
    public static final class QEntry {
        public final String name;
        public final double amount;

        public QEntry(String name, double amount) {
            this.name = name;
            this.amount = amount;
        }
    }

    public static final List<QEntry> queue = new ArrayList<>();

    public static void queueNext() {
        if (!queue.isEmpty()) queue.remove(0);
    }

    public static void queueClear() {
        queue.clear();
    }

    // ---------------------------------------------------------------- input
    public static void onMessage(Text message) {
        try {
            handleMessage(message);
        } catch (Throwable t) {
            System.err.println("[DonutAlerts] message error: " + t);
        }
    }

    private static void handleMessage(Text message) {
        Matcher m = PAY.matcher(message.getString());
        if (!m.find()) return;
        double amount;
        try {
            amount = Fmt.parseLoose(m.group(2) + m.group(3));
        } catch (Exception e) {
            return;
        }
        String name = m.group(1);

        // ignore the same message arriving twice
        String key = name + "|" + amount;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastKeyTime < 150) return;
        lastKey = key;
        lastKeyTime = now;

        handlePayment(name, amount);
    }

    public static void handlePayment(String name, double amount) {
        // Bids still count for a few seconds after the end (stream delay)
        boolean late = aEnded && System.currentTimeMillis() <= aEnd + cfg.lateBidSeconds * 1000L;
        if (aActive || late) {
            double total = aSum ? aTotals.merge(name, amount, Double::sum) : amount;
            if (total >= aMin && total > aBid) {
                aBid = total;
                aBidder = name;
                resolveHead(name);
                if (late) playSound("Level Up");
            }
        }
        // gamble queue (not while an auction is collecting bids)
        if (cfg.queueEnabled && !(aActive || late) && amount >= cfg.queueMin && amount <= cfg.queueMax) {
            queue.add(new QEntry(name, amount));
            resolveHead(name);
        }

        if (!cfg.enabled || amount < cfg.globalMin) return;
        Config.Tier t = cfg.tierFor(amount);
        if (t == null) return;
        trigger(name, amount, t);
    }

    public static void trigger(String name, double amount, Config.Tier t) {
        resolveHead(name);
        Toast toast = new Toast(name, amount, t, skinFor(name));
        if (t.display == Config.Display.CENTER) {
            banner = toast;
        } else {
            toasts.add(0, toast);
            while (toasts.size() > 5) toasts.remove(toasts.size() - 1);
        }
        if (t.effect != Config.Effect.NONE) pending.add(t.effect);
        playSound(t.sound);
    }

    private static PlayerListEntry skinFor(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return null;
        return mc.getNetworkHandler().getPlayerListEntry(name);
    }

    public static void playSound(String friendlyName) {
        String id = Config.SOUNDS.get(friendlyName);
        if (id == null || id.isEmpty()) return;
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return;
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvent.of(ident), 1.0f, cfg.volume));
    }

    // ---------------------------------------------------------------- auction
    public static void startAuction() {
        aActive = true;
        aEnded = false;
        int secs = Fmt.parseDuration(cfg.auctionTime);
        if (secs <= 0) secs = 30;
        aEnd = System.currentTimeMillis() + secs * 1000L;
        aMin = cfg.auctionMin;
        aQty = cfg.auctionQty;
        aItemId = cfg.auctionItemId;
        aItem = itemName(aItemId);
        aBidder = null;
        aBid = 0;
        aSkin = null;
        aSum = cfg.sumBids;
        offAt = 0;
        aTotals.clear();
        if (cfg.autoWithAuction) cfg.enabled = true;
    }

    private static Item itemOf(String id) {
        Identifier i = Identifier.tryParse(id == null ? "" : id);
        return i == null ? Items.ELYTRA : Registries.ITEM.get(i);
    }

    public static String itemName(String id) {
        return itemOf(id).getName().getString();
    }

    public static ItemStack itemStack(String id) {
        return new ItemStack(itemOf(id));
    }

    public static void stopAuction() {
        aActive = false;
        aEnded = false;
        offAt = 0;
        if (cfg.autoWithAuction) cfg.enabled = false;
    }

    public static String statusLine() {
        if (aActive) return "Running - highest: " + (aBidder == null ? "no bids yet" : aBidder + " " + Fmt.money(aBid));
        if (aEnded) return "Ended - " + (aBidder == null ? "no winner" : "winner " + aBidder + " " + Fmt.money(aBid));
        return "No auction running";
    }

    // ---------------------------------------------------------------- positions
    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static float clampScale(float v) {
        return Math.round(Math.max(0.5f, Math.min(2.5f, v)) * 100f) / 100f;
    }

    public static int toastW() { return Math.round(TOAST_W * cfg.toastScale); }
    public static int toastH() { return Math.round(TOAST_H * cfg.toastScale); }
    public static int aucW() { return Math.round(AUC_W * cfg.auctionScale); }
    public static int aucH() { return Math.round(AUC_H * cfg.auctionScale); }
    public static int queueW() { return Math.round(QUEUE_W * cfg.queueScale); }

    /** Height of the queue box (unscaled) for a queue of this size. */
    public static int queueBoxH(int size) {
        if (size == 0) return 38;
        int waiting = size - 1;
        int shown = Math.min(waiting, 4);
        return 52 + shown * 11 + (waiting > shown ? 11 : 0);
    }

    public static int[] queuePos(int w, int h) {
        return new int[]{
                clamp((int) (cfg.queueX * w), 0, Math.max(0, w - queueW())),
                clamp((int) (cfg.queueY * h), 0, Math.max(0, h - Math.round(108 * cfg.queueScale)))};
    }

    public static int[] toastPos(int w, int h) {
        return new int[]{
                clamp((int) (cfg.toastX * w), 0, Math.max(0, w - toastW())),
                clamp((int) (cfg.toastY * h), 0, Math.max(0, h - toastH()))};
    }

    public static int[] auctionPos(int w, int h) {
        return new int[]{
                clamp((int) (cfg.auctionX * w), 0, Math.max(0, w - aucW())),
                clamp((int) (cfg.auctionY * h), 0, Math.max(0, h - aucH()))};
    }

    // ---------------------------------------------------------------- particles
    private static final class P {
        float x, y, vx, vy, g, drag, age, life, size, delay, fuse;
        int color;
        boolean rocket;
    }

    private static final List<P> parts = new ArrayList<>();
    private static final Random R = new Random();
    private static float flash = 0;
    private static long lastNanos = 0;

    private static final int[] CONFETTI = {0xFF5555, 0x55FF55, 0x5599FF, 0xFFD700, 0xFF66CC, 0x55FFFF};
    private static final int[] FIRE = {0xFFFFAA, 0xFFD700, 0xFF8833, 0xFF4422};

    private static void spawn(Config.Effect e, int w, int h) {
        switch (e) {
            case CONFETTI -> confetti(w, h);
            case ROCKETS -> rockets(w, h);
            case EXPLOSION -> explosion(w, h);
            case EVERYTHING -> {
                confetti(w, h);
                rockets(w, h);
                explosion(w, h);
            }
            default -> {}
        }
    }

    private static void confetti(int w, int h) {
        for (int i = 0; i < 170; i++) {
            P p = new P();
            p.x = R.nextFloat() * w;
            p.y = -R.nextFloat() * h * 0.4f;
            p.vx = (R.nextFloat() - 0.5f) * 50f;
            p.vy = 40f + R.nextFloat() * 90f;
            p.g = 40f;
            p.life = 5f;
            p.size = 2f + R.nextFloat() * 3f;
            p.color = CONFETTI[R.nextInt(CONFETTI.length)];
            parts.add(p);
        }
    }

    private static void rockets(int w, int h) {
        for (int i = 0; i < 6; i++) {
            P p = new P();
            p.rocket = true;
            p.delay = i * 0.25f + R.nextFloat() * 0.2f;
            p.x = w * (0.15f + 0.7f * R.nextFloat());
            p.y = h;
            float burstY = h * (0.2f + 0.3f * R.nextFloat());
            p.fuse = 1.0f + R.nextFloat() * 0.4f;
            p.vy = -(h - burstY) / p.fuse;
            p.vx = (R.nextFloat() - 0.5f) * 20f;
            p.color = 0xFFD700;
            parts.add(p);
        }
    }

    private static void burst(List<P> out, float x, float y) {
        int base = CONFETTI[R.nextInt(CONFETTI.length)];
        for (int i = 0; i < 36; i++) {
            double ang = R.nextDouble() * Math.PI * 2;
            float sp = 40f + R.nextFloat() * 90f;
            P p = new P();
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * sp;
            p.vy = (float) Math.sin(ang) * sp;
            p.g = 60f;
            p.drag = 0.5f;
            p.life = 1.0f + R.nextFloat() * 0.6f;
            p.size = 2f;
            p.color = R.nextInt(3) == 0 ? 0xFFFFFF : base;
            out.add(p);
        }
    }

    private static void explosion(int w, int h) {
        flash = 0.35f;
        float cx = w / 2f, cy = h * 0.32f;
        for (int i = 0; i < 150; i++) {
            double ang = R.nextDouble() * Math.PI * 2;
            float sp = 30f + (float) Math.pow(R.nextFloat(), 0.6) * 280f;
            P p = new P();
            p.x = cx;
            p.y = cy;
            p.vx = (float) Math.cos(ang) * sp;
            p.vy = (float) Math.sin(ang) * sp;
            p.g = 80f;
            p.drag = 1.2f;
            p.life = 0.9f + R.nextFloat() * 0.8f;
            p.size = 2f + R.nextFloat() * 3f;
            p.color = FIRE[R.nextInt(FIRE.length)];
            parts.add(p);
        }
    }

    private static void update(float dt) {
        List<P> add = new ArrayList<>();
        for (Iterator<P> it = parts.iterator(); it.hasNext(); ) {
            P p = it.next();
            if (p.delay > 0) {
                p.delay -= dt;
                continue;
            }
            p.age += dt;
            if (p.rocket) {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                P tr = new P();
                tr.x = p.x;
                tr.y = p.y;
                tr.vy = 20f;
                tr.life = 0.35f;
                tr.size = 1.5f;
                tr.color = 0xFF8833;
                add.add(tr);
                if (p.age >= p.fuse) {
                    burst(add, p.x, p.y);
                    it.remove();
                }
            } else {
                float damp = Math.max(0f, 1f - p.drag * dt);
                p.vx *= damp;
                p.vy = p.vy * damp + p.g * dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                if (p.age >= p.life) it.remove();
            }
        }
        parts.addAll(add);
        if (parts.size() > 1500) parts.subList(0, parts.size() - 1500).clear();
        if (flash > 0) flash -= dt;
    }

    private static void drawParticles(DrawContext ctx, int w, int h) {
        if (flash > 0) {
            int a = (int) (flash / 0.35f * 90);
            if (a >= 4) ctx.fill(0, 0, w, h, (a << 24) | 0xFFFFFF);
        }
        for (P p : parts) {
            if (p.delay > 0) continue;
            if (p.rocket) {
                ctx.fill((int) p.x, (int) p.y, (int) p.x + 2, (int) p.y + 5, 0xFFFFE9A0);
                continue;
            }
            float remain = p.life - p.age;
            int a = (int) (255 * Math.max(0f, Math.min(1f, remain / 0.4f)));
            if (a < 4) continue;
            int s = Math.max(1, Math.round(p.size));
            ctx.fill((int) p.x, (int) p.y, (int) p.x + s, (int) p.y + s, (a << 24) | p.color);
        }
    }

    // ---------------------------------------------------------------- drawing helpers
    private static TextRenderer tr() {
        return MinecraftClient.getInstance().textRenderer;
    }

    // ---- player heads: tab list skin if available, else minotar.net (8x8 pixels), else default Steve/Alex
    private interface HeadDrawer {
        void draw(DrawContext ctx, int x, int y, int size);
    }

    private static final Map<String, HeadDrawer> heads = new ConcurrentHashMap<>();
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();

    public static String sampleName() {
        return MinecraftClient.getInstance().getSession().getUsername();
    }

    public static void resolveHead(String name) {
        if (name == null || name.isEmpty() || heads.containsKey(name) || !loading.add(name)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(name);
            if (e != null) {
                heads.put(name, (c, x, y, s) -> PlayerSkinDrawer.draw(c, e.getSkinTextures(), x, y, s));
                return;
            }
        }
        CompletableFuture.runAsync(() -> {
            try {
                String url = "https://minotar.net/helm/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/32.png";
                URLConnection con = java.net.URI.create(url).toURL().openConnection();
                con.setConnectTimeout(4000);
                con.setReadTimeout(4000);
                con.setRequestProperty("User-Agent", "DonutAlerts");
                BufferedImage img;
                try (InputStream in = con.getInputStream()) {
                    img = ImageIO.read(in);
                }
                if (img == null || img.getWidth() < 8 || img.getHeight() < 8) return;
                int[] px = new int[64];
                int iw = img.getWidth(), ih = img.getHeight();
                for (int py = 0; py < 8; py++) {
                    for (int pxx = 0; pxx < 8; pxx++) {
                        int sx = pxx * iw / 8 + iw / 16;
                        int sy = py * ih / 8 + ih / 16;
                        px[py * 8 + pxx] = 0xFF000000 | (img.getRGB(sx, sy) & 0xFFFFFF);
                    }
                }
                heads.put(name, (c, x, y, s) -> {
                    for (int j = 0; j < 8; j++) {
                        for (int i = 0; i < 8; i++) {
                            int x1 = x + Math.round(i * s / 8f), x2 = x + Math.round((i + 1) * s / 8f);
                            int y1 = y + Math.round(j * s / 8f), y2 = y + Math.round((j + 1) * s / 8f);
                            c.fill(x1, y1, x2, y2, px[j * 8 + i]);
                        }
                    }
                });
            } catch (Throwable ignored) {
                // keep the default head
            }
        });
    }

    public static void drawHead(DrawContext ctx, PlayerListEntry ignored, String name, int x, int y, int size) {
        if (name == null || name.isEmpty() || name.equals("?")) {
            ctx.fill(x, y, x + size, y + size, 0xFF5A5A70);
            ctx.drawText(tr(), "?", x + size / 2 - 3, y + size / 2 - 4, 0xFFFFFFFF, true);
            return;
        }
        HeadDrawer d = heads.get(name);
        if (d == null) {
            resolveHead(name);
            UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            PlayerSkinDrawer.draw(ctx, DefaultSkinHelper.getSkinTextures(u), x, y, size);
        } else {
            d.draw(ctx, x, y, size);
        }
    }

    private static void drawScaledCentered(DrawContext ctx, String text, float cx, float y, float scale, int argb) {
        TextRenderer t = tr();
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(cx, y, 0f);
        m.scale(scale, scale, 1f);
        ctx.drawText(t, text, -t.getWidth(text) / 2, 0, argb, true);
        m.pop();
    }

    public static void drawToast(DrawContext ctx, int x, int y, String name, double amount, int color, PlayerListEntry skin) {
        TextRenderer t = tr();
        ctx.fill(x, y, x + TOAST_W, y + TOAST_H, 0xD00E1420);
        ctx.drawBorder(x, y, TOAST_W, TOAST_H, 0xFF000000 | color);
        drawHead(ctx, skin, name, x + 5, y + 5, 28);
        ctx.drawText(t, name + " paid you", x + 40, y + 7, 0xFFB8B8C8, false);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x + 40, y + 19, 0f);
        m.scale(1.6f, 1.6f, 1f);
        ctx.drawText(t, Fmt.money(amount), 0, 0, 0xFF000000 | color, true);
        m.pop();
    }

    public static void drawQueueBox(DrawContext ctx, int x, int y, List<QEntry> list) {
        TextRenderer t = tr();
        int h = queueBoxH(list.size());
        int waiting = Math.max(0, list.size() - 1);
        int shown = Math.min(waiting, 4);
        ctx.fill(x, y, x + QUEUE_W, y + h, 0xD00E1420);
        ctx.drawBorder(x, y, QUEUE_W, h, 0xFF5599FF);
        String title = "Gamble Queue" + (waiting > 0 ? " (+" + waiting + " waiting)" : "");
        ctx.drawText(t, title, x + 8, y + 6, 0xFF5599FF, true);
        if (list.isEmpty()) {
            ctx.drawText(t, "Empty - waiting for payments", x + 8, y + 22, 0xFFAAAAAA, false);
            return;
        }
        QEntry first = list.get(0);
        drawHead(ctx, null, first.name, x + 8, y + 18, 28);
        ctx.drawText(t, "Turn: " + first.name, x + 42, y + 21, 0xFFFFFFFF, true);
        ctx.drawText(t, "Paid: " + Fmt.money(first.amount), x + 42, y + 33, 0xFF55FF55, true);
        int yy = y + 50;
        for (int i = 1; i <= shown; i++) {
            QEntry e = list.get(i);
            ctx.drawText(t, (i + 1) + ". " + e.name, x + 8, yy, 0xFF9DB8FF, false);
            String m = Fmt.money(e.amount);
            ctx.drawText(t, m, x + QUEUE_W - 8 - t.getWidth(m), yy, 0xFF55FF55, false);
            yy += 11;
        }
        if (waiting > shown) ctx.drawText(t, "+" + (waiting - shown) + " more", x + 8, yy, 0xFF888888, false);
    }

    private static void renderQueue(DrawContext ctx, int w, int h) {
        if (!cfg.queueEnabled) return;
        int[] pos = queuePos(w, h);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(pos[0], pos[1], 0f);
        m.scale(cfg.queueScale, cfg.queueScale, 1f);
        try {
            drawQueueBox(ctx, 0, 0, queue);
        } finally {
            m.pop();
        }
    }

    private static String fmtTime(long ms) {
        long s = (long) Math.ceil(ms / 1000.0);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    public static void drawAuctionBox(DrawContext ctx, int x, int y, boolean active, long msLeft, double min,
                                      int qty, String item, String itemId, String bidder, double bid, PlayerListEntry skin) {
        TextRenderer t = tr();
        ctx.fill(x, y, x + AUC_W, y + AUC_H, 0xD00E1420);
        ctx.drawBorder(x, y, AUC_W, AUC_H, 0xFFFFAA00);
        String title = active ? "Bidding - " + fmtTime(msLeft) + " left" : "Bidding Ended!";
        ctx.drawText(t, title, x + 8, y + 6, 0xFFFFAA00, true);
        if (active) {
            String m = "Min " + Fmt.money(min);
            ctx.drawText(t, m, x + AUC_W - 8 - t.getWidth(m), y + 6, 0xFFAAAAAA, false);
        } else if (msLeft > 0) {
            String m = "Late bids " + ((msLeft + 999) / 1000) + "s";
            ctx.drawText(t, m, x + AUC_W - 8 - t.getWidth(m), y + 6, 0xFFAAAAAA, false);
        }
        drawHead(ctx, skin, bidder == null ? "?" : bidder, x + 8, y + 22, 32);
        if (bidder == null) {
            ctx.drawText(t, active ? "No bids yet" : "No winner", x + 46, y + 34, 0xFFAAAAAA, false);
        } else if (active) {
            ctx.drawText(t, bidder, x + 46, y + 26, 0xFF5599FF, false);
            ctx.drawText(t, Fmt.money(bid), x + 46, y + 38, 0xFF55FF55, true);
        } else {
            ctx.drawText(t, "Winner:", x + 46, y + 24, 0xFFAAAAAA, false);
            ctx.drawText(t, bidder + " (" + Fmt.money(bid) + ")", x + 46, y + 36, 0xFF5599FF, false);
        }
        String it = qty + "x " + item;
        int iw = t.getWidth(it);
        ctx.drawText(t, it, x + AUC_W - 8 - iw, y + 22, 0xFFFFFFFF, true);
    }

    // ---------------------------------------------------------------- main render
    public static void renderAll(DrawContext ctx) {
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        long nn = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.05f, (nn - lastNanos) / 1e9f);
        lastNanos = nn;

        if (offAt > 0 && System.currentTimeMillis() >= offAt) {
            offAt = 0;
            if (cfg.autoWithAuction) cfg.enabled = false;
        }

        for (Config.Effect e : pending) spawn(e, w, h);
        pending.clear();
        update(dt);
        drawParticles(ctx, w, h);
        renderBanner(ctx, w, h);
        renderToasts(ctx, w, h);
        renderAuction(ctx, w, h);
        renderQueue(ctx, w, h);
    }

    private static void drawBanner(DrawContext ctx, int w, int h, String name, double amount, int color,
                                   PlayerListEntry skin, float pop, int a) {
        float bs = cfg.bannerScale;
        float cx = w / 2f, y = h * 0.2f;
        int hs = Math.round(32 * bs);
        drawHead(ctx, skin, name, (int) cx - hs / 2, (int) y, hs);
        drawScaledCentered(ctx, name + " paid you", cx, y + 38 * bs, 1.6f * pop * bs, (a << 24) | 0xFFFFFF);
        drawScaledCentered(ctx, Fmt.money(amount), cx, y + 54 * bs, 4.5f * pop * bs, (a << 24) | color);
    }

    public static void drawBannerSample(DrawContext ctx, int w, int h, PlayerListEntry skin) {
        drawBanner(ctx, w, h, sampleName(), 10_000_000, 0xFFAA33, skin, 1f, 255);
    }

    private static void renderBanner(DrawContext ctx, int w, int h) {
        if (banner == null) return;
        float t = (System.currentTimeMillis() - banner.start) / 1000f;
        float total = 4.5f;
        if (t > total) {
            banner = null;
            return;
        }
        float fade = Math.min(1f, (total - t) / 0.7f);
        float pop = t < 0.25f ? 0.5f + 0.5f * (t / 0.25f) : 1f;
        int a = Math.max(4, (int) (255 * fade));
        drawBanner(ctx, w, h, banner.name, banner.amount, banner.tier.color, banner.skin, pop, a);
    }

    private static void renderToasts(DrawContext ctx, int w, int h) {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> now - t.start > 5000);
        if (toasts.isEmpty()) return;
        int[] pos = toastPos(w, h);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(pos[0], pos[1], 0f);
        m.scale(cfg.toastScale, cfg.toastScale, 1f);
        try {
            int i = 0;
            for (Toast t : toasts) {
                drawToast(ctx, 0, i * (TOAST_H + 4), t.name, t.amount, t.tier.color, t.skin);
                i++;
            }
        } finally {
            m.pop();
        }
    }

    private static void renderAuction(DrawContext ctx, int w, int h) {
        if (!aActive && !aEnded) return;
        long now = System.currentTimeMillis();
        if (aActive && now >= aEnd) {
            aActive = false;
            aEnded = true;
            aEndedUntil = now + 15000;
            if (cfg.autoWithAuction) offAt = aEnd + cfg.lateBidSeconds * 1000L;
            if (aBidder != null) playSound("Level Up");
        }
        if (aEnded && now > aEndedUntil) {
            aEnded = false;
            return;
        }
        int[] pos = auctionPos(w, h);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(pos[0], pos[1], 0f);
        m.scale(cfg.auctionScale, cfg.auctionScale, 1f);
        try {
            drawAuctionBox(ctx, 0, 0, aActive,
                    aActive ? Math.max(0, aEnd - now) : Math.max(0, aEnd + cfg.lateBidSeconds * 1000L - now),
                    aMin, aQty, aItem, aItemId, aBidder, aBid, aSkin);
        } finally {
            m.pop();
        }
    }
}
