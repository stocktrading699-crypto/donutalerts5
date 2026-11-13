package com.dasa.donutalerts;

import com.dasa.donutalerts.screen.AuctionScreen;
import com.dasa.donutalerts.screen.BaseScreen;
import com.dasa.donutalerts.screen.HudEditScreen;
import com.dasa.donutalerts.screen.MainScreen;
import com.dasa.donutalerts.screen.QueueScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

public class DonutAlertsClient implements ClientModInitializer {
    // Commands run while the chat box is open, so the screen is opened on the next tick.
    private static Supplier<Screen> pendingScreen;

    private static String nowText() {
        if (Alerts.queue.isEmpty()) return "Queue is empty";
        Alerts.QEntry e = Alerts.queue.get(0);
        return "Now: " + e.name + " (" + Fmt.money(e.amount) + ")";
    }

    @Override
    public void onInitializeClient() {
        // On launch: alerts are ON (or OFF until an auction starts, if "follow auction" is enabled)
        Alerts.cfg.enabled = !Alerts.cfg.autoWithAuction;

        KeyBinding menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutalerts.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.donutalerts"));
        KeyBinding moveKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutalerts.move", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.donutalerts"));
        KeyBinding auctionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutalerts.auction", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, "category.donutalerts"));
        // queue keys start unbound - set them in Options > Controls if you want them
        KeyBinding queueMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutalerts.queue_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.donutalerts"));
        KeyBinding queueNextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutalerts.queue_next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.donutalerts"));

        // "X paid you $1M" arrives as a server (game) message
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> Alerts.onMessage(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> Alerts.onMessage(message));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /donutalerts                -> main menu
            // /donutalerts move           -> move / resize boxes
            // /donutalerts auction        -> auction menu
            // /donutalerts queue          -> gamble queue menu (on/off + settings)
            // /donutalerts next           -> finish the current person in the queue
            // /donutalerts clear          -> empty the queue
            dispatcher.register(ClientCommandManager.literal("donutalerts")
                    .executes(c -> {
                        pendingScreen = MainScreen::new;
                        return 1;
                    })
                    .then(ClientCommandManager.literal("move").executes(c -> {
                        pendingScreen = HudEditScreen::new;
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("auction").executes(c -> {
                        pendingScreen = AuctionScreen::new;
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("queue").executes(c -> {
                        pendingScreen = QueueScreen::new;
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("next").executes(c -> {
                        Alerts.queueNext();
                        c.getSource().sendFeedback(Text.literal(nowText()));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("clear").executes(c -> {
                        Alerts.queueClear();
                        c.getSource().sendFeedback(Text.literal("Queue cleared"));
                        return 1;
                    })));

            // /bidding        -> auction menu (auto-fills the item you're holding)
            // /bidding stop   -> stop / clear the auction
            dispatcher.register(ClientCommandManager.literal("bidding")
                    .executes(c -> {
                        pendingScreen = AuctionScreen::new;
                        return 1;
                    })
                    .then(ClientCommandManager.literal("stop").executes(c -> {
                        Alerts.stopAuction();
                        c.getSource().sendFeedback(Text.literal("Auction stopped"));
                        return 1;
                    })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) client.setScreen(new MainScreen());
            while (moveKey.wasPressed()) client.setScreen(new HudEditScreen());
            while (auctionKey.wasPressed()) client.setScreen(new AuctionScreen());
            while (queueMenuKey.wasPressed()) client.setScreen(new QueueScreen());
            while (queueNextKey.wasPressed()) Alerts.queueNext();
            if (pendingScreen != null) {
                Supplier<Screen> s = pendingScreen;
                pendingScreen = null;
                client.setScreen(s.get());
            }
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen instanceof BaseScreen) return; // the screen draws it itself
            if (mc.options.hudHidden) return;
            try {
                Alerts.renderAll(ctx);
            } catch (Throwable t) {
                System.err.println("[DonutAlerts] render error: " + t);
            }
        });
    }
}
