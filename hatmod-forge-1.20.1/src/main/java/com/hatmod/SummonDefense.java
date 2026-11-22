package com.hatmod;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

final class SummonDefense {

    private SummonDefense() {
    }

    static boolean healthWriteBlocked(boolean selfRemoval, float incoming, float current) {
        return HatSettings.forceBreak() && !selfRemoval && incoming < current;
    }

    static boolean friendlyStrike(DamageSource source, LivingEntity owner) {
        Entity attacker = source.getEntity();
        if (attacker instanceof net.minecraft.world.entity.player.Player) {
            return true;
        }
        if (attacker instanceof HatSummon || attacker instanceof Teto) {
            return true;
        }
        return owner != null && attacker instanceof LivingEntity living
                && HatAbilities.isFriendlyTo(owner, living);
    }
}
