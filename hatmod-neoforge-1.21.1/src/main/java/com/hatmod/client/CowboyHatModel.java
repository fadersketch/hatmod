package com.hatmod.client;

import com.hatmod.HatMod;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.LivingEntity;

/**
 * 牛仔帽护甲模型：一顶带帽檐的帽子。
 *
 * <p>只靠 64x32 的护甲贴图做不出「宽帽檐」（头盔模型只是套在头上的一层壳），
 * 所以这里用自定义模型额外加了一块向外伸出的帽檐。
 *
 * <p>除 head 外的部件都留空（不渲染），帽冠与帽檐作为 head 的子节点，会跟随头部转动。
 */
public class CowboyHatModel extends HumanoidModel<LivingEntity> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(HatMod.id("cowboy_hat"), "main");

    public CowboyHatModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.ZERO);

        // 帽冠：头顶往上 5 像素
        head.addOrReplaceChild("crown", CubeListBuilder.create()
                        .texOffs(0, 0)
                        .addBox(-3.5F, -13.0F, -3.5F, 7, 5, 7),
                PartPose.ZERO);

        // 帽檐：20x20 的大宽檐，像大号牛仔帽。
        // 贴图块画在 128x64 护甲贴图的 (0,32) 处；旧内容原样留在左上角，
        // 所以 texOffs 的像素号不变，帽冠采样到的还是原来的那几格。
        head.addOrReplaceChild("brim", CubeListBuilder.create()
                        .texOffs(0, 32)
                        .addBox(-10.0F, -8.5F, -10.0F, 20, 1, 20),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 128, 64);
    }
}
