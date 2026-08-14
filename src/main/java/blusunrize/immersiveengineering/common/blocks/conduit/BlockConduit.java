/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import blusunrize.immersiveengineering.common.blocks.BlockIETileProvider;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IColouredBlock;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Surface-mounted conduit: the indoor counterpart to IE's catenary wires.
 * <p>
 * A wire sags between two points, which is right across a valley and wrong along a ceiling. This
 * lies flat against a face and turns in right angles, and that is the entire reason it exists.
 * <p>
 * The block is drawn by an ordinary multipart blockstate -- a hub against the mounting face plus
 * one arm per joined direction -- rather than by a renderer. Thirty small models, all axis-aligned
 * boxes generated alongside the texture, and nothing drawn per frame. A catenary renderer would be
 * both the wrong shape and far more expensive.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class BlockConduit extends BlockIETileProvider<BlockTypes_Conduit> implements IColouredBlock
{
	public BlockConduit()
	{
		super("conduit", Material.IRON, PropertyEnum.create("type", BlockTypes_Conduit.class),
				ItemBlockIEBase.class, IEProperties.FACING_ALL,
				IEProperties.SIDECONNECTION[0], IEProperties.SIDECONNECTION[1],
				IEProperties.SIDECONNECTION[2], IEProperties.SIDECONNECTION[3],
				IEProperties.SIDECONNECTION[4], IEProperties.SIDECONNECTION[5],
				//The junction box's own view of the same six faces -- which ones a run physically
				//touches, rather than which ones are patched. See RUNCONNECTION's own comment.
				IEProperties.RUNCONNECTION[0], IEProperties.RUNCONNECTION[1],
				IEProperties.RUNCONNECTION[2], IEProperties.RUNCONNECTION[3],
				IEProperties.RUNCONNECTION[4], IEProperties.RUNCONNECTION[5],
				//Unlisted, and the only way the ground feeder's model can find out what it is
				//supposed to look like: the disguise lives on the tile entity, not in the state.
				IEProperties.TILEENTITY_PASSTHROUGH);
		this.setHardness(2.0F);
		this.setResistance(10.0F);
		this.lightOpacity = 0;
		for(BlockTypes_Conduit type : BlockTypes_Conduit.values())
		{
			//The ground feeder is the exception to every line in this loop, because it is the one
			//meta that is a whole cube: it fills its block, it blocks light, it suffocates, and
			//neighbouring blocks may cull their faces against it. All of that has to be true or a
			//feeder set into a floor would be a lit, see-through hole in the shape of a dirt block.
			if(type==BlockTypes_Conduit.GROUND_FEEDER)
				continue;
			this.setNotNormalBlock(type.getMeta());
			this.setMetaBlockLayer(type.getMeta(), BlockRenderLayer.CUTOUT);
		}
		//A feeder wears whatever is around it, and which layer that block draws in is not knowable
		//until somebody places one -- grass is cutout-mipped, stone is solid. So it offers itself in
		//all three and the model draws nothing in the layers its disguise does not use. Not
		//translucent: it can only wear opaque cubes, so that pass would never have anything in it.
		this.setMetaBlockLayer(BlockTypes_Conduit.GROUND_FEEDER.getMeta(), BlockRenderLayer.SOLID,
				BlockRenderLayer.CUTOUT_MIPPED, BlockRenderLayer.CUTOUT);
		//lightOpacity above is set for the whole block and is right for tubing. A feeder is a hole
		//somebody filled in, so it stops light like the floor it is part of.
		this.setMetaLightOpacity(BlockTypes_Conduit.GROUND_FEEDER.getMeta(), 255);
	}

	@Override
	public boolean useCustomStateMapper()
	{
		return true;
	}

	@Override
	public String getCustomStateMapping(int meta, boolean itemBlock)
	{
		//The block is described by a multipart blockstate, which cannot also carry the
		//`inventory,...` variant the item model is looked up through -- so the two live in
		//separate files and this is what points the block half at conduit_run.json. Returning
		//null for the item leaves it resolving against conduit.json, exactly as IE's fences do.
		//Both files have to exist: a custom mapping with no matching file is one of the two
		//silent causes of a purple block in 1.12, and neither reports an error.
		if(itemBlock)
			return null;
		//One file per meta. The box and the feeder are drawn by nothing the run's multipart could
		//express -- one has coloured plates per patched face, the other has no fixed model at all.
		if(meta==BlockTypes_Conduit.JUNCTION_BOX.getMeta())
			return "junction_box";
		if(meta==BlockTypes_Conduit.GROUND_FEEDER.getMeta())
			return "ground_feeder";
		return "run";
	}

	@Override
	public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos)
	{
		state = super.getActualState(state, world, pos);
		TileEntity tile = world.getTileEntity(pos);
		if(tile instanceof TileEntityConduit)
		{
			TileEntityConduit conduit = (TileEntityConduit)tile;
			//Written out in absolute facings rather than as arm indices: the blockstate file reads far
			//better as "sideconnection_north" than as "arm2", and the mapping between the two is
			//exactly what ConduitGeometry.armIndex is for.
			for(EnumFacing side : EnumFacing.VALUES)
				state = applyProperty(state, IEProperties.SIDECONNECTION[side.ordinal()],
						conduit.isConnected(side));
			return state;
		}
		if(tile instanceof TileEntityJunctionBox)
		{
			//The same six SIDECONNECTION properties the run uses, carrying "this face is patched"
			//here rather than "this face is joined". Two meanings for one property is worth a second
			//look, but the two block types are drawn by different blockstate files and neither can
			//see the other's, so there is nowhere for them to be confused.
			ConduitPatch patch = ((TileEntityJunctionBox)tile).getPatch();
			for(EnumFacing side : EnumFacing.VALUES)
				state = applyProperty(state, IEProperties.SIDECONNECTION[side.ordinal()],
						patch.isPatched(side));
			//The box's own idea of which faces a run actually touches -- see
			//ConduitGeometry.joinsJunctionBox for why this cannot be read off the patch table: a run
			//passing through unconfigured is exactly the common case, and its faces still need to
			//meet the box's model rather than leave a gap where a conduit's arm reaches flush.
			for(EnumFacing side : EnumFacing.VALUES)
			{
				TileEntity neighbour = world.getTileEntity(pos.offset(side));
				EnumFacing neighbourMount = neighbour instanceof TileEntityConduit
						?((TileEntityConduit)neighbour).facing: null;
				EnumFacing.Axis feederAxis = neighbour instanceof TileEntityGroundFeeder
						?((TileEntityGroundFeeder)neighbour).getAxis(): null;
				state = applyProperty(state, IEProperties.RUNCONNECTION[side.ordinal()],
						ConduitGeometry.joinsJunctionBox(side, neighbourMount, feederAxis));
			}
		}
		return state;
	}

	//	=================================
	//		PATCH COLOURS
	//	=================================

	/**
	 * A patched face wears a plate in its conductor's colour.
	 * <p>
	 * Before this the box was a featureless lid: patching it changed nothing you could see, so the
	 * only way to find out which colour was on which face -- or whether the box was configured at
	 * all -- was to point at each of the six in turn and read the overlay. Configuring something
	 * invisible is most of what made the feature feel fiddly to the first person who built with it.
	 * <p>
	 * Tinted rather than modelled per colour. The plate is painted near-white and the dye arrives
	 * here at render time, which is six models instead of ninety-six.
	 */
	@Override
	public boolean hasCustomBlockColours()
	{
		return true;
	}

	@Override
	public int getRenderColour(IBlockState state, @Nullable IBlockAccess world, @Nullable BlockPos pos,
							   int tintIndex)
	{
		//White is the identity for a multiply, so anything this does not recognise comes out as the
		//texture painted it -- which is the right answer for the box itself, the conduit run, and
		//the item in a player's hand, none of which pass a face index.
		if(world==null||pos==null||tintIndex < 0||tintIndex >= EnumFacing.VALUES.length)
			return 0xffffff;
		TileEntity tile = world.getTileEntity(pos);
		if(!(tile instanceof TileEntityJunctionBox))
			return 0xffffff;
		//The tint index is the face's ordinal, which is how the generated models are numbered.
		WireChannel channel = ((TileEntityJunctionBox)tile).getPatch().get(EnumFacing.byIndex(tintIndex));
		return channel==null?0xffffff: channel.getColour();
	}

	@Override
	public boolean isSideSolid(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side)
	{
		//A feeder is a whole cube and has to report so: it is part of a floor, things stand on it,
		//and -- the reason it matters here -- ConduitPlacement asks exactly this question to decide
		//whether there is a surface for a conduit to clip to. A feeder that answered false would be
		//a floor with a conduit-shaped hole nothing could be mounted in.
		return getMetaFromState(state)==BlockTypes_Conduit.GROUND_FEEDER.getMeta();
	}

	@Override
	public boolean allowHammerHarvest(IBlockState state)
	{
		return true;
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_Conduit type)
	{
		switch(type)
		{
			case JUNCTION_BOX:
				return new TileEntityJunctionBox();
			case GROUND_FEEDER:
				return new TileEntityGroundFeeder();
			default:
				return new TileEntityConduit();
		}
	}

	@Override
	public void breakBlock(World world, BlockPos pos, IBlockState state)
	{
		//Drop the runs with the box. Without this the wire graph keeps edges to a block that is no
		//longer there, and the next thing to walk them finds nothing at the far end.
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityJunctionBox)
			((TileEntityJunctionBox)te).onBlockBroken();
		super.breakBlock(world, pos, state);
	}

	@Override
	public boolean hasTileEntity(IBlockState state)
	{
		return true;
	}
}
