package de.peregrine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * 3D cosmetics on your own character: wings on the back and headwear on the head. Real models with
 * textures, attached to the player model's body and head so they follow every animation (and emotes).
 */
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    // ---- wings: one textured plane per side (24×18 px), mirrored for the right wing
    private static final ModelPart WING_LEFT = bake(64, 32, root -> root.addOrReplaceChild("w",
            CubeListBuilder.create().texOffs(0, 0).addBox(0, -6, 0, 24, 18, 0), PartPose.ZERO));
    private static final ModelPart WING_RIGHT = bake(64, 32, root -> root.addOrReplaceChild("w",
            CubeListBuilder.create().texOffs(0, 0).mirror().addBox(-24, -6, 0, 24, 18, 0), PartPose.ZERO));

    // ---- headwear
    private static final ModelPart TOP_HAT = bake(64, 32, root -> {
        root.addOrReplaceChild("brim", CubeListBuilder.create().texOffs(0, 0).addBox(-5.5f, -9.1f, -5.5f, 11, 1, 11), PartPose.ZERO);
        root.addOrReplaceChild("crown", CubeListBuilder.create().texOffs(0, 12).addBox(-4, -16.1f, -4, 8, 7, 8), PartPose.ZERO);
    });
    private static final ModelPart CROWN = bake(64, 32, root -> {
        root.addOrReplaceChild("front", CubeListBuilder.create().texOffs(0, 0).addBox(-4.5f, -10.1f, -4.6f, 9, 2, 1), PartPose.ZERO);
        root.addOrReplaceChild("back", CubeListBuilder.create().texOffs(0, 0).addBox(-4.5f, -10.1f, 3.6f, 9, 2, 1), PartPose.ZERO);
        root.addOrReplaceChild("left", CubeListBuilder.create().texOffs(0, 4).addBox(3.6f, -10.1f, -3.6f, 1, 2, 7), PartPose.ZERO);
        root.addOrReplaceChild("right", CubeListBuilder.create().texOffs(0, 4).addBox(-4.6f, -10.1f, -3.6f, 1, 2, 7), PartPose.ZERO);
        float[][] spikes = {{-4.5f, -4.6f}, {-0.5f, -4.6f}, {3.5f, -4.6f}, {-4.5f, 3.6f}, {-0.5f, 3.6f}, {3.5f, 3.6f}, {-4.6f, -0.5f}, {3.6f, -0.5f}};
        for (int i = 0; i < spikes.length; i++) {
            root.addOrReplaceChild("spike" + i, CubeListBuilder.create().texOffs(0, 10).addBox(spikes[i][0], -12.1f, spikes[i][1], 1, 2, 1), PartPose.ZERO);
        }
    });
    private static final int HALO_SEGMENTS = 14;
    private static final ModelPart HALO = bake(16, 16, root -> {
        for (int i = 0; i < HALO_SEGMENTS; i++) {
            float a = (float) (i * Math.PI * 2 / HALO_SEGMENTS);
            float r = 4.6f;
            root.addOrReplaceChild("s" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-1.5f, -0.5f, -0.5f, 3, 1, 1),
                    PartPose.offsetAndRotation((float) Math.cos(a) * r, -12.5f, (float) Math.sin(a) * r, 0, (float) (-a + Math.PI / 2), 0));
        }
    });
    private static final ModelPart HORNS = bake(32, 32, root -> {
        for (int side = -1; side <= 1; side += 2) {
            String n = side < 0 ? "r" : "l";
            PartDefinition base = root.addOrReplaceChild(n + "0", CubeListBuilder.create().texOffs(0, 0).addBox(-1, -3, -1, 2, 3, 2),
                    PartPose.offsetAndRotation(side * 2.8f, -7.6f, -1.5f, -0.25f, 0, side * 0.45f));
            PartDefinition mid = base.addOrReplaceChild("m", CubeListBuilder.create().texOffs(0, 6).addBox(-1, -3, -1, 2, 3, 2).mirror(side < 0),
                    PartPose.offsetAndRotation(0, -2.6f, 0, 0.45f, 0, side * -0.35f));
            mid.addOrReplaceChild("t", CubeListBuilder.create().texOffs(0, 12).addBox(-0.5f, -3, -0.5f, 1, 3, 1),
                    PartPose.offsetAndRotation(0, -2.6f, 0, 0.55f, 0, side * -0.3f));
        }
    });
    private static final ModelPart CAT_EARS = bake(32, 32, root -> {
        for (int side = -1; side <= 1; side += 2) {
            PartDefinition ear = root.addOrReplaceChild(side < 0 ? "r" : "l", CubeListBuilder.create().texOffs(0, 0).addBox(-1.5f, -3, -0.5f, 3, 3, 1),
                    PartPose.offsetAndRotation(side * 2.6f, -7.8f, -1.2f, -0.1f, 0, side * 0.28f));
            ear.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(0, 6).addBox(-0.5f, -2, -0.5f, 1, 2, 1), PartPose.offset(0, -2.6f, 0));
        }
    });

    private interface Builder {
        void build(PartDefinition root);
    }

    private static ModelPart bake(int texW, int texH, Builder b) {
        MeshDefinition mesh = new MeshDefinition();
        b.build(mesh.getRoot());
        return LayerDefinition.create(mesh, texW, texH).bakeRoot();
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("peregrine", "textures/cosmetic/" + name + ".png");
    }

    public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    /** Draws one model part with the current pose (version-independent: custom geometry). */
    private static void draw(SubmitNodeCollector out, PoseStack pose, ModelPart part, RenderType type, int light) {
        out.submitCustomGeometry(pose, type, (p, consumer) -> {
            PoseStack ps = new PoseStack();
            ps.last().pose().set(p.pose());
            ps.last().normal().set(p.normal());
            part.render(ps, consumer, light, OverlayTexture.NO_OVERLAY);
        });
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.id != mc.player.getId() || state.isInvisible) return;
        PlayerModel model = getParentModel();
        float t = state.ageInTicks;

        if (Modules.WINGS.enabled && !state.isFallFlying) {
            String style = Modules.WINGS.style.get().toLowerCase();
            float size = Modules.WINGS.size.is("Large") ? 1.25f : Modules.WINGS.size.is("Small") ? 0.8f : 1f;
            float speed = state.walkAnimationSpeed > 0.3f ? 0.35f : 0.12f; // flap faster while running
            float flap = (float) Math.sin(t * speed) * 0.22f;
            pose.pushPose();
            model.body.translateAndRotate(pose);
            pose.translate(0, (state.isCrouching ? 3f : 2.5f) / 16f, 2.3f / 16f);
            pose.scale(size, size, size);
            RenderType type = RenderTypes.entityCutout(tex("wings_" + style), false);
            WING_LEFT.xRot = 0.12f;
            WING_LEFT.yRot = -(0.42f + flap);
            WING_LEFT.zRot = -0.18f - flap * 0.4f;
            WING_LEFT.x = 1.2f;
            WING_RIGHT.xRot = 0.12f;
            WING_RIGHT.yRot = 0.42f + flap;
            WING_RIGHT.zRot = 0.18f + flap * 0.4f;
            WING_RIGHT.x = -1.2f;
            draw(out, pose, WING_LEFT, type, light);
            draw(out, pose, WING_RIGHT, type, light);
            pose.popPose();
        }

        if (Modules.HEADWEAR.enabled) {
            pose.pushPose();
            model.head.translateAndRotate(pose);
            switch (Modules.HEADWEAR.style.get()) {
                case "Crown" -> draw(out, pose, CROWN, RenderTypes.entityCutout(tex("hat_crown"), false), light);
                case "Halo" -> {
                    HALO.y = (float) Math.sin(t * 0.08) * 0.6f;
                    HALO.yRot = t * 0.02f;
                    draw(out, pose, HALO, RenderTypes.entityCutout(tex("hat_halo"), false), 0xF000F0); // full bright
                    draw(out, pose, HALO, RenderTypes.eyes(tex("hat_halo")), 0xF000F0); // glow
                }
                case "Horns" -> draw(out, pose, HORNS, RenderTypes.entityCutout(tex("hat_horns"), false), light);
                case "Cat ears" -> draw(out, pose, CAT_EARS, RenderTypes.entityCutout(tex("hat_catears"), false), light);
                default -> draw(out, pose, TOP_HAT, RenderTypes.entityCutout(tex("hat_tophat"), false), light);
            }
            pose.popPose();
        }
    }
}
