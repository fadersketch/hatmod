package com.hatmod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 主人受伤 / 死亡时 Teto 的反应。
 *
 * <p>以前她只对自己挨打有反应（{@code Teto#hurt}），主人在旁边被砍得半死、或者直接倒下，
 * 她一声不吭。这里补上：
 * <ul>
 *   <li><b>受伤</b>：主人吃到一次真实伤害就说一句，10 秒冷却 —— 站在岩浆里也不会刷屏；</li>
 *   <li><b>死亡</b>：最后一句，随后她会自己散场（见 {@code Teto#tick} 的「主人已经死亡」）。</li>
 * </ul>
 *
 * <p>三种话不许叠在一起：
 * <ul>
 *   <li>「闪避」那句在 {@code HatProtection} 里、这一击**被取消**的时候说（见 {@code BrainrotQte}）——
 *       这里只听 {@link LivingDamageEvent}（伤害真的落下才算），闪避掉的那一下根本不会进来；</li>
 *   <li>这一击要是直接把人打死，受伤那句延后一刻才说 —— 延迟里收到死亡事件就作废，
 *       只留死亡那一句，不会一先一后冒出两个气泡。</li>
 * </ul>
 *
 * <p>两套词按**她的状态**分：跟着的时候是一套，留守等待（坐着）的时候是另一套 ——
 * 她不在你身边，能做的只有听声音。再按召唤次数分层（第一次 / 第二次 / 第三次往后），
 * 见 {@link TetoSpeech#pick}。
 *
 * <p>链路期间补出来的分身不开口；几只 Teto 同一刻一起说的话由
 * {@link TetoTalk#sayCooled} 按玩家去重，只会剩一句。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TetoReaction {

    /** 「受伤」那句的冷却（刻）：10 秒。 */
    private static final int HURT_COOLDOWN = 200;

    /**
     * 按**死法**挑一句：摔死、淹死、烧死、炸死、掉虚空各有一套，其余走原来那档。
     *
     * <p>挑的都是能调侃的那几种；「被怪打死」之类还是原来那套分层的说法。
     */
    private static String deathBase(net.minecraft.world.damagesource.DamageSource source) {
        if (source == null) {
            return "hatmod.teto.owner.die";
        }
        if (source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            return "hatmod.teto.owner.die.void";
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
            return "hatmod.teto.owner.die.fall";
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)) {
            return "hatmod.teto.owner.die.water";
        }
        if (source.is(net.minecraft.world.damagesource.DamageTypes.LAVA)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
            return "hatmod.teto.owner.die.fire";
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
            return "hatmod.teto.owner.die.boom";
        }
        if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity) {
            return "hatmod.teto.owner.die.mob";
        }
        return "hatmod.teto.owner.die";
    }

    /** 说给多远的人听（格）。 */
    private static final double RANGE = 32.0D;

    /** 复活时她站在离玩家多远的地方（格）。 */
    private static final double RESPAWN_RADIUS = 2.0D;

    /** 玩家 -> 这句「受伤」该在哪一刻说（下一刻）。死亡会把它划掉。 */
    private static final Map<UUID, Long> PENDING_HURT = new HashMap<>();

    private TetoReaction() {
    }

    /** 伤害真的落下了（被闪避 / 取消掉的那一下不会走到这里）。 */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getAmount() > 0.0F) {
            PENDING_HURT.put(player.getUUID(), player.level().getGameTime() + 1L);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING_HURT.remove(player.getUUID());     // 死了：受伤那句让给死亡台词
            // 跨存档记一笔「死在哪儿」：换存档重逢时她会提一句，死够多还会换语气（见 TetoRecall）
            if (!Teto.ownedBy(player).isEmpty()) {
                TetoRecall.noteDeath(player);
            }
            react(player, true, event.getSource());
        }
    }

    /** 下一刻把压着的「受伤」补上（人还活着才说）。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_HURT.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        Iterator<Map.Entry<UUID, Long>> iterator = PENDING_HURT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() > now) {
                continue;
            }
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.isAlive()) {
                react(player, false, null);
            }
        }
    }

    /**
     * 主人复活了：她**不会跟着死**（见 {@code Teto#tick}），而是在复活点旁边重新出现。
     *
     * <p>同一个维度就地挪过去；跨维度（重生锚放在下界那种）就把旧的那只收掉、
     * 在复活点放一只新的 —— 换维度的 API 两个加载器不一样，绕开它反而最稳。
     */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        boolean lostInAnotherDimension = false;
        for (Teto teto : Teto.ownedBy(player)) {
            if (teto.isRemoved()) {
                continue;
            }
            if (teto.level() != level) {
                teto.discard();
                lostInAnotherDimension = true;
                continue;
            }
            double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
            teto.moveTo(player.getX() + Math.cos(angle) * RESPAWN_RADIUS, player.getY(),
                    player.getZ() + Math.sin(angle) * RESPAWN_RADIUS,
                    player.getYRot() + 180.0F, 0.0F);
            teto.setDeltaMovement(0.0D, 0.0D, 0.0D);
            teto.fallDistance = 0.0F;
            TetoTalk.sayCooled(level, teto, RANGE, "owner_respawn", 40,
                    TetoTalk.pick(level.random, "hatmod.teto.owner.respawn", 2));
        }
        if (lostInAnotherDimension) {
            spawnFreshNear(player);
        }
    }

    /** 在玩家旁边放一只新的散养 Teto（跨维度复活时用）。 */
    private static void spawnFreshNear(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Teto teto = HatSummons.TETO.get().create(level);
        if (teto == null) {
            return;
        }
        double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
        teto.moveTo(player.getX() + Math.cos(angle) * RESPAWN_RADIUS, player.getY(),
                player.getZ() + Math.sin(angle) * RESPAWN_RADIUS, player.getYRot() + 180.0F, 0.0F);
        teto.hatmodVisitArrival(player);
        if (level.addFreshEntity(teto)) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL,
                    teto.getX(), teto.getY() + teto.getBbHeight() * 0.5D, teto.getZ(),
                    14, 0.3D, 0.7D, 0.3D, 0.01D);
        }
    }

    /** 让她对主人的遭遇说一句。 */
    private static void react(ServerPlayer player, boolean death,
                              net.minecraft.world.damagesource.DamageSource source) {
        ServerLevel level = player.serverLevel();
        if (TetoMassKill.massKill(level.getGameTime())) {
            return;                     // 「/kill @e」：话让给「一锅端」那一套（见 Teto#die）
        }
        int summons = TetoSpeech.summonsOf(player.getUUID());
        for (Teto teto : Teto.ownedBy(player)) {
            if (teto.isExtra()) {
                continue;               // 光柱分身不开口
            }
            String key;
            Object[] args = new Object[0];
            if (!death) {
                key = TetoSpeech.pick(level.random, summons,
                        "hatmod.teto.owner.hurt" + (teto.isSitting() ? ".sit" : ""), 2, 2);
            } else if (teto.isSitting()) {
                // 留守的那只没看见你是怎么没的，只有「又没等到你」那一套
                key = TetoSpeech.pick(level.random, summons, "hatmod.teto.owner.die.sit", 2, 2);
            } else {
                String cause = deathBase(source);
                if ("hatmod.teto.owner.die.mob".equals(cause)) {
                    // 被怪打死：把凶手的名字念出来（%s）
                    key = TetoTalk.pick(level.random, cause, 2);
                    args = new Object[] {source.getEntity() == null
                            ? "那家伙" : source.getEntity().getDisplayName()};
                } else if ("hatmod.teto.owner.die".equals(cause)) {
                    key = TetoSpeech.pick(level.random, summons, cause, 2, 2);
                } else {
                    key = TetoTalk.pick(level.random, cause, 2);
                }
            }
            TetoTalk.sayCooled(level, teto, RANGE, death ? "owner_die" : "owner_hurt",
                    death ? 60 : HURT_COOLDOWN, key, args);
        }
    }
}
