package de.peregrine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 3D cosmetics on your own character, built from many boxes with real depth:
 * articulated wings (shoulder → elbow → hand → fingers, membrane panels or layered feathers that fold as
 * they flap) and detailed headwear. Attached to the player model's body and head, so they follow every
 * animation, emote and the previews in menus.
 */
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** Try-on preview from the wardrobe (null = use the equipped item, "None" = show nothing). */
    public static volatile String previewWings, previewHeadwear, previewCape, previewPet;

    // ------------------------------------------------------------------ model building helpers
    private interface Builder {
        void build(PartDefinition root);
    }

    private static ModelPart bake(int texW, int texH, Builder b) {
        MeshDefinition mesh = new MeshDefinition();
        b.build(mesh.getRoot());
        return LayerDefinition.create(mesh, texW, texH).bakeRoot();
    }

    /** A box that runs along the wing: from {@code start} to {@code start+len} outwards on this side. */
    private static CubeListBuilder along(int u, int v, float start, float len, float y0, float h, float z0, float d, int side) {
        float x = side > 0 ? start : -(start + len);
        return CubeListBuilder.create().texOffs(u, v).mirror(side < 0).addBox(x, y0, z0, len, h, d);
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("peregrine", "textures/cosmetic/" + name + ".png");
    }

    // ------------------------------------------------------------------ wings
    private static final float[] FINGER_LEN = {13, 12, 10.5f, 8.5f};
    private static final float[] PANEL_H = {10, 11, 11, 9.5f};

    /** Bat-like wing: bones with membrane panels hanging from each bone, plus claws on the hand. */
    private static void membraneWing(PartDefinition root, int side) {
        PartDefinition wing = root.addOrReplaceChild("wing", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition arm = wing.addOrReplaceChild("arm", along(0, 0, 0, 7, -0.8f, 1.6f, -0.8f, 1.6f, side), PartPose.ZERO);
        arm.addOrReplaceChild("panel", along(0, 64 - 10, 0.5f, 6.5f, 0.6f, 9, -0.5f, 1, side), PartPose.ZERO);
        PartDefinition fore = arm.addOrReplaceChild("fore", along(20, 0, 0, 8, -0.7f, 1.4f, -0.7f, 1.4f, side), PartPose.offset(side * 7, 0, 0));
        fore.addOrReplaceChild("panel", along(20, 64 - 12, 0.3f, 7.5f, 0.5f, 11, -0.5f, 1, side), PartPose.ZERO);
        PartDefinition hand = fore.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(side * 8, 0, 0));
        // claw sticking up at the wrist
        hand.addOrReplaceChild("claw", along(0, 64, 0, 2.5f, -0.4f, 0.8f, -0.4f, 0.8f, side),
                PartPose.rotation(0, 0, side * -1.0f));
        for (int i = 0; i < FINGER_LEN.length; i++) {
            float ang = 0.12f + i * 0.34f; // fan downwards
            PartDefinition finger = hand.addOrReplaceChild("f" + i, along(40, 0, 0, FINGER_LEN[i], -0.5f, 1, -0.5f, 1, side),
                    PartPose.rotation(0, 0, side * ang));
            int pv = 64 - (int) Math.ceil(PANEL_H[i]) - 1;
            finger.addOrReplaceChild("panel", along(36 + i * 22, pv, 0.6f, FINGER_LEN[i] - 1.2f, 0.4f, PANEL_H[i], -0.5f, 1, side), PartPose.ZERO);
        }
    }

    /** Bird-like wing: bones with layered primary, secondary and covert feathers. */
    private static void featherWing(PartDefinition root, int side) {
        PartDefinition wing = root.addOrReplaceChild("wing", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition arm = wing.addOrReplaceChild("arm", along(0, 0, 0, 7, -1, 2, -1, 2, side), PartPose.ZERO);
        for (int i = 0; i < 3; i++) { // tertials
            arm.addOrReplaceChild("t" + i, along(0, 16, 0, 8 - i * 0.5f, -1, 2, -0.5f, 1, side),
                    PartPose.offsetAndRotation(side * (1.5f + i * 2.2f), 0.5f, 0.2f, 0, 0, side * 1.45f));
        }
        for (int i = 0; i < 3; i++) { // coverts in front
            arm.addOrReplaceChild("c" + i, along(0, 64, 0, 4.5f, -1, 2, -0.5f, 1, side),
                    PartPose.offsetAndRotation(side * (1.5f + i * 2.2f), 0.2f, -0.7f, 0, 0, side * 1.25f));
        }
        PartDefinition fore = arm.addOrReplaceChild("fore", along(0, 0, 0, 8, -0.8f, 1.6f, -0.8f, 1.6f, side), PartPose.offset(side * 7, 0, 0));
        for (int i = 0; i < 5; i++) { // secondaries
            fore.addOrReplaceChild("s" + i, along(0, 22, 0, 11 - i * 0.4f, -1, 2, -0.5f, 1, side),
                    PartPose.offsetAndRotation(side * (0.5f + i * 1.8f), 0.3f, 0.3f, 0, 0, side * (1.35f - i * 0.05f)));
            fore.addOrReplaceChild("c" + i, along(0, 70, 0, 5, -1, 2, -0.5f, 1, side),
                    PartPose.offsetAndRotation(side * (0.8f + i * 1.8f), 0.1f, -0.6f, 0, 0, side * 1.2f));
        }
        PartDefinition hand = fore.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(side * 8, 0, 0));
        for (int i = 0; i < 6; i++) { // primaries fanning out from the hand
            hand.addOrReplaceChild("p" + i, along(0, 28 + i * 5, 0, 15 - i * 1.0f, -1, 2, -0.5f, 1, side),
                    PartPose.offsetAndRotation(0, 0, 0.2f - i * 0.1f, 0, 0, side * (1.15f - i * 0.2f)));
        }
    }

    /** Butterfly: an upper and a lower lobe with thickness and a dark rim. */
    private static void butterflyWing(PartDefinition root, int side) {
        PartDefinition wing = root.addOrReplaceChild("wing", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition arm = wing.addOrReplaceChild("arm", CubeListBuilder.create(), PartPose.ZERO);
        arm.addOrReplaceChild("upper", along(0, 0, 0, 13, -11, 11, -0.5f, 1, side), PartPose.rotation(0, 0, side * -0.15f));
        PartDefinition fore = arm.addOrReplaceChild("fore", CubeListBuilder.create(), PartPose.ZERO);
        fore.addOrReplaceChild("lower", along(0, 16, 0, 10, 0, 9, -0.4f, 1, side), PartPose.rotation(0, 0, side * 0.12f));
        fore.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.ZERO);
    }

    private record Wing(ModelPart model, ModelPart wing, ModelPart arm, ModelPart fore, ModelPart hand) {
        static Wing of(ModelPart m) {
            ModelPart w = m.getChild("wing"), a = w.getChild("arm"), f = a.getChild("fore");
            return new Wing(m, w, a, f, f.getChild("hand"));
        }
    }

    private static final Map<String, Wing[]> WINGS = new HashMap<>();

    private static Wing[] wings(String style) {
        return WINGS.computeIfAbsent(style, s -> {
            Wing[] pair = new Wing[2];
            for (int k = 0; k < 2; k++) {
                int side = k == 0 ? 1 : -1;
                ModelPart m = bake(128, 128, root -> {
                    switch (s) {
                        case "angel" -> featherWing(root, side);
                        case "butterfly" -> butterflyWing(root, side);
                        default -> membraneWing(root, side);
                    }
                });
                pair[k] = Wing.of(m);
            }
            return pair;
        });
    }

    // ------------------------------------------------------------------ headwear
    private static final ModelPart TOP_HAT = bake(128, 64, root -> {
        root.addOrReplaceChild("brim", CubeListBuilder.create().texOffs(0, 0).addBox(-6, -9.1f, -6, 12, 1, 12), PartPose.ZERO);
        // brim curls up a little at the sides
        root.addOrReplaceChild("curlL", CubeListBuilder.create().texOffs(50, 0).addBox(5, -10.1f, -5, 1, 1, 10), PartPose.ZERO);
        root.addOrReplaceChild("curlR", CubeListBuilder.create().texOffs(50, 0).addBox(-6, -10.1f, -5, 1, 1, 10), PartPose.ZERO);
        root.addOrReplaceChild("crown", CubeListBuilder.create().texOffs(0, 13).addBox(-4, -17.1f, -4, 8, 8, 8), PartPose.ZERO);
        root.addOrReplaceChild("ribbon", CubeListBuilder.create().texOffs(0, 32).addBox(-4.25f, -11.1f, -4.25f, 8.5f, 2, 8.5f), PartPose.ZERO);
        root.addOrReplaceChild("buckle", CubeListBuilder.create().texOffs(0, 40).addBox(-1.2f, -11.4f, -4.6f, 2.4f, 2.6f, 0.6f), PartPose.ZERO);
    });

    private static final ModelPart CROWN = bake(128, 64, root -> {
        root.addOrReplaceChild("front", CubeListBuilder.create().texOffs(0, 0).addBox(-4.6f, -10.6f, -4.7f, 9.2f, 2.6f, 1.1f), PartPose.ZERO);
        root.addOrReplaceChild("back", CubeListBuilder.create().texOffs(0, 0).addBox(-4.6f, -10.6f, 3.6f, 9.2f, 2.6f, 1.1f), PartPose.ZERO);
        root.addOrReplaceChild("left", CubeListBuilder.create().texOffs(30, 0).addBox(3.6f, -10.6f, -3.6f, 1.1f, 2.6f, 7.2f), PartPose.ZERO);
        root.addOrReplaceChild("right", CubeListBuilder.create().texOffs(30, 0).addBox(-4.7f, -10.6f, -3.6f, 1.1f, 2.6f, 7.2f), PartPose.ZERO);
        root.addOrReplaceChild("velvet", CubeListBuilder.create().texOffs(0, 24).addBox(-3.6f, -11.4f, -3.6f, 7.2f, 2, 7.2f), PartPose.ZERO);
        float[][] spikes = {{-4.6f, -4.7f}, {-0.55f, -4.7f}, {3.5f, -4.7f}, {-4.6f, 3.6f}, {-0.55f, 3.6f}, {3.5f, 3.6f}, {-4.7f, -0.55f}, {3.6f, -0.55f}};
        for (int i = 0; i < spikes.length; i++) {
            boolean mid = i == 1 || i == 4 || i >= 6;
            float h = mid ? 3.2f : 2.4f;
            root.addOrReplaceChild("spike" + i, CubeListBuilder.create().texOffs(60, 0).addBox(spikes[i][0], -10.6f - h, spikes[i][1], 1.1f, h, 1.1f), PartPose.ZERO);
            root.addOrReplaceChild("pearl" + i, CubeListBuilder.create().texOffs(0, 44).addBox(spikes[i][0] - 0.1f, -10.6f - h - 1.2f, spikes[i][1] - 0.1f, 1.3f, 1.3f, 1.3f), PartPose.ZERO);
        }
        root.addOrReplaceChild("ruby", CubeListBuilder.create().texOffs(0, 32).addBox(-0.9f, -10.1f, -5.2f, 1.8f, 1.6f, 0.7f), PartPose.ZERO);
        root.addOrReplaceChild("gemB", CubeListBuilder.create().texOffs(0, 32).addBox(-0.9f, -10.1f, 4.5f, 1.8f, 1.6f, 0.7f), PartPose.ZERO);
        root.addOrReplaceChild("sapphire", CubeListBuilder.create().texOffs(0, 36).addBox(4.5f, -10.1f, -0.8f, 0.7f, 1.6f, 1.6f), PartPose.ZERO);
        root.addOrReplaceChild("emerald", CubeListBuilder.create().texOffs(0, 40).addBox(-5.2f, -10.1f, -0.8f, 0.7f, 1.6f, 1.6f), PartPose.ZERO);
    });

    private static final int HALO_SEGMENTS = 22;
    private static final ModelPart HALO = bake(64, 32, root -> {
        for (int i = 0; i < HALO_SEGMENTS; i++) {
            float a = (float) (i * Math.PI * 2 / HALO_SEGMENTS);
            float r = 5f;
            root.addOrReplaceChild("s" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-0.85f, -0.45f, -0.6f, 1.7f, 0.9f, 1.2f),
                    PartPose.offsetAndRotation((float) Math.cos(a) * r, -13f, (float) Math.sin(a) * r, 0, (float) (-a + Math.PI / 2), 0));
        }
    });

    private static final ModelPart HORNS = bake(64, 64, root -> {
        float[] size = {2.2f, 1.9f, 1.6f, 1.25f, 0.8f};
        for (int side = -1; side <= 1; side += 2) {
            PartDefinition seg = root.addOrReplaceChild(side < 0 ? "r" : "l", CubeListBuilder.create(),
                    PartPose.offsetAndRotation(side * 2.6f, -7.8f, -1.2f, -0.15f, 0, side * 0.55f));
            for (int i = 0; i < size.length; i++) {
                float s = size[i];
                seg = seg.addOrReplaceChild("s", CubeListBuilder.create().texOffs(0, i * 8).addBox(-s / 2, -2.2f, -s / 2, s, 2.4f, s),
                        PartPose.offsetAndRotation(0, i == 0 ? 0 : -2.0f, 0, -0.32f, 0, side * -0.12f));
            }
        }
    });

    private static final ModelPart CAT_EARS = bake(64, 32, root -> {
        for (int side = -1; side <= 1; side += 2) {
            PartDefinition ear = root.addOrReplaceChild(side < 0 ? "r" : "l", CubeListBuilder.create().texOffs(0, 0).addBox(-1.6f, -2.4f, -0.6f, 3.2f, 2.4f, 1.2f),
                    PartPose.offsetAndRotation(side * 2.5f, -7.9f, -1f, -0.12f, 0, side * 0.3f));
            ear.addOrReplaceChild("mid", CubeListBuilder.create().texOffs(0, 0).addBox(-1.1f, -1.8f, -0.55f, 2.2f, 1.8f, 1.1f), PartPose.offset(0, -2.3f, 0));
            ear.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(0, 0).addBox(-0.5f, -1.2f, -0.5f, 1, 1.2f, 1), PartPose.offset(0, -4f, 0));
            ear.addOrReplaceChild("inner", CubeListBuilder.create().texOffs(0, 8).addBox(-1f, -3.4f, -0.8f, 2, 3.2f, 0.3f), PartPose.ZERO);
            ear.addOrReplaceChild("tuft", CubeListBuilder.create().texOffs(0, 16).addBox(-0.4f, -1.2f, -0.95f, 0.8f, 1, 0.3f), PartPose.ZERO);
        }
    });

    // ------------------------------------------------------------------ pets (vanilla models and textures)
    /** Pet name → model layer, texture, scale and how high its feet float next to the head. */
    public record PetDef(ModelLayerLocation layer, String texture, float scale, boolean flies) {
    }

    public static final Map<String, PetDef> PETS = new java.util.LinkedHashMap<>();

    static {
        PETS.put("Parrot", new PetDef(ModelLayers.PARROT, "parrot/parrot_red_blue", 0.8f, true));
        PETS.put("Bee", new PetDef(ModelLayers.BEE, "bee/bee", 0.6f, true));
        PETS.put("Allay", new PetDef(ModelLayers.ALLAY, "allay/allay", 0.65f, true));
        PETS.put("Fox", new PetDef(ModelLayers.FOX, "fox/fox", 0.45f, false));
        PETS.put("Axolotl", new PetDef(ModelLayers.AXOLOTL, "axolotl/axolotl_lucy", 0.55f, false));
        PETS.put("Frog", new PetDef(ModelLayers.FROG, "frog/frog_temperate", 0.6f, false));
        PETS.put("Copper golem", new PetDef(ModelLayers.COPPER_GOLEM, "copper_golem/copper_golem", 0.45f, false));
    }

    private final EntityRendererProvider.Context ctx;
    private final Map<String, ModelPart> petModels = new HashMap<>();

    public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityRendererProvider.Context ctx) {
        super(parent);
        this.ctx = ctx;
    }

    private ModelPart petModel(String name, PetDef def) {
        return petModels.computeIfAbsent(name, n -> {
            try {
                return ctx.bakeLayer(def.layer());
            } catch (Throwable e) {
                PeregrineClient.LOG.warn("Pet model {} unavailable: {}", n, e.toString());
                return null;
            }
        });
    }

    /** Finds wing parts at the usual places of the vanilla models (root, body, bone). */
    private static void flapWings(ModelPart root, float flap) {
        for (String holder : new String[]{"", "body", "bone"}) {
            ModelPart p = root;
            if (!holder.isEmpty()) {
                if (!root.hasChild(holder)) continue;
                p = root.getChild(holder);
            }
            if (p.hasChild("left_wing")) p.getChild("left_wing").zRot = -flap;
            if (p.hasChild("right_wing")) p.getChild("right_wing").zRot = flap;
            if (p.hasChild("bone") && holder.isEmpty()) flapWings(p.getChild("bone"), flap);
        }
    }

    private static String petStyle() {
        String p = previewPet;
        if (p != null) return p.equals("None") ? null : p;
        return Modules.PET.enabled ? Modules.PET.style.get() : null;
    }

    private void drawPet(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state) {
        String name = petStyle();
        if (name == null) return;
        PetDef def = PETS.get(name);
        if (def == null) return;
        ModelPart model = petModel(name, def);
        if (model == null) return;
        float t = state.ageInTicks;
        model.resetPose();
        if (def.flies()) flapWings(model, 0.35f + (float) Math.sin(t * 1.6f) * 0.55f);
        int side = Modules.PET.side.is("Left") ? 1 : -1; // +x is the player's left
        float bob = (float) Math.sin(t * 0.12f) * 0.06f;
        pose.pushPose();
        // next to the head, a little behind; it floats (or sits on the shoulder for walking animals)
        if (def.flies()) {
            pose.translate(side * 0.62f, -0.55f + bob, 0.12f);
        } else {
            pose.translate(side * 0.36f, -0.02f + bob * 0.3f, 0.02f); // on the shoulder
        }
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Math.sin(t * 0.05f) * 12f));
        float sc = def.scale();
        pose.scale(sc, sc, sc);
        pose.translate(0, -1.5f, 0); // vanilla models stand with their feet at y = 24 px
        draw(out, pose, model, RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/entity/" + def.texture() + ".png"), false), light);
        pose.popPose();
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

    /** Equipped style, or the wardrobe's try-on preview; null when nothing should be shown. */
    private static String wingStyle() {
        String p = previewWings;
        if (p != null) return p.equals("None") ? null : p;
        return Modules.WINGS.enabled ? Modules.WINGS.style.get() : null;
    }

    private static String headStyle() {
        String p = previewHeadwear;
        if (p != null) return p.equals("None") ? null : p;
        return Modules.HEADWEAR.enabled ? Modules.HEADWEAR.style.get() : null;
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector out, int light, AvatarRenderState state, float yRot, float xRot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.id != mc.player.getId() || state.isInvisible) return;
        PlayerModel model = getParentModel();
        float t = state.ageInTicks;

        String ws = wingStyle();
        if (ws != null && !state.isFallFlying) {
            String style = ws.toLowerCase();
            float size = Modules.WINGS.size.is("Large") ? 1.2f : Modules.WINGS.size.is("Small") ? 0.8f : 1f;
            boolean butterfly = style.equals("butterfly");
            float speed = butterfly ? 0.45f : state.walkAnimationSpeed > 0.3f ? 0.32f : 0.11f;
            float s = (float) Math.sin(t * speed); // -1 … 1: downstroke when rising
            Wing[] pair = wings(style);
            pose.pushPose();
            model.body.translateAndRotate(pose);
            pose.translate(0, (state.isCrouching ? 3.2f : 2.6f) / 16f, 2.4f / 16f);
            pose.scale(size, size, size);
            RenderType type = RenderTypes.entityCutout(tex("wings_" + style), false);
            for (int k = 0; k < 2; k++) {
                int side = k == 0 ? 1 : -1;
                Wing w = pair[k];
                w.model.x = side * 1.4f;
                if (butterfly) {
                    w.wing.yRot = side * -(0.25f + 0.55f * (s * 0.5f + 0.5f));
                    w.wing.zRot = side * -0.1f;
                    w.wing.xRot = 0.1f;
                } else {
                    // shoulder sweeps back and lifts, elbow and wrist fold on the upstroke
                    w.wing.xRot = 0.15f;
                    w.wing.yRot = side * -(0.55f + 0.25f * s);
                    w.wing.zRot = side * -(0.2f + 0.3f * s);
                    w.fore.yRot = side * -(0.25f - 0.2f * s);
                    w.fore.zRot = side * (0.18f + 0.22f * (1 - s) * 0.5f);
                    w.hand.zRot = side * (0.08f + 0.18f * (1 - s) * 0.5f);
                    w.hand.yRot = side * -(0.15f - 0.15f * s);
                }
                draw(out, pose, w.model, type, light);
            }
            pose.popPose();
        }

        drawPet(pose, out, light, state);

        String hs = headStyle();
        if (hs != null) {
            pose.pushPose();
            model.head.translateAndRotate(pose);
            switch (hs) {
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
