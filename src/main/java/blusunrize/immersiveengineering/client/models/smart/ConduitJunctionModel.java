/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.conduit.TileEntityJunctionBox;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.property.IExtendedBlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drawing a junction box: the housing against whichever surface its runs are on, and a stub out to the
 * block edge on every face that has anything to meet -- a run, a conductor broken out, or simply a
 * block bolted against it. A breakout's stub ends in a coloured mouth; a plain one does not.
 * <p>
 * <strong>Exactly what the multipart blockstate did, one step later.</strong> The same seventy-eight
 * generated models -- six housings, thirty-six plates, thirty-six stubs -- are picked in the same
 * combinations; they used to be picked by twelve boolean block properties and are now read off the
 * tile entity through {@code IEProperties.TILEENTITY_PASSTHROUGH}. See {@link ConduitRunModel} for
 * what those twelve properties were costing.
 * <p>
 * <strong>The plates keep their tint indices.</strong> A plate is painted near-white and coloured at
 * render time by {@code BlockConduit.getRenderColour}, which reads {@code EnumFacing.byIndex} off
 * the tint index -- so the quads are handed on exactly as the generated models baked them. Six
 * models and six indices instead of ninety-six models, which is the trade that made patch colours
 * affordable in the first place.
 * <p>
 * This model is not named by the blockstate directly: {@code ConnLoader} wraps it, so that a wire
 * strung to a box is drawn along with the box. See {@code ClientProxy}, which registers it as the
 * base model behind the key {@code conduit_junction_box}.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitJunctionModel implements IBakedModel
{
	/**
	 * Assembled quad lists, keyed by mount, joined faces and patched faces.
	 * <p>
	 * Concurrent because it is filled from {@code getQuads}, which runs on the chunk-render worker
	 * pool. Cleared through {@code IEApi.renderCacheClearers} on a resource reload, since every quad
	 * in here holds a sprite from an atlas that has been re-stitched underneath it.
	 */
	public static final Map<Integer, List<BakedQuad>[]> modelCache = new ConcurrentHashMap<>();

	/** One housing per surface the box can be bolted to, indexed by {@code EnumFacing.ordinal()}. */
	private final IBakedModel[] housings = new IBakedModel[EnumFacing.VALUES.length];
	/** A plate per face, per mount: where the housing is decides where a plate sits. */
	private final IBakedModel[][] plates;
	/** A stub per face, per mount, and null on the face the housing already reaches by itself. */
	private final IBakedModel[][] stubs;
	/** The same stubs cut short, worn under a coloured mouth. */
	private final IBakedModel[][] shortStubs;
	/** The coloured mouth that finishes a breakout, tinted per face exactly as a plate is. */
	private final IBakedModel[][] mouths;

	ConduitJunctionModel(IBakedModel[] housings, IBakedModel[][] plates, IBakedModel[][] stubs,
						 IBakedModel[][] shortStubs, IBakedModel[][] mouths)
	{
		System.arraycopy(housings, 0, this.housings, 0, housings.length);
		this.plates = plates;
		this.stubs = stubs;
		this.shortStubs = shortStubs;
		this.mouths = mouths;
	}

	@Nonnull
	@Override
	public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand)
	{
		//A box with nothing around it sits on the floor of its cell, which is what one with no runs
		//has always looked like -- and is also the right answer for a state that arrived here without
		//a tile entity behind it.
		int shape = EnumFacing.DOWN.ordinal();
		int patched = 0;
		if(state instanceof IExtendedBlockState)
		{
			TileEntity te = ((IExtendedBlockState)state).getValue(IEProperties.TILEENTITY_PASSTHROUGH);
			if(te instanceof TileEntityJunctionBox)
			{
				shape = ((TileEntityJunctionBox)te).getRenderShape();
				patched = ((TileEntityJunctionBox)te).getPatchMask();
			}
		}
		int key = shape|patched << 9;
		List<BakedQuad>[] lists = modelCache.get(key);
		if(lists==null)
		{
			lists = ConduitParts.compose(assemble(shape, patched));
			modelCache.put(key, lists);
		}
		return ConduitParts.forSide(lists, side);
	}

	/**
	 * The pieces a box of that shape is made of, in the order the blockstate used to apply them.
	 *
	 * @param shape   as {@link TileEntityJunctionBox#getRenderShape} packs it: mount in bits 0-2,
	 *                joined faces in bits 3-8
	 * @param patched one bit per patched face
	 */
	private List<IBakedModel> assemble(int shape, int patched)
	{
		EnumFacing mount = EnumFacing.byIndex(shape&7);
		List<IBakedModel> parts = new ArrayList<>();
		parts.add(housings[mount.ordinal()]);
		for(EnumFacing face : EnumFacing.VALUES)
		{
			int m = mount.ordinal(), f = face.ordinal();
			boolean isPatched = (patched&(1 << f))!=0;
			//No stub on the face the housing is bolted to: it already reaches the block edge there,
			//and a zero-thickness box is a model that parses and draws nothing. There is deliberately
			//no file for it either -- see build_run_stub_models in the asset script. A conductor
			//broken out on that face -- which takes a dye on a face nobody can click, so in practice
			//only a save that predates this -- keeps the flat plate it always had.
			if(stubs[m][f]==null)
			{
				if(isPatched)
					parts.add(plates[m][f]);
				continue;
			}
			//**A breakout is a breakout: it reaches the block edge.** A patched face grows its stub
			//whether or not a run joins there, and wears the conductor's colour on the last two
			//pixels of it -- which is what a connector or a block bolted against that face meets,
			//instead of the three-pixel gap a playtester found beside every one of them.
			if(isPatched)
			{
				parts.add(shortStubs[m][f]);
				parts.add(mouths[m][f]);
			}
			else if((shape&(1 << (3+f)))!=0)
				parts.add(stubs[m][f]);
		}
		return parts;
	}

	@Override
	public boolean isAmbientOcclusion()
	{
		//False, matching what the box has always reported: its housing is drawn through
		//ConnModelReal, which says false, and a multipart model takes the answer from its first part.
		return false;
	}

	@Override
	public boolean isGui3d()
	{
		return false;
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
		return housings[EnumFacing.DOWN.ordinal()].getParticleTexture();
	}

	@Nonnull
	@Override
	public ItemCameraTransforms getItemCameraTransforms()
	{
		return housings[EnumFacing.DOWN.ordinal()].getItemCameraTransforms();
	}

	@Nonnull
	@Override
	public ItemOverrideList getOverrides()
	{
		//The item never reaches this model -- conduit.json points the box's inventory variant at the
		//floor-mounted housing, because an item has no runs to pick a plane from.
		return ItemOverrideList.NONE;
	}
}
