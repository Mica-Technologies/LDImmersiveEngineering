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
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.property.IExtendedBlockState;

import javax.annotation.Nullable;

/**
 * Surface-mounted conduit: the indoor counterpart to IE's catenary wires.
 * <p>
 * A wire sags between two points, which is right across a valley and wrong along a ceiling. This
 * lies flat against a face, turns in right angles and wraps around the corners of whatever it is
 * clipped to, and that is the entire reason it exists.
 * <p>
 * The block is drawn out of static geometry -- a hub against the mounting face plus one arm per
 * joined direction, in whichever of its three forms that arm has -- rather than by a renderer.
 * Seventy-eight small models, all axis-aligned boxes generated alongside the texture, and nothing
 * drawn per frame. A catenary renderer would be both the wrong shape and far more expensive.
 * <p>
 * Which of those pieces to assemble is read off the tile entity by a smart model rather than spelled
 * out in listed block properties -- see the constructor. That is why this block has eighteen states
 * and not seventy-three thousand.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class BlockConduit extends BlockIETileProvider<BlockTypes_Conduit> implements IColouredBlock
{
	public BlockConduit()
	{
		//	=================================
		//	Two listed properties, and that is deliberate
		//	=================================
		//Type times facing: eighteen states, and Forge builds every one of them at startup. It used
		//to build 73,728, because twelve per-face booleans were declared here as well -- six saying
		//which of a run's arms crossed a cell boundary and six saying which turned a corner, doubling
		//as "this face is patched" and "a run touches this face" on a junction box. Every one of
		//those states also earns a ModelResourceLocation of its own through IECustomStateMapper, so
		//the cost was 73,728 model references for a block with seventy-eight pieces of geometry in it.
		//
		//All twelve now reach the renderer through TILEENTITY_PASSTHROUGH and a smart model instead
		//-- see ConduitRunModel and ConduitJunctionModel. Nothing about how any of it draws changed;
		//the same part models are assembled from the same three arm masks, one step later.
		super("conduit", Material.IRON, PropertyEnum.create("type", BlockTypes_Conduit.class),
				ItemBlockIEBase.class, IEProperties.FACING_ALL,
				//Unlisted, and how all three metas find out what they are supposed to look like: the
				//shape lives on the tile entity, not in the state. The feeder's disguise, the run's
				//arm masks and the box's mount, patches and stubs all arrive this way.
				IEProperties.TILEENTITY_PASSTHROUGH,
				//Also unlisted, and what makes a wire strung to a junction box visible. Without it
				//getExtendedState has nowhere to put the connection set -- the property has to be
				//declared before it can be filled -- and the far end draws only its own half of the
				//catenary, which is the "wires with holes in" symptom rather than a missing wire.
				IEProperties.CONNECTIONS);
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
		//	=================================
		//	The junction box draws in SOLID, not CUTOUT
		//	=================================
		//Because a wire strung to a box has to be drawn, and the connection model only emits catenary
		//quads during the SOLID and TRANSLUCENT passes -- a block that declared CUTOUT alone would
		//never be asked for them and its wires would simply not be there.
		//
		//Moved rather than added, which is the part that matters: the box is drawn by a *multipart*
		//blockstate, and every layer a block declares is a second pass over every part of it. Adding
		//SOLID alongside CUTOUT -- what the Grid Feed and Service Units do, where one model can be
		//told which layer its own quads belong in -- would draw the housing, all six patch plates and
		//every run stub twice. Every texture the box is made of is fully opaque, so CUTOUT was buying
		//nothing here in the first place.
		this.setMetaBlockLayer(BlockTypes_Conduit.JUNCTION_BOX.getMeta(), BlockRenderLayer.SOLID);
	}

	@Override
	public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos)
	{
		state = super.getExtendedState(state, world, pos);
		//BlockIETileProvider fills CONNECTIONS for a TileEntityImmersiveConnectable, which a junction
		//box deliberately is not -- it is a patch panel, a redstone node and a flux receiver built on
		//TileEntityIEBase, and it is a wire endpoint as well. Filled here, the way BlockConnector and
		//BlockClothDevice fill it for their own tiles.
		TileEntity tile = world.getTileEntity(pos);
		if(state instanceof IExtendedBlockState&&tile instanceof TileEntityJunctionBox)
			state = ((IExtendedBlockState)state).withProperty(IEProperties.CONNECTIONS,
					((TileEntityJunctionBox)tile).genConnBlockstate());
		return state;
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

	//	=================================
	//	There is no getActualState override any more
	//	=================================
	//BlockIETileProvider still fills FACING_ALL for a run, because TileEntityConduit is an
	//IDirectionalTile and that is the one listed property left with anything to say. Everything else
	//that used to be derived here -- twelve booleans per block, and for a junction box six tile
	//lookups to work out which faces a run touched -- is read straight off the tile entity by the
	//smart models now. getActualState is asked once per block per chunk rebuild and once per
	//getStateForPlacement and once per anything that wants a block's real shape, so the six lookups
	//were being paid for by every one of those callers rather than by the one that needed them.
	//
	//A box no longer fills FACING_ALL at all. It never had a facing of its own -- the mount is
	//derived from the runs that reach it, in ConduitGeometry.junctionBoxMount -- and the only reader
	//was the multipart blockstate that used to pick between six housing models. ConduitJunctionModel
	//asks TileEntityJunctionBox.getRenderShape for the same answer instead.

	/**
	 * Let a conduit work out its own shape on the client as well as on the server.
	 * <p>
	 * <strong>A second net under the "draws disconnected until you come back" bug.</strong> The
	 * primary fix is in {@code TileEntityConduit.onDataPacket}, which redraws when the arms a
	 * description packet carries differ from the ones already there. This covers the case where no
	 * such packet arrives at all: the client's copy of a conduit can see its neighbours perfectly
	 * well -- every rule in {@code refreshConnections} is arithmetic on what is next door, with no
	 * server-only state anywhere in it -- so there is no reason for it to sit waiting to be told.
	 * <p>
	 * The server stays authoritative: it saves the arms, and its packets overwrite whatever the
	 * client worked out. This only means the client is never <em>stuck</em> on a shape that the
	 * blocks around it plainly contradict, which is the whole of what a player was walking away and
	 * back to cure.
	 * <p>
	 * Server side this does nothing but defer to IE, which routes the change through
	 * {@code INeighbourChangeTile} on a future task -- see {@code BlockIETileProvider}.
	 */
	@Override
	public void neighborChanged(IBlockState state, World world, BlockPos pos, Block block, BlockPos fromPos)
	{
		super.neighborChanged(state, world, pos, block, fromPos);
		if(!world.isRemote)
			return;
		TileEntity tile = world.getTileEntity(pos);
		if(tile instanceof TileEntityConduit&&((TileEntityConduit)tile).refreshConnections())
			world.markBlockRangeForRenderUpdate(pos, pos);
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
