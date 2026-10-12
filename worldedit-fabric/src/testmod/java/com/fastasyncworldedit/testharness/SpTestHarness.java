package com.fastasyncworldedit.testharness;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleplayer end-to-end test harness for the FAWE Fabric port.
 *
 * <p>Runs entirely in-process: once the player is in a world it sends WorldEdit
 * commands through the client's own connection, verifies the resulting block
 * states from the client level, writes {@code sp-test-results.json} into the
 * run directory and then stops the client. No OS input involved.</p>
 */
public class SpTestHarness implements ClientModInitializer {

    private static final BlockPos CHECK_POS = new BlockPos(4, -48, 4);
    private static final int POLL_LIMIT_TICKS = 600;

    private int ticksInWorld = 0;
    private int step = 0;
    private int pollTicks = 0;
    private BlockState initialState;
    private final List<String> results = new ArrayList<>();
    private boolean done = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[SPHARNESS] test harness loaded");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (done) {
            return;
        }
        // The harness runs unattended: keep the integrated server ticking even
        // when the window is unfocused, or async edits never get applied.
        if (mc.options.pauseOnLostFocus) {
            mc.options.pauseOnLostFocus = false;
            System.out.println("[SPHARNESS] disabled pauseOnLostFocus");
        }
        // Minecraft 26.3 moved the current screen onto Gui (mc.gui.screen()/setScreen()).
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
            mc.gui.setScreen(null);
        }
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            ticksInWorld = 0;
            return;
        }
        ticksInWorld++;
        // Give the integrated server time to settle before issuing commands.
        if (ticksInWorld < 100) {
            return;
        }

        switch (step) {
            case 0 -> {
                initialState = mc.level.getBlockState(CHECK_POS);
                System.out.println("[SPHARNESS] initial block at " + CHECK_POS + ": " + initialState);
                mc.getConnection().sendCommand("/pos1 2,-50,2");
                step = 1;
            }
            case 1 -> {
                mc.getConnection().sendCommand("/pos2 6,-46,6");
                step = 2;
            }
            case 2 -> {
                mc.getConnection().sendCommand("/set minecraft:diamond_block");
                pollTicks = 0;
                step = 3;
            }
            case 3 -> {
                if (mc.level.getBlockState(CHECK_POS).is(Blocks.DIAMOND_BLOCK)) {
                    record(true, "//set diamond_block placed blocks (singleplayer)");
                    step = 4;
                } else if (++pollTicks > POLL_LIMIT_TICKS) {
                    record(false, "//set diamond_block placed blocks (singleplayer) — block is "
                            + mc.level.getBlockState(CHECK_POS));
                    step = 4;
                }
            }
            case 4 -> {
                mc.getConnection().sendCommand("/undo");
                pollTicks = 0;
                step = 5;
            }
            case 5 -> {
                if (mc.level.getBlockState(CHECK_POS).equals(initialState)) {
                    record(true, "//undo restored original block (singleplayer)");
                    step = 6;
                } else if (++pollTicks > POLL_LIMIT_TICKS) {
                    record(false, "//undo restored original block (singleplayer) — block is "
                            + mc.level.getBlockState(CHECK_POS) + ", expected " + initialState);
                    step = 6;
                }
            }
            case 6 -> {
                mc.getConnection().sendCommand("/redo");
                pollTicks = 0;
                step = 7;
            }
            case 7 -> {
                if (mc.level.getBlockState(CHECK_POS).is(Blocks.DIAMOND_BLOCK)) {
                    record(true, "//redo re-applied edit (singleplayer)");
                    step = 8;
                } else if (++pollTicks > POLL_LIMIT_TICKS) {
                    record(false, "//redo re-applied edit (singleplayer) — block is "
                            + mc.level.getBlockState(CHECK_POS));
                    step = 8;
                }
            }
            case 8 -> finish(mc);
            default -> { }
        }
    }

    private void record(boolean ok, String name) {
        String line = (ok ? "PASS " : "FAIL ") + name;
        results.add(line);
        System.out.println("[SPHARNESS] " + line);
    }

    private void finish(Minecraft mc) {
        done = true;
        long failures = results.stream().filter(r -> r.startsWith("FAIL")).count();
        System.out.println("[SPHARNESS] ===== RESULTS =====");
        results.forEach(r -> System.out.println("[SPHARNESS] " + r));
        System.out.println("[SPHARNESS] " + (results.size() - failures) + "/" + results.size() + " passed");
        try {
            StringBuilder json = new StringBuilder("[\n");
            for (int i = 0; i < results.size(); i++) {
                json.append("  \"").append(results.get(i)).append('"');
                json.append(i < results.size() - 1 ? ",\n" : "\n");
            }
            json.append("]\n");
            Files.writeString(Path.of("sp-test-results.json"), json.toString());
        } catch (IOException e) {
            System.out.println("[SPHARNESS] failed to write results: " + e);
        }
        mc.execute(mc::stop);
    }

}
