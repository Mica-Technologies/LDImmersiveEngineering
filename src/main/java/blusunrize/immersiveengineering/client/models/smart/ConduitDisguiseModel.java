/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.conduit.TileEntityGroundFeeder;
import com.google.common.collect.ImmutableList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.common.property.IExtendedBlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Drawing a ground feeder as whatever it is pretending to be.
 * <p>
 * <strong>It borrows the other block's baked model rather than copying its quads.</strong> That is
 * the whole implementation, and it is deliberately less clever than {@link FeedthroughModel}, which
 * bakes a fresh model per disguise and caches it. There is nothing to bake here: the feeder is a
 * plain cube wearing a plain cube, so the quads wanted are exactly the ones already baked for the
 * block being worn. Fetching them is a map lookup and returning a list somebody else built, which is
 * cheaper than any cache would be and cannot go stale.
 * <p>
 * <strong>The tint is not baked in either</strong>, and that is the part worth being careful about.
 * Grass, leaves and foliage are grey textures coloured per position from the biome, so a model that
 * baked the colour would take the colour of wherever the first one happened to be built and carry it
 * across every biome border in the save -- the single most visible way this feature could fail, in a
 * block whose entire job is not being noticed. Instead the quads keep their tint indices and vanilla
 * colours them per position through
 * {@link blusunrize.immersiveengineering.client.IEDefaultColourHandlers}, which asks
 * {@link #disguiseColour} what the worn block would have said. A feeder in a swamp is swamp-green
 * and the same feeder in a plains is plains-green, for no work at all.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitDisguiseModel implements IBakedModel
{
	/**
	 * What a feeder with nothing to wear draws: bare metal, the same thing the item shows. Better
	 * than drawing nothing, which would read as a bug rather than as a block awaiting surroundings.
	 */
	private final IBakedModel bare;

	public ConduitDisguiseModel(IBakedModel bare)
	{
		this.bare = bare;
	}

	/**
	 * The colour the worn block would be at this position, or null if there is nothing worn here.
	 * <p>
	 * Lives on the model rather than on {@code BlockConduit} because it reaches for
	 * {@link Minecraft}, and the block is loaded on a dedicated server where that class is not
	 * there to be reached for.
	 */
	@Nullable
	public static Integer disguiseColour(@Nullable IBlockAccess world, @Nullable BlockPos pos,
										 int tintIndex)
	{
		if(world==null||pos==null)
			return null;
		TileEntity te = world.getTileEntity(pos);
		if(!(te instanceof TileEntityGroundFeeder))
			return null;
		IBlockState worn = ((TileEntityGroundFeeder)te).getDisguise();
		if(worn==TileEntityGroundFeeder.BARE)
			return null;
		return Minecraft.getMinecraft().getBlockColors().colorMultiplier(worn, world, pos, tintIndex);
	}

	@Nonnull
	@Override
	public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand)
	{
		IBlockState worn = TileEntityGroundFeeder.BARE;
		if(state instanceof IExtendedBlockState)
		{
			TileEntity te = ((IExtendedBlockState)state).getValue(IEProperties.TILEENTITY_PASSTHROUGH);
			if(te instanceof TileEntityGroundFeeder)
				worn = ((TileEntityGroundFeeder)te).getDisguise();
		}
		//Which layer the chunk is being built for. BlockConduit offers the feeder in three of them,
		//because it cannot know in advance whether it will end up wearing solid stone or
		//cutout-mipped grass; this is where the two that do not apply come back empty.
		BlockRenderLayer layer = MinecraftForgeClient.getRenderLayer();

		if(worn==TileEntityGroundFeeder.BARE)
			return layer==null||layer==BlockRenderLayer.SOLID
					?bare.getQuads(state, side, rand): ImmutableList.of();

		if(layer!=null&&!worn.getBlock().canRenderInLayer(worn, layer))
			return ImmutableList.of();
		//rand is handed on rather than dropped, so a block with weighted variants -- rotated stone,
		//scattered gravel -- still varies from one feeder to the next instead of every one in a
		//field picking the same rotation.
		return Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes()
				.getModelForState(worn).getQuads(worn, side, rand);
	}

	@Override
	public boolean isAmbientOcclusion()
	{
		//True, like the full opaque cubes a feeder is allowed to wear. Asking the disguise instead
		//is not possible from here: this is answered once for the model, not once per position.
		return true;
	}

	@Override
	public boolean isGui3d()
	{
		return true;
	}

	@Override
	public boolean isBuiltInRenderer()
	{
		return false;
	}

	@Nonnull
	@Override
	public TextureAtlasSprite getParticleTexture()
	{
		//Bare metal, whatever the feeder is wearing. The particle texture is a property of the model
		//rather than of a position, so it cannot follow the disguise -- and a feeder that spat metal
		//when hit is the better failure anyway: it tells a player who has forgotten where they put
		//one that they have found it.
		return bare.getParticleTexture();
	}

	@Nonnull
	@Override
	public ItemCameraTransforms getItemCameraTransforms()
	{
		return bare.getItemCameraTransforms();
	}

	@Nonnull
	@Override
	public ItemOverrideList getOverrides()
	{
		//The item never reaches this model -- conduit.json points the feeder's inventory variant
		//straight at the bare model, because an item has no surroundings to wear.
		return ItemOverrideList.NONE;
	}
}
