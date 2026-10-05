package com.hatmod;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 戴帽子时禁止攻击「被动生物」与「未被激怒的中立生物」。
 * 敌意生物（Enemy）以及已被激怒 / 正在锁定玩家的中立生物照常可打。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HatProtection {

    private HatProtection() {
    }

    public static void register() {
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
