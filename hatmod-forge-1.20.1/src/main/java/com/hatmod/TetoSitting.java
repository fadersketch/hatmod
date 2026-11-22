package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 让 Teto **留在原地 / 继续跟着** —— 口语上常说的「坐下」，但她其实没有坐这个动作：
 * 她只是停在原地待命，不跟、不闲聊、不招仇恨。台词也一律按「留下来」来写。
 *
 * <p>手势是**潜行 + 空手**右键（原版驯服的狼是右键，但这里右键已经被喂食占用了，
 * 而且右键她还会触发她自己的交互）。手里拿着东西时不抢：拿着食物是喂、拿着别的照旧。
 *
 * <p>两条规矩：
 * <ul>
 *   <li>链路（{@code BrainrotCycle}）正在跑的时候**不让坐** —— 那一轮她是主力，
 *       坐下等于把光柱和负面场一起按停，会让人以为出 bug 了。她会说一句「现在不行」。</li>
 *   <li>反过来，只要名下有一只坐着的，链路开启时**一只都不放**（见 {@code BrainrotCycle#start}）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TetoSitting {

    /** 留守 / 叫起那句的冷却（刻）：来回切换手势也只 5 秒一句。 */
    private static final int STAY_TALK_TICKS = 100;

    private TetoSitting() {
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Teto teto)) {
            return;
        }
        // 空手右键实体时，客户端会**两只手各发一个包**（主手没接住就试副手），
        // 服务端就会收到两次 EntityInteract —— 两次都切一下 = 刚坐下就站起来。
        // 只认主手，副手那次直接不管。
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player player = event.getEntity();
        // 手里拿什么都行；木棍那一下留给「舞曲静音」（见 TetoAction）
        // 脑蚀那一下是「叫她走」，不是留守（见 TetoDismiss）
        if (!player.isShiftKeyDown() || event.getItemStack().is(Items.STICK)
                || event.getItemStack().is(HatItems.BRAIN_HAT.get())) {
            return;
        }
        // 两端都要吞掉：客户端不吞的话，手里那件东西会被自己「用」出去
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!teto.isOwnedBy(player)) {
            // 别人的 Teto 不归你管（她会自己走开，但不会听你的）
            TetoTalk.sayNear(level, teto, 24.0D,
                    TetoTalk.pick(level.random, "hatmod.teto.stay.stranger", 2));
            return;
        }
        boolean chainRunning = BrainrotCycle.isRunning(player);
        if (!teto.isSitting() && chainRunning) {
            // 链路正在跑：不让她走开，而且**一句话都不说** ——
            // 曲子里任何和「留守」有关的台词都会打断节奏，玩家听到只会以为出事了。
            return;
        }
        // 再压一道去抖：同一刻 / 相邻几刻里的重复手势只算一次
        if (!teto.hatmodAcceptSitCommand(level.getGameTime())) {
            return;
        }
        boolean sitting = !teto.isSitting();
        teto.setSitting(sitting);
        if (sitting && player instanceof ServerPlayer serverPlayer) {
            // 她记得你让她留守过（闲聊时会说「我就真的没走」）
            HatSettings.rememberTeto(serverPlayer.getUUID(), TetoSpeech.SAT);
        }
        if (!sitting && chainRunning) {
            // 曲子正放着的时候把她叫起来：直接**收编进这一轮**。
            // 不然她会变成「链路期间的一只散养 Teto」—— 不绕圈、不放负面场、不挡刀，
            // 却一直跟着你聊天，看着像 bug。收编是静默的（链路期间不说话）。
            double phase = Math.toRadians(player.getYRot()) + Math.PI / 2.0D;
            teto.hatmodAdopt(player, phase);
        }
        level.sendParticles(sitting ? ParticleTypes.CLOUD : ParticleTypes.HAPPY_VILLAGER,
                teto.getX(), teto.getY() + 0.2D, teto.getZ(),
                sitting ? 10 : 6, 0.35D, 0.15D, 0.35D, 0.01D);
        level.playSound(null, teto.getX(), teto.getY(), teto.getZ(),
                SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.NEUTRAL,
                0.7F, sitting ? 0.85F : 1.25F);
        if (!chainRunning) {
            TetoTalk.sayCooled(level, teto, 24.0D, "stay", STAY_TALK_TICKS, TetoSpeech.pick(level.random,
                    TetoSpeech.summonsOf(player.getUUID()),
                    sitting ? "hatmod.teto.stay.down" : "hatmod.teto.stay.up", 2, 2));
        }
    }
}
