/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry.ArmMode;
import blusunrize.immersiveengineering.common.blocks.conduit.TileEntityConduit;
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
 * Drawing a length of conduit: a hub against the mounting face, plus one arm per joined direction in
 * whichever of its three forms that arm has.
 * <p>
 * <strong>Exactly what the multipart blockstate did, one step later.</strong> The same seventy-eight
 * generated models are assembled in the same combinations; the difference is where the combination
 * comes from. It used to come from twelve boolean block properties, and Forge builds the cartesian
 * product of every listed property at startup and gives each of the resulting states a
 * {@code ModelResourceLocation} -- so a block with seventy-eight pieces of geometry in it was
 * costing 73,728 states and 73,728 model references, every one of them resolved through the
 * multipart selectors at load. Reading the three arm masks off the tile entity through
 * {@code IEProperties.TILEENTITY_PASSTHROUGH} instead leaves the block with type times facing:
 * eighteen.
 * <p>
 * <strong>The assembly is cached, and it has to be.</strong> {@code getQuads} runs on vanilla's
 * chunk-render workers, once per side per block; concatenating up to five quad lists there for every
 * length of conduit in a corridor would be exactly the per-frame allocation the whole feature was
 * designed to avoid. A run's appearance is entirely decided by its mount and its three arm masks, so
 * that is the key -- fifteen bits, and a base uses a handful of the combinations.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitRunModel implements IBakedModel
{
	/**
	 * Assembled quad lists, keyed by {@link #key}.
	 * <p>
	 * Concurrent because it is filled from {@code getQuads}, which runs on the chunk-render worker
	 * pool -- one thread per chunk being compiled. Cleared through {@code IEApi.renderCacheClearers}
	 * on a resource reload, since every quad in here holds a {@code TextureAtlasSprite} that a
	 * re-stitch has invalidated.
	 */
	public static final Map<Integer, List<BakedQuad>[]> modelCache = new ConcurrentHashMap<>();

	/** One hub per surface a conduit can be clipped to, indexed by {@code EnumFacing.ordinal()}. */
	private final IBakedModel[] hubs = new IBakedModel[EnumFacing.VALUES.length];

	/**
	 * The arms, indexed by mount ordinal and then by
	 * {@code armIndex*3 + ArmMode.ordinal()-1} -- straight, riser, wrap. {@link ArmMode#NONE} has no
	 * model, which is what the {@code -1} is for.
	 */
	private final IBakedModel[][] arms = new IBakedModel[EnumFacing.VALUES.length][];

	ConduitRunModel(IBakedModel[] hubs, IBakedModel[][] arms)
	{
		System.arraycopy(hubs, 0, this.hubs, 0, hubs.length);
		System.arraycopy(arms, 0, this.arms, 0, arms.length);
	}

	/** Where one arm's three forms sit in the per-mount array. */
	static int armSlot(int armIndex, ArmMode mode)
	{
		return armIndex*3+mode.ordinal()-1;
	}

	private static int key(EnumFacing mount, int connections, int risers, int wraps)
	{
		return mount.ordinal()|connections << 3|risers << 7|wraps << 11;
	}

	@Nonnull
	@Override
	public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand)
	{
		//The listed facing first, so a conduit still draws its own hub in the one case where there is
		//no tile entity to ask -- a block being rendered as it is broken, or a state handed here by
		//something that never went through getExtendedState.
		EnumFacing mount = EnumFacing.DOWN;
		if(state!=null&&state.getPropertyKeys().contains(IEProperties.FACING_ALL))
			mount = state.getValue(IEProperties.FACING_ALL);
		TileEntityConduit conduit = null;
		if(state instanceof IExtendedBlockState)
		{
			TileEntity te = ((IExtendedBlockState)state).getValue(IEProperties.TILEENTITY_PASSTHROUGH);
			if(te instanceof TileEntityConduit)
			{
				conduit = (TileEntityConduit)te;
				mount = conduit.facing;
			}
		}
		int connections = conduit==null?0: conduit.getConnections();
		int risers = conduit==null?0: conduit.getRisers();
		int wraps = conduit==null?0: conduit.getWraps();

		int key = key(mount, connections, risers, wraps);
		List<BakedQuad>[] lists = modelCache.get(key);
		if(lists==null)
		{
			lists = ConduitParts.compose(assemble(mount, conduit));
			modelCache.put(key, lists);
		}
		return ConduitParts.forSide(lists, side);
	}

	/**
	 * The pieces a conduit on that surface is made of, in the order the blockstate used to apply
	 * them: the hub, then each joined arm.
	 * <p>
	 * The arm's form is asked of {@link TileEntityConduit#armMode} rather than worked out from the
	 * masks here, so there is one statement of what a riser is and one of what a wrap is -- see
	 * {@code ConduitArms.mode}. Two copies of that rule is how an arm ends up drawn as a straight
	 * length over a riser, which looks like a modelling mistake and is not one.
	 */
	private List<IBakedModel> assemble(EnumFacing mount, @Nullable TileEntityConduit conduit)
	{
		List<IBakedModel> parts = new ArrayList<>();
		parts.add(hubs[mount.ordinal()]);
		if(conduit==null)
			return parts;
		EnumFacing[] plane = ConduitGeometry.inPlane(mount);
		for(int i = 0; i < plane.length; i++)
		{
			ArmMode mode = conduit.armMode(plane[i]);
			if(mode!=ArmMode.NONE)
				parts.add(arms[mount.ordinal()][armSlot(i, mode)]);
		}
		return parts;
	}

	@Override
	public boolean isAmbientOcclusion()
	{
		//True, as the generated JSON models are: a conduit is opaque tubing clipped to a wall, and
		//without this the shading along a long run would be flat.
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
		//The hub's, which is the conduit tile: every piece of the block is textured with it, so
		//whichever one a player breaks the particles are right.
		return hubs[EnumFacing.DOWN.ordinal()].getParticleTexture();
	}

	@Nonnull
	@Override
	public ItemCameraTransforms getItemCameraTransforms()
	{
		return hubs[EnumFacing.DOWN.ordinal()].getItemCameraTransforms();
	}

	@Nonnull
	@Override
	public ItemOverrideList getOverrides()
	{
		//The item never reaches this model -- conduit.json points the run's inventory variant at
		//conduit_item, a straight length lying on the floor, because an item has no surroundings for
		//its arms to be joined to.
		return ItemOverrideList.NONE;
	}
}
