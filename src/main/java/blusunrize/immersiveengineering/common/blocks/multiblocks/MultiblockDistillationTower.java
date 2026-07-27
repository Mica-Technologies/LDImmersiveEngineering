/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.multiblocks;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.MultiblockHandler;
import blusunrize.immersiveengineering.api.MultiblockHandler.IMultiblock;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import blusunrize.immersiveengineering.client.ClientUtils;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsAll;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityDistillationTower;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The Distillation Tower: a twelve-block column on a four-by-four deck, drawn off at heights
 * matching the order of the cuts.
 * <p>
 * The shape is three parts. A two-by-two steel shell runs the full height -- that is the vessel.
 * A scaffolding deck rings its foot, which is where crude goes in and where the power and
 * redstone connections live. And at each of the seven draw heights, four pairs of Oilfield Frame
 * stick out of the shell as nozzles, one pair per face.
 * <p>
 * Those nozzles are the whole point of the machine. Their heights come from
 * {@link #drawHeight(int)}, the same function the tile entity uses to decide which tank a face
 * can be drained from, so the blocks that visibly stick out are exactly the blocks a pipe can
 * take a cut from, and the two can never drift apart. A player who can see the tower knows where
 * to plumb it; a player who plumbs the wrong height gets nothing, which is the intended puzzle.
 * <p>
 * As with the derrick, the shape is a pure predicate ({@link #isPart}) and the {@code ItemStack}
 * template is built on first use rather than in a static initialiser, so the part that fails
 * silently -- "hammering does nothing, and says nothing" -- stays directly testable.
 * <p>
 * The footprint is symmetric across both axes, so there is no mirrored variant to check for.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockDistillationTower implements IMultiblock
{
	public static final MultiblockDistillationTower instance = new MultiblockDistillationTower();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.TOWER_SIZE;
	private static final int HEIGHT = PetroleumGeometry.TOWER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.TOWER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.TOWER_WIDTH;

	/**
	 * How many cuts the column can draw off, and therefore how many nozzle heights it has and how
	 * many output tanks the tile entity keeps. Seven is what the shipped crude recipe yields; a
	 * recipe declaring more cuts than this has nowhere to put the extra ones and is refused rather
	 * than silently truncated.
	 */
	public static final int CUT_COUNT = 7;
	/**
	 * Crude goes in at the deck, at the foot of the column. It is the one layer that fills rather
	 * than drains, which keeps a pipe run from ever having to both feed and tap the same face.
	 */
	public static final int FEED_LAYER = 0;
	/**
	 * The lightest cut leaves at the very top, as it does in a real column.
	 */
	public static final int TOP_PORT = HEIGHT-1;
	/**
	 * The residue leaves immediately above the feed deck rather than through it.
	 */
	public static final int BOTTOM_PORT = FEED_LAYER+1;

	/**
	 * The height the given cut is drawn off at.
	 * <p>
	 * Cuts arrive from the recipe lightest first, and the ports run from {@link #TOP_PORT} down to
	 * {@link #BOTTOM_PORT} in that same order, spread as evenly as whole blocks allow. Spreading
	 * them rather than stacking them contiguously is deliberate: ports two blocks apart leave room
	 * for a pipe to reach one without brushing its neighbours, and a column with gaps in it reads
	 * as a column rather than as a ladder.
	 *
	 * @param cut index into the recipe's cuts, 0 being the lightest
	 * @return the layer of the structure that cut is tapped from, or -1 if there is no such cut
	 */
	public static int drawHeight(int cut)
	{
		if(cut < 0||cut >= CUT_COUNT)
			return -1;
		int span = TOP_PORT-BOTTOM_PORT;
		int steps = CUT_COUNT-1;
		//Rounded rather than truncated, so the ports sit symmetrically about the middle of the
		//column instead of bunching towards the bottom.
		return TOP_PORT-(cut*span+steps/2)/steps;
	}

	/**
	 * @return the cut drawn off at this height, or -1 if the layer is plain shell
	 */
	public static int cutAtHeight(int height)
	{
		for(int cut = 0; cut < CUT_COUNT; cut++)
			if(drawHeight(cut)==height)
				return cut;
		return -1;
	}

	/**
	 * @return whether this cell is part of the vessel itself, the column of shell that runs the
	 * full height
	 */
	public static boolean isColumn(int l, int w)
	{
		return l > 0&&l < DEPTH-1&&w > 0&&w < WIDTH-1;
	}

	/**
	 * A nozzle cell: against one outer face of the box, but not on a corner. Corners are left open
	 * so each face's pair of nozzles reads as belonging to that face, and so a pipe run climbing
	 * one corner of the tower never fouls a port it was not aiming at.
	 */
	public static boolean isNozzle(int l, int w)
	{
		return isOuter(l, DEPTH)!=isOuter(w, WIDTH);
	}

	private static boolean isOuter(int i, int size)
	{
		return i==0||i==size-1;
	}

	/**
	 * @return whether the structure occupies this cell of its {@code H x L x W} box
	 */
	public static boolean isPart(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return false;
		if(isColumn(l, w))
			return true;
		if(h==FEED_LAYER)
			return true;
		return isNozzle(l, w)&&cutAtHeight(h) >= 0;
	}

	/**
	 * @return how many blocks a complete tower is built from
	 */
	public static int blockCount()
	{
		int count = 0;
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(isPart(h, l, w))
						count++;
		return count;
	}

	private static final String ORE_SHELL = "blockSheetmetalSteel";
	private static final String ORE_DECK = "scaffoldingSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:DistillationTower";
	}

	//	=================================
	//		MANUAL
	//	=================================

	private ItemStack[][][] structure;

	@Override
	public ItemStack[][][] getStructureManual()
	{
		if(structure==null)
		{
			structure = new ItemStack[HEIGHT][DEPTH][WIDTH];
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						if(isPart(h, l, w))
							structure[h][l][w] = isColumn(l, w)?shell(1)
									: h==FEED_LAYER?deck(1): nozzle(1);
		}
		return structure;
	}

	private IngredientStack[] materials;

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		if(materials==null)
		{
			int shell = 0;
			int deck = 0;
			int nozzles = 0;
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
						if(isPart(h, l, w))
						{
							if(isColumn(l, w))
								shell++;
							else if(h==FEED_LAYER)
								deck++;
							else
								nozzles++;
						}
			materials = new IngredientStack[]{
					new IngredientStack(ORE_SHELL, shell),
					new IngredientStack(ORE_DECK, deck),
					new IngredientStack(nozzle(nozzles))};
		}
		return materials;
	}

	private static ItemStack shell(int count)
	{
		return new ItemStack(IEContent.blockSheetmetal, count, BlockTypes_MetalsAll.STEEL.getMeta());
	}

	private static ItemStack deck(int count)
	{
		return new ItemStack(IEContent.blockMetalDecoration1, count,
				BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
	}

	private static ItemStack nozzle(int count)
	{
		return new ItemStack(IEContent.blockPetroleumDevice, count,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean overwriteBlockRender(ItemStack stack, int iterator)
	{
		return false;
	}

	@Override
	public float getManualScale()
	{
		return 6;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		GlStateManager.translate(2, 6, 2);
		ClientUtils.mc().getRenderItem().renderItem(
				new ItemStack(IEContent.blockPetroleumMultiblock, 1,
						BlockTypes_PetroleumMultiblock.DISTILLATION_TOWER.getMeta()),
				ItemCameraTransforms.TransformType.GUI);
	}

	//	=================================
	//		FORMATION
	//	=================================

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		//The nozzles, and not the shell: sheetmetal and scaffolding are everywhere in a built-up
		//base, and triggering a hundred-odd block check on every hammered sheet would cost more
		//than the structure is worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A column is hammered from a walkway or the ground, so the clicked face is normally a side.
		//Falling back to where the player is standing means clicking a nozzle from above still
		//works rather than being a silent no-op.
		EnumFacing facing = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw).getOpposite(): side;

		//Only the nozzles are frame, so those are the only cells the clicked block can be. That is
		//fifty-six candidates rather than the full box, and every one that is not a nozzle height
		//is skipped before a single block lookup happens.
		for(int h = 0; h < HEIGHT; h++)
		{
			if(cutAtHeight(h) < 0)
				continue;
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isNozzle(l, w))
						continue;
					BlockPos origin = pos.add(0, -h, 0).offset(facing, -l).offset(facing.rotateY(), -w);
					if(matches(world, origin, facing))
						return form(world, origin, facing, player);
				}
		}
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing facing)
	{
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					if(isColumn(l, w))
					{
						if(!Utils.isOreBlockAt(world, target, ORE_SHELL))
							return false;
					}
					else if(h==FEED_LAYER)
					{
						if(!Utils.isOreBlockAt(world, target, ORE_DECK))
							return false;
					}
					else if(!Utils.isBlockAt(world, target, IEContent.blockPetroleumDevice,
							BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta()))
						return false;
				}
		return true;
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code TileEntityMultiblockPart} does,
	 * so that {@code getBlockPosForPos} agrees with what was placed here.
	 */
	private static BlockPos cell(BlockPos origin, EnumFacing facing, int h, int l, int w)
	{
		return origin.offset(facing, l).offset(facing.rotateY(), w).add(0, h, 0);
	}

	private boolean form(World world, BlockPos origin, EnumFacing facing, EntityPlayer player)
	{
		ItemStack hammer = player.getHeldItemMainhand().getItem()
				.getToolClasses(player.getHeldItemMainhand()).contains(Lib.TOOL_HAMMER)
				?player.getHeldItemMainhand(): player.getHeldItemOffhand();
		if(MultiblockHandler.fireMultiblockFormationEventPost(player, this, origin, hammer).isCanceled())
			return false;

		IBlockState state = IEContent.blockPetroleumMultiblock
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.DISTILLATION_TOWER.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityDistillationTower)
					{
						TileEntityDistillationTower part = (TileEntityDistillationTower)te;
						part.formed = true;
						part.facing = facing;
						part.pos = PetroleumGeometry.structureIndex(SIZE, h, l, w);
						part.offset = new int[]{
								target.getX()-origin.getX(),
								target.getY()-origin.getY(),
								target.getZ()-origin.getZ()};
						part.markDirty();
						world.addBlockEvent(target, IEContent.blockPetroleumMultiblock, 255, 0);
					}
				}
		return true;
	}
}
