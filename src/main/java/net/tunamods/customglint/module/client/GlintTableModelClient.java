package net.tunamods.customglint.module.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.block.GlintTableBlockEntity;
import net.tunamods.customglint.module.block.ModBlocks;

/**
 * Makes the placed Glint Table re-skin to match the player's chosen GUI skin (Default / Dark / Forge), a
 * purely client-side cosmetic tied to {@link GlintGuiConfig#tableSkin()}. Each (skin, facing) pair has its
 * own variant model; the block's baked model for every facing is replaced by a delegate that forwards to the
 * variant for the current skin. Cycling the skin calls {@link #refresh()} so the sections holding a table
 * re-mesh. Any baking-API failure falls back to the static (Forge) model rather than crashing.
 */
public final class GlintTableModelClient {
    private GlintTableModelClient() {}

    private static final String[] SKINS = { "default", "dark", "forge" };
    private static final Direction[] FACINGS = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    private static ResourceLocation variant(String skin, Direction d) {
        return CustomGlint.res("block/glint_table_" + skin + "_" + d.getSerializedName());
    }

    private static final Set<BlockPos> CLIENT_TABLES = ConcurrentHashMap.newKeySet();

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GlintTableModelClient::onRegisterAdditional);
        modEventBus.addListener(GlintTableModelClient::onModifyBakingResult);
        MinecraftForge.EVENT_BUS.addListener(GlintTableModelClient::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(GlintTableModelClient::onChunkUnload);
    }

    private static void onRegisterAdditional(ModelEvent.RegisterAdditional e) {
        for (String skin : SKINS) for (Direction d : FACINGS) e.register(variant(skin, d));
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult e) {
        Map<ResourceLocation, BakedModel> models = e.getModels();
        BlockState base = ModBlocks.GLINT_TABLE_BLOCK.get().defaultBlockState();
        Map<Direction, Map<String, BakedModel>> variants = new HashMap<>();
        for (Direction d : FACINGS) {
            Map<String, BakedModel> perSkin = new HashMap<>();
            for (String skin : SKINS) {
                BakedModel m = models.get(variant(skin, d));
                if (m != null) perSkin.put(skin, m);
            }
            variants.put(d, perSkin);
        }
        for (Direction d : FACINGS) {
            BlockState state = base.setValue(HorizontalDirectionalBlock.FACING, d);
            ModelResourceLocation loc = BlockModelShaper.stateToModelLocation(state);
            BakedModel fallback = models.get(loc);
            if (fallback == null) continue;
            try {
                models.put(loc, new SkinSwitchModel(fallback, variants.get(d)));
            } catch (UnsupportedOperationException ignored) {
                // Immutable baking result on some setup - leave the static model in place.
            }
        }
    }

    private static void onChunkLoad(ChunkEvent.Load e) {
        if (e.getLevel() == null || !e.getLevel().isClientSide()) return;
        for (BlockPos pos : e.getChunk().getBlockEntitiesPos()) {
            if (e.getChunk().getBlockEntity(pos) instanceof GlintTableBlockEntity) CLIENT_TABLES.add(pos.immutable());
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload e) {
        if (e.getLevel() == null || !e.getLevel().isClientSide()) return;
        int cx = e.getChunk().getPos().x, cz = e.getChunk().getPos().z;
        CLIENT_TABLES.removeIf(p -> (p.getX() >> 4) == cx && (p.getZ() >> 4) == cz);
    }

    public static void clearTracked() {
        CLIENT_TABLES.clear();
    }

    /** Re-mesh only the sections that contain a Glint Table so a placed table picks up a just-changed skin. */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (BlockPos p : CLIENT_TABLES) {
            mc.levelRenderer.setSectionDirty(
                    SectionPos.blockToSectionCoord(p.getX()),
                    SectionPos.blockToSectionCoord(p.getY()),
                    SectionPos.blockToSectionCoord(p.getZ()));
        }
    }

    /** Delegating baked model that forwards to the standalone variant for the current GUI skin + its facing. */
    private static final class SkinSwitchModel implements BakedModel {
        private final BakedModel fallback;
        private final Map<String, BakedModel> perSkin;

        SkinSwitchModel(BakedModel fallback, Map<String, BakedModel> perSkin) {
            this.fallback = fallback;
            this.perSkin = perSkin;
        }

        private BakedModel pick() {
            int idx = Math.floorMod(GlintGuiConfig.tableSkin(), SKINS.length);
            BakedModel m = perSkin == null ? null : perSkin.get(SKINS[idx]);
            return m != null ? m : fallback;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
            return pick().getQuads(state, side, rand);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
            return pick().getQuads(state, side, rand, data, renderType);
        }

        @Override
        public boolean useAmbientOcclusion() { return pick().useAmbientOcclusion(); }

        @Override
        public boolean isGui3d() { return pick().isGui3d(); }

        @Override
        public boolean usesBlockLight() { return pick().usesBlockLight(); }

        @Override
        public boolean isCustomRenderer() { return pick().isCustomRenderer(); }

        @Override
        @SuppressWarnings("deprecation")
        public TextureAtlasSprite getParticleIcon() { return pick().getParticleIcon(); }

        @Override
        public TextureAtlasSprite getParticleIcon(ModelData data) { return pick().getParticleIcon(data); }

        @Override
        public ItemOverrides getOverrides() { return pick().getOverrides(); }

        @Override
        public ItemTransforms getTransforms() { return pick().getTransforms(); }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return pick().getRenderTypes(state, rand, data);
        }
    }
}
