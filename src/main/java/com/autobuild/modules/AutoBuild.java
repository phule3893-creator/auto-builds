package com.autobuild.modules;

import com.autobuild.AutoBuildAddon;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoBuild extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement Rules");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("Maximum distance from player eyes to target block position.")
            .defaultValue(4.5)
            .min(1.0)
            .sliderMax(6.0)
            .build());

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
            .name("delay-ticks")
            .description("Tick delay between placement sequences.")
            .defaultValue(1)
            .min(0)
            .sliderMax(20)
            .build());

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("blocks-per-tick")
            .description("Maximum blocks to attempt placing per execution tick.")
            .defaultValue(2)
            .min(1)
            .sliderMax(10)
            .build());

    private final Setting<Boolean> rotate = sgPlacement.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Sends packet rotations facing the placed block face.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> checkSupport = sgPlacement.add(new BoolSetting.Builder()
            .name("check-support")
            .description("Ensures target block has at least one adjacent solid surface.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> airOnly = sgPlacement.add(new BoolSetting.Builder()
            .name("air-only")
            .description("Only places blocks into replaceable/air blocks.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> switchBack = sgPlacement.add(new BoolSetting.Builder()
            .name("switch-back")
            .description("Reverts hotbar slot to previously held item after placing.")
            .defaultValue(true)
            .build());

    private int tickTimer = 0;

    public AutoBuild() {
        super(AutoBuildAddon.CATEGORY, "auto-build", "High-performance automated building engine.");
    }

    @Override
    public void onActivate() {
        tickTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

        FindItemResult blockItem = InvUtils.findInHotbar(itemStack -> 
            itemStack.getItem() instanceof BlockItem && !isTileEntityBlock(((BlockItem) itemStack.getItem()).getBlock())
        );

        if (!blockItem.found()) return;

        List<BlockPos> targetPositions = getValidTargetPositions();
        if (targetPositions.isEmpty()) return;

        int originalSlot = mc.player.getInventory().selectedSlot;
        int placedCount = 0;

        for (BlockPos targetPos : targetPositions) {
            if (placedCount >= blocksPerTick.get()) break;

            boolean success;
            if (rotate.get()) {
                Vec3d targetVec = Vec3d.ofCenter(targetPos);
                double yaw = Rotations.getYaw(targetVec);
                double pitch = Rotations.getPitch(targetVec);

                Rotations.rotate(yaw, pitch, () -> executePlacement(targetPos, blockItem, originalSlot));
                success = true;
            } else {
                success = executePlacement(targetPos, blockItem, originalSlot);
            }

            if (success) {
                placedCount++;
            }
        }

        if (placedCount > 0) {
            tickTimer = delayTicks.get();
        }
    }

    private boolean executePlacement(BlockPos pos, FindItemResult itemResult, int originalSlot) {
        InvUtils.swap(itemResult.slot(), false);

        boolean placed = BlockUtils.place(
            pos, 
            Hand.MAIN_HAND, 
            itemResult.slot(), 
            rotate.get(), 
            50, 
            true, 
            true, 
            false
        );

        if (switchBack.get() && mc.player.getInventory().selectedSlot != originalSlot) {
            InvUtils.swap(originalSlot, false);
        }

        return placed;
    }

    private List<BlockPos> getValidTargetPositions() {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos playerPos = mc.player.getBlockPos();
        int reachRadius = (int) Math.ceil(range.get());
        double maxRangeSq = range.get() * range.get();
        Vec3d eyePos = mc.player.getEyePos();

        for (int x = -reachRadius; x <= reachRadius; x++) {
            for (int y = -reachRadius; y <= reachRadius; y++) {
                for (int z = -reachRadius; z <= reachRadius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);

                    if (eyePos.squaredDistanceTo(Vec3d.ofCenter(pos)) > maxRangeSq) continue;
                    if (airOnly.get() && !mc.world.getBlockState(pos).isReplaceable()) continue;
                    if (checkSupport.get() && !hasAdjacentNeighbor(pos)) continue;

                    candidates.add(pos);
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> eyePos.squaredDistanceTo(Vec3d.ofCenter(p))));
        return candidates;
    }

    private boolean hasAdjacentNeighbor(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.offset(side);
            if (!mc.world.getBlockState(neighbor).isAir() && !mc.world.getBlockState(neighbor).isReplaceable()) {
                return true;
            }
        }
        return false;
    }

    private boolean isTileEntityBlock(Block block) {
        return block == Blocks.CHEST 
            || block == Blocks.TRAPPED_CHEST 
            || block == Blocks.ENDER_CHEST 
            || block == Blocks.ANVIL 
            || block == Blocks.CRAFTING_TABLE 
            || block == Blocks.FURNACE 
            || block == Blocks.HOPPER;
    }
}
