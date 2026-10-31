package com.hatmod;

import net.minecraft.advancements.Advancement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 成就的发放口。
 *
 * <p>所有条件都由模组自己判（判完直接调 {@link #grant}），成就本身用原版的
 * {@code minecraft:impossible} 触发器挂着（JSON 见 {@code data/hatmod/advancements/*.json}）。
 * 这样不用注册自定义 criterion —— 两个加载器的 criterion 注册 API 不一样，而这种写法
 * 在 1.20.1 / 1.21.1 上完全一致。
 */
final class HatAchievements {

    /** 黑帽：一轮蓄力里闪避三次。 */
    static final String SHADE_STEP = "shade_step";
    /** 白帽：一次照射里用光柱击杀五个。 */
    static final String LIGHT_STEP = "light_step";
    /** 红帽：蓄力全程一次没被打中。 */
    static final String IMMOVABLE = "immovable";
    /** 全帽：黑 / 白 / 红三条路线都走过。 */
    static final String TRINITY = "trinity";
    /** 脑蚀：整首曲子全部 PERFECT。 */
    static final String RHYTHM_MASTER = "rhythm_master";

    /** 「三色归一」：每个玩家已经走过哪些路线。 */
    private static final Map<UUID, Set<HatType>> ROUTES = new HashMap<>();

    private HatAchievements() {
    }

    /**
     * 发成就。戴着帽子的未必是玩家（史莱姆、Teto 也可能），那种情况什么也不做。
     */
    static void grant(LivingEntity wearer, String id) {
        if (!(wearer instanceof ServerPlayer player)) {
            return;
        }
        Advancement advancement = player.serverLevel().getServer().getAdvancements()
                .getAdvancement(HatMod.id(id));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "impossible");
        }
    }

    /** 「三色归一」：记下「全」这一轮走的路线，三条都走过了就发。 */
    static void noteRoute(LivingEntity wearer, HatType route) {
        if (!(wearer instanceof ServerPlayer player) || route == null) {
            return;
        }
        Set<HatType> seen = ROUTES.computeIfAbsent(player.getUUID(), key -> new HashSet<>());
        seen.add(route);
        if (seen.size() >= HatType.routeChoices().length) {
            grant(player, TRINITY);
        }
    }
}
