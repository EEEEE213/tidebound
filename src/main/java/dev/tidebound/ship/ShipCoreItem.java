package dev.tidebound.ship;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Use on open water: builds the starter raft there with the core on it, already floating.
 * Place on a block: just places the core, so you can build your own hull around it and launch it from the core.
 */
public class ShipCoreItem extends BlockItem {
    public ShipCoreItem(Block block, Item.Properties props) {
        super(block, props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack);
        BlockPos water = hit.getBlockPos();
        if (!level.getFluidState(water).is(FluidTags.WATER) || !level.getBlockState(water.above()).isAir()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level instanceof ServerLevel server) {
            Direction facing = player.getDirection();
            List<Component> errors = new ArrayList<>();
            if (ShipBuilder.launchRaft(server, water.above(), facing, errors) != null) {
                level.playSound(null, water, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1, 0.8f);
                level.playSound(null, water, SoundEvents.BOAT_PADDLE_WATER, SoundSource.BLOCKS, 1, 1);
                player.displayClientMessage(Component.translatable("message.tidebound.raft_launched"), true);
                stack.consume(1, player);
            } else {
                player.displayClientMessage(errors.isEmpty() ? Component.literal("?") : errors.get(0), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Looking at water over a reachable seabed still launches the raft rather than placing the core underwater. */
    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player != null) {
            BlockHitResult hit = getPlayerPOVHitResult(ctx.getLevel(), player, ClipContext.Fluid.SOURCE_ONLY);
            if (hit.getType() == HitResult.Type.BLOCK && ctx.getLevel().getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
                return use(ctx.getLevel(), player, ctx.getHand()).getResult();
            }
        }
        return super.useOn(ctx);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tidebound.ship_core.tip1").withStyle(net.minecraft.ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.tidebound.ship_core.tip2").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
