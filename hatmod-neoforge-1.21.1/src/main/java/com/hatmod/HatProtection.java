package com.hatmod;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * 戴帽子时禁止攻击「被动生物」与「未被激怒的中立生物」。
 * 敌意生物（Enemy）以及已被激怒 / 正在锁定玩家的中立生物照常可打。
 */
@EventBusSubscriber(modid = HatMod.MOD_ID)
public final class HatProtection {

    private HatProtection() {
    }

    /** 易伤：受到的所有伤害 ×1.5（声波打出来的标记）。 */
    public static final float VULNERABLE_MULTIPLIER = 1.5F;

    @SubscribeEvent
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        // 「脑蚀」连击奖励：黑帽同款闪避 —— 这一击完全落空，人瞬移到旁边
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && HatAbilities.hatStack(player).is(HatItems.BRAIN_HAT.get())
                && BrainrotQte.consumeDodge(player)) {
            event.setCanceled(true);
            dodgeFlash(player);
            return;
        }
        if (event.getEntity().hasEffect(HatEffects.VULNERABLE)) {
            event.setAmount(event.getAmount() * VULNERABLE_MULTIPLIER);
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        if (!HatAbilities.isWearingHat(event.getEntity())) {
            return;
        }
        if (isProtected(target)) {
            event.setCanceled(true);
        }
    }

    /**
     * 闪避成功的反馈：原地炸开两圈紫光、人瞬移到旁边、落点再闪一下。
     *
     * <p>粒子只用原版就有的：{@code WITCH} / {@code PORTAL} 画紫环，{@code SQUID_INK} 当「黑影」，
     * 落点用 {@code END_ROD} 打一记白亮。声音三层叠起来 —— 末影传送（瞬移）、
     * 挥空的风（躲开）、音符盒「叮」（和节拍同一个语言）。
     */
    private static void dodgeFlash(net.minecraft.server.level.ServerPlayer player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        // 老位置先记下来：瞬移之后就取不到了
        double fromX = player.getX();
        double fromY = player.getY();
        double fromZ = player.getZ();
        double dx = (player.getRandom().nextDouble() - 0.5D) * 8.0D;
        double dz = (player.getRandom().nextDouble() - 0.5D) * 8.0D;
        player.randomTeleport(fromX + dx, fromY, fromZ + dz, true);

        // 原地：两圈贴地的紫环 + 齐胸一圈传送门 + 黑影 + 倒转的传送门
        ring(level, net.minecraft.core.particles.ParticleTypes.WITCH, fromX, fromY + 0.3D, fromZ,
                0.6D, 18);
        ring(level, net.minecraft.core.particles.ParticleTypes.WITCH, fromX, fromY + 0.3D, fromZ,
                1.1D, 26);
        ring(level, net.minecraft.core.particles.ParticleTypes.PORTAL, fromX, fromY + 1.2D, fromZ,
                0.85D, 20);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK,
                fromX, fromY + 0.8D, fromZ, 12, 0.35D, 0.5D, 0.35D, 0.02D);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                fromX, fromY + 0.6D, fromZ, 16, 0.3D, 0.6D, 0.3D, 0.04D);

        // 落点：一记白亮 + 一圈收束的紫烟
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                player.getX(), player.getY() + 0.9D, player.getZ(),
                10, 0.25D, 0.35D, 0.25D, 0.04D);
        ring(level, net.minecraft.core.particles.ParticleTypes.PORTAL,
                player.getX(), player.getY() + 0.25D, player.getZ(), 0.75D, 20);

        // 声音：末影传送 + 挥空的风 + 一声高音「叮」
        level.playSound(null, fromX, fromY, fromZ,
                net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 1.5F);
        level.playSound(null, fromX, fromY, fromZ,
                net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.7F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 1.9F);

        // 屏幕：只发给挨打的本人 —— 整屏紫边 + 大字「闪避」
        HatNetwork.sendQteDodge(player);
    }

    /** 在 {@code (x, y, z)} 处画一个半径 {@code radius} 的粒子圆环（{@code count} 个均分）。 */
    private static void ring(net.minecraft.server.level.ServerLevel level,
                             net.minecraft.core.particles.SimpleParticleType type,
                             double x, double y, double z, double radius, int count) {
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0D * i / count;
            level.sendParticles(type,
                    x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 被动生物、或未被激怒的中立生物 -> true（禁止攻击）。 */
    public static boolean isProtected(LivingEntity target) {
        if (!(target instanceof Mob mob)) {
            return false;
        }
        if (mob instanceof Enemy) {
            return false;
        }
        if (mob instanceof NeutralMob neutral && neutral.getRemainingPersistentAngerTime() > 0) {
            return false;
        }
        if (mob.getTarget() != null) {
            return false;
        }
        return true;
    }
}
