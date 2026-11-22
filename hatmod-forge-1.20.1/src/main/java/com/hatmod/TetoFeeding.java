package com.hatmod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 喂 Teto 吃东西。
 *
 * <p>她一直在后室里找出口，也一直在找吃的（后室可没有法棍）。拿着**能吃的**右键她，
 * 她就真的吃掉一个：回血、冒爱心、说一句。食物名直接从物品本身取 ——
 * 加新食物不用改代码。
 *
 * <p>什么都能喂：**凡是带食物组件的物品都吃**（原版、别的模组都算），
 * 另外 {@code torchesbecomesunlight:ursus_bread}（大列巴）单独保底 —— 有些整合包里
 * 它没有食物组件，但也该认。
 *
 * <p>说什么话分三层，优先取前面的：
 * <ol>
 *   <li>她问过「上次那个 XX 还有吗」，这一口正好是回答（{@code feed.answer.*}）；</li>
 *   <li>这一类（面包 / 甜的 / 肉 / 鱼 / 水果 / 菜 / 可疑的 / 金的）头一两次吃到
 *       （{@code food.<类别>.1/2}，各只一次）；</li>
 *   <li>按「一辈子喂过她几次」分档（{@code feed} / {@code feed.more} / {@code feed.again} /
 *       {@code feed.over}）。</li>
 * </ol>
 *
 * <p>类别是按注册名的关键字认的（见 {@link #foodTag}）—— 全部字符串判断，
 * **没有编译期依赖**，没装的模组自然不会触发。
 */
@Mod.EventBusSubscriber(modid = HatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TetoFeeding {

    /** 「森罗物语：厨房」的命名空间。 */
    private static final String COOKERY = "kaleidoscope_cookery";
    /** 「薪火造炬成阳」的大列巴：整条命中最像法棍的那个。 */
    private static final ResourceLocation URSUS_BREAD =
            new ResourceLocation("torchesbecomesunlight", "ursus_bread");
    /** 喂一口回多少血（难吃的那种只回一点）。 */
    private static final float FEED_HEAL = 20.0F;
    private static final float FEED_HEAL_BAD = 4.0F;
    /** 喂食那句台词的冷却（刻）：连着喂也只 5 秒一句。 */
    private static final int FEED_TALK_TICKS = 100;

    /** 两次投喂之间至少要隔多久（刻）：5 秒。 */
    private static final int FEED_INTERVAL = 100;
    /** 难吃的那种要隔更久（30 秒）—— 把腐肉一把一把塞过来，就是把人当垃圾桶。 */
    private static final int FEED_INTERVAL_BAD = 600;
    /** 「还没到点」那句的冷却（刻）：5 秒，和最短的投喂间隔对齐。 */
    private static final int WAIT_TALK_TICKS = 100;

    /**
     * 链路（被动触发）期间，她只有在血量低于这个比例时才肯吃东西。
     *
     * <p>平时这一下**静默**：不扣物品、不回血、不说话、不冒爱心 —— 她正在场上跳，
     * 不是来吃饭的。
     */
    private static final double CHAIN_FEED_HEALTH_RATIO = 0.30D;
    /** 链路里那一口额外回多少血（按最大生命的比例）：救急用。 */
    private static final float CHAIN_FEED_HEAL_RATIO = 0.15F;
    /** 链路里专门那一套台词有几条。 */
    private static final int CHAIN_FEED_LINES = 4;

    /**
     * 每个玩家上一次**成功**投喂的时刻（刻，服务端内存，不进存档）。
     *
     * <p>按玩家记而不是按实体记：她名下的几只共用一个胃。
     */
    private static final java.util.Map<java.util.UUID, Long> LAST_FEED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private TetoFeeding() {
    }

    /**
     * 这东西她吃不吃：**凡是能吃的都吃** —— 原版的面包苹果、别的模组的菜、烤好的肉，
     * 只要是食物就接。大列巴单独保底（有些整合包里它没有食物组件，但也该认）。
     */
    static boolean isSnack(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(URSUS_BREAD)) {
            return true;
        }
        return stack.getItem().getFoodProperties() != null;
    }

    /**
     * 这东西属于哪一类（决定她第一次吃到这类时说什么）。
     *
     * <p>按注册名的关键字分，认不出来就返回 {@code null} —— 那就只说普通池子里的台词。
     */
    static String foodTag(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        // 法棍档：大列巴（装没装都认）和一切名字里带 baguette 的 —— 她的最爱，单独一层
        if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(URSUS_BREAD)
                || path.contains("baguette")) {
            return "baguette";
        }
        if (path.contains("golden")) {
            return "golden";
        }
        if (path.contains("rotten") || path.contains("spider_eye") || path.contains("poisonous")
                || path.contains("suspicious") || path.contains("pufferfish")
                || path.contains("chorus")) {
            return "strange";
        }
        if (path.contains("bread") || path.contains("baguette") || path.contains("toast")
                || path.contains("bun") || path.contains("noodle") || path.contains("dumpling")) {
            return "bread";
        }
        if (path.contains("cake") || path.contains("cookie") || path.contains("pie")
                || path.contains("honey") || path.contains("candy") || path.contains("chocolate")
                || path.contains("jam") || path.contains("sugar")) {
            return "sweet";
        }
        if (path.contains("beef") || path.contains("pork") || path.contains("mutton")
                || path.contains("chicken") || path.contains("rabbit") || path.contains("steak")
                || path.contains("meat") || path.contains("ham") || path.contains("sausage")) {
            return "meat";
        }
        if (path.contains("cod") || path.contains("salmon") || path.contains("fish")
                || path.contains("squid") || path.contains("shrimp")) {
            return "fish";
        }
        if (path.contains("apple") || path.contains("berry") || path.contains("berries")
                || path.contains("melon") || path.contains("fruit") || path.contains("grape")
                || path.contains("peach") || path.contains("pear") || path.contains("orange")
                || path.contains("cherry")) {
            return "fruit";
        }
        if (path.contains("carrot") || path.contains("potato") || path.contains("beetroot")
                || path.contains("kelp") || path.contains("cabbage") || path.contains("tomato")
                || path.contains("onion") || path.contains("mushroom") || path.contains("wheat")) {
            return "veggie";
        }
        return null;
    }

    /**
     * 一口食物带来的效果：底子是回血 + 短再生，再按类别加一点 —— 和她说的那句对得上。
     *
     * <ul>
     *   <li>甜的：加速（「跳舞的时候会更有力气」）；</li>
     *   <li>肉：力量；</li>
     *   <li>鱼：水下呼吸；</li>
     *   <li>水果：强一点的再生；</li>
     *   <li>菜：抗性提升；</li>
     *   <li>金的：伤害吸收（「这一口我留着慢慢吃」）；</li>
     *   <li>难吃的（腐肉那类）：只回一点血，外加 5 秒缓慢 —— 让她真的走不动。</li>
     * </ul>
     *
     * <p>这里原先是「头晕」（{@code Nausea}）：那个效果只扭曲**玩家**的画面，加在生物身上
     * 一点作用都没有。换成缓慢，生物的速度属性真的会被压下来，粒子也开成可见，
     * 一眼能看出她吃坏了。
     *
     * <p>都加在她身上，不给玩家 —— 喂饭不该变成给自己叠 buff。
     */
    private static void applyFood(Teto teto, String tag) {
        if ("strange".equals(tag)) {
            teto.heal(FEED_HEAL_BAD);
            // 缓慢 II × 5 秒，粒子开着：她真的会慢下来，玩家也看得见
            teto.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1, true, true, true));
            return;
        }
        teto.heal(FEED_HEAL);
        teto.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, false, true));
        if (tag == null) {
            return;
        }
        switch (tag) {
            case "sweet" -> teto.addEffect(
                    new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, true, false, true));
            case "meat" -> teto.addEffect(
                    new MobEffectInstance(MobEffects.DAMAGE_BOOST, 200, 0, true, false, true));
            case "fish" -> teto.addEffect(
                    new MobEffectInstance(MobEffects.WATER_BREATHING, 400, 0, true, false, true));
            case "fruit" -> teto.addEffect(
                    new MobEffectInstance(MobEffects.REGENERATION, 160, 1, true, false, true));
            case "baguette" -> {
                teto.addEffect(
                        new MobEffectInstance(MobEffects.REGENERATION, 160, 1, true, false, true));
                teto.addEffect(
                        new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 400, 1, true, false, true));
            }
            case "veggie" -> teto.addEffect(
                    new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 160, 0, true, false, true));
            case "golden" -> teto.addEffect(
                    new MobEffectInstance(MobEffects.ABSORPTION, 1200, 1, true, false, true));
            default -> {
                // 认不出来的东西：只给基础的回血 + 再生
            }
        }
    }

    /**
     * 特殊食物自带的药水效果：一颗不少全部吃到 —— 金苹果的吸收 + 再生 II、
     * 附魔金苹果的四样、腐肉的饥饿、河豚的中毒……这些都写在食物组件的配方表里，
     * 以前只按类别给固定效果，食物自带的这些全漏掉了。
     *
     * <p>概率直接无视：喂到嘴里的每一口都按「百分百中」算，要吃就全吃。
     * 每吃进一样，就按那个效果自己的颜色冒一圈药水粒子。
     */
    private static void applyItemEffects(Teto teto, ItemStack stack, ServerLevel level) {
        FoodProperties food = stack.getItem().getFoodProperties();
        if (food != null) {
            for (com.mojang.datafixers.util.Pair<MobEffectInstance, Float> pair
                    : food.getEffects()) {
                addWithBurst(teto, level, new MobEffectInstance(pair.getFirst()));
            }
        }
        // 可疑的炖菜：效果写在 NBT 里，食物组件上没有 —— 单独读
        if (stack.is(Items.SUSPICIOUS_STEW) && stack.hasTag()) {
            ListTag entries = stack.getTag().getList("Effects", 9);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                int duration = entry.contains("Duration", 99) ? entry.getInt("Duration") : 160;
                MobEffect effect = MobEffect.byId(entry.getByte("Id"));
                if (effect != null) {
                    addWithBurst(teto, level, new MobEffectInstance(effect, duration));
                }
            }
        }
    }

    /**
     * 把效果挂上去，并按效果自己的颜色冒一圈药水粒子。
     *
     * <p>{@code ENTITY_EFFECT} 这颗粒子在 1.20.x 里把**速度参数**当 RGB 读
     * （客户端 {@code SpellParticle$MobProvider} 直接 setColor(xSpeed, ySpeed, zSpeed)）。
     * 但粒子包只在 {@code count == 0} 时才把速度原样传下去 —— count 大于 0 的分支
     * 会把速度换成高斯随机数，颜色就全乱了。所以这里逐颗发（count 固定 0），
     * 随机散开的位置在服务端自己抖。
     */
    private static void addWithBurst(Teto teto, ServerLevel level, MobEffectInstance instance) {
        if (!teto.addEffect(instance)) {
            return;
        }
        int color = instance.getEffect().getColor();
        double r = (color >> 16 & 255) / 255.0D;
        double g = (color >> 8 & 255) / 255.0D;
        double b = (color & 255) / 255.0D;
        double cx = teto.getX();
        double cy = teto.getY() + teto.getBbHeight() * 0.5D;
        double cz = teto.getZ();
        for (int i = 0; i < 24; i++) {
            level.sendParticles(ParticleTypes.ENTITY_EFFECT,
                    cx + (level.random.nextDouble() - 0.5D) * 0.8D,
                    cy + (level.random.nextDouble() - 0.3D) * 0.9D,
                    cz + (level.random.nextDouble() - 0.5D) * 0.8D,
                    0, r, g, b, 1.0D);
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Teto teto) || !isSnack(event.getItemStack())) {
            return;
        }
        if (event.getEntity().isShiftKeyDown()) {
            return;             // 潜行右键是「坐下 / 起来」的手势（见 TetoSitting），不喂
        }
        ItemStack stack = event.getItemStack();
        // 名字和类别都要在扣数量之前取 —— 最后一块喂下去之后 stack 就成了空气，
        // 那时候再问「这是什么」只会得到 null，类别台词会全部哑火。
        Component name = stack.getHoverName();
        String tag = foodTag(stack);
        Player player = event.getEntity();
        // 两端都要吞掉：客户端不吞的话，这一下会落到物品自己的 use 上 ——
        // 喂给她的那块面包会被**玩家自己吃掉**。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // 链路里她顾不上吃：血量还在 30% 以上就**静默**拦下（无语音、无反馈、不扣物品）
        boolean chain = BrainrotCycle.isRunning(player);
        if (chain && teto.getMaxHealth() > 0.0F
                && teto.getHealth() / teto.getMaxHealth() >= CHAIN_FEED_HEALTH_RATIO) {
            return;
        }
        // 还没到下一次能吃的时候：东西不收、不回血、不冒爱心，只说一句
        long now = level.getGameTime();
        int gap = "strange".equals(tag) ? FEED_INTERVAL_BAD : FEED_INTERVAL;
        Long last = LAST_FEED.get(player.getUUID());
        if (last != null && now - last >= 0L && now - last < gap) {
            TetoTalk.sayCooled(level, teto, 24.0D, "feedWait", WAIT_TALK_TICKS,
                    TetoSpeech.feedWaitLine(level.random, tag));
            return;
        }
        if (LAST_FEED.size() > 64) {
            LAST_FEED.clear();
        }
        LAST_FEED.put(player.getUUID(), now);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        applyFood(teto, tag);
        applyItemEffects(teto, stack, level);
        int feeds = 1;
        String asked = "";
        String answer = null;
        String first = null;
        if (!chain && player instanceof ServerPlayer serverPlayer) {
            java.util.UUID owner = serverPlayer.getUUID();
            // 「上一次喂的是什么」得在更新之前取 —— 她问的就是这一样
            asked = HatSettings.tetoLastFood(owner);
            feeds = HatSettings.addTetoFeed(owner);
            TetoMood.adjust(owner, "strange".equals(tag) ? -3 : 2, now);
            if ("strange".equals(tag)) {
                // 腐肉这类：不当成「好吃的」，只记一条「别再给我」
                HatSettings.rememberTeto(owner, TetoSpeech.FED_BAD);
                HatSettings.rememberTetoBadFood(owner, name.getString());
                // 但她要是刚问过「上次那个还有吗」，你端来这个也得有句回答
                answer = TetoSpeech.feedAnswerLine(level.random, owner, asked, name.getString(), tag);
                if (answer != null) {
                    HatSettings.markTetoSpoken(owner, answer);
                } else {
                    first = TetoSpeech.foodFirstLine(level.random, owner, tag);
                }
            } else {
                // 她记得被喂过（闲聊时会问「还有吗」），也记得上一次喂的是什么
                HatSettings.rememberTeto(owner, TetoSpeech.FED);
                HatSettings.rememberTetoFood(owner, name.getString());
                // 她问过「上次那个还有吗」的话，这一口就是回答
                answer = TetoSpeech.feedAnswerLine(level.random, owner, asked, name.getString(), tag);
                if (answer != null) {
                    HatSettings.markTetoSpoken(owner, answer);
                }
                if (answer == null) {
                    first = TetoSpeech.foodFirstLine(level.random, owner, tag);
                }
            }
        }
        teto.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, false, true));
        level.sendParticles(ParticleTypes.HEART,
                teto.getX(), teto.getY() + teto.getBbHeight() * 0.75D, teto.getZ(),
                8, 0.45D, 0.35D, 0.45D, 0.02D);
        level.playSound(null, teto.getX(), teto.getY(), teto.getZ(),
                SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 1.0F, 1.45F);
        // 链路里救急的一口：多回一点血，说的是另一套话（不进「上次那个」那本账）
        if (chain) {
            teto.heal(teto.getMaxHealth() * CHAIN_FEED_HEAL_RATIO);
            TetoMood.adjust(player.getUUID(), 1, now);
            TetoTalk.sayCooled(level, teto, 24.0D, "feedChain", 40,
                    "hatmod.teto.feed.chain." + (1 + level.random.nextInt(CHAIN_FEED_LINES)));
            return;
        }
        // 每个阶段用自己的冷却键：刚喂完第一口又说第二口，不该被上一句的 5 秒冷却吃掉
        if (answer == null && first != null) {
            // 这一类她头一回吃：一辈子只有一次，不走冷却 —— 别让上一句的 5 秒把它憋掉
            TetoTalk.sayNear(level, teto, 24.0D, first, name);
            return;
        }
        String key;
        String kind;
        Object[] args = new Object[] {name};
        if (answer != null) {
            key = answer;
            kind = "feedAnswer";
            args = new Object[] {asked};
        } else {
            key = TetoSpeech.feedLine(level.random, TetoSpeech.summonsOf(player.getUUID()), feeds);
            kind = "feed" + Math.min(feeds, 4);
        }
        TetoTalk.sayCooled(level, teto, 24.0D, kind, FEED_TALK_TICKS, key, args);
    }
}
