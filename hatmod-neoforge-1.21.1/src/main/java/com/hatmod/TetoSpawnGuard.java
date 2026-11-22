package com.hatmod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 刷怪蛋 / 指令召唤「后室 Teto」的守门员。
 *
 * <p>规矩很简单：**世界里已经有一只 Teto 时，就再也放不出第二只**。
 * 不管上一只是帽子召唤的、光柱分身，还是上一次用蛋放的 —— 都算数。
 *
 * <ul>
 *   <li>刷怪蛋：右键事件直接取消，蛋不会被消耗（等于 {@code use()} 返回 false）；
 *       **对着刷怪笼用也一样取消** —— 笼子会无限刷，不能给她开口子；</li>
 *   <li>发射器：行为整个换掉，红石也放不出来；</li>
 *   <li>指令 {@code /summon}：实体入世界那一刻取消，等于没生出来。</li>
 * </ul>
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class TetoSpawnGuard {

    private TetoSpawnGuard() {
    }

    /**
     * 世界里是不是已经有一只 Teto（{@code skip} 不计）。
     *
     * <p>**跨维度**数：以前只看当前这一层，于是在下界 / 末地再塞一颗蛋就能多出一只 ——
     * 「世界里只有一只」按维度破了口子。现在从服务器把每一层都过一遍。
     */
    static boolean anyTeto(Level level, Entity skip) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return false;
        }
        for (ServerLevel other : server.getAllLevels()) {
            for (Entity entity : other.getAllEntities()) {
                if (TRANSFERRING.contains(entity.getUUID())) {
                    continue;           // 正在搬家的那一只：旧身体还没删掉，不算「第二只」
                }
                if (entity instanceof Teto teto && entity != skip && !teto.isRemoved()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 正在跨维度搬家的 Teto（新身体进世界那一刻，旧身体还留在原来那层）。 */
    private static final java.util.Set<java.util.UUID> TRANSFERRING = new java.util.HashSet<>();

    static void beginDimensionTransfer(java.util.UUID id) {
        TRANSFERRING.add(id);
    }

    static void endDimensionTransfer(java.util.UUID id) {
        TRANSFERRING.remove(id);
    }

    /**
     * 发射器里的刷怪蛋：**一颗都放不出来**。
     *
     * <p>右键那两条路（对空 / 对方块）本来就有拦截，可发射器走的是
     * {@code DispenseItemBehavior}，完全绕开 {@code PlayerInteractEvent}。
     * 现在把蛋的行为换成「空响一声、蛋留在发射器里」—— 红石再怎么跑也生不出她。
     */
    static void blockDispenser() {
        DispenserBlock.registerBehavior(HatItems.TETO_SPAWN_EGG.get(), (source, stack) -> {
            // 1.21 的 BlockSource 是 record：level() / pos() 而不是 getLevel() / getPos()
            source.level().playSound(null, source.pos(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 1.0F, 1.2F);
            return stack;
        });
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (blocks(event.getItemStack(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // 刷怪笼里**不许装 Teto 蛋**：笼子会一直刷，等于绕过「世界里只有一只」这条规矩。
        // 所以不管当前有没有 Teto，对着刷怪笼用蛋这一下都直接取消（蛋也不消耗）。
        if (!event.getItemStack().isEmpty()
                && event.getItemStack().is(HatItems.TETO_SPAWN_EGG.get())
                && event.getLevel().getBlockState(event.getPos()).is(Blocks.SPAWNER)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (blocks(event.getItemStack(), event.getLevel())) {
            event.setCanceled(true);
        }
    }

    /** 指令 / 其他路径塞进来的散养 Teto：世界里已经有一只就拦下。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Teto teto && !teto.isSummoned()
                && anyTeto(event.getLevel(), teto)) {
            event.setCanceled(true);
        }
    }

    private static boolean blocks(ItemStack stack, Level level) {
        return !stack.isEmpty() && stack.is(HatItems.TETO_SPAWN_EGG.get()) && anyTeto(level, null);
    }
}
