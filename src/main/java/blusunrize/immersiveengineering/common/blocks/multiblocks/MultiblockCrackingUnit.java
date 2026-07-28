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
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityCrackingUnit;
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
 * The Cracking Unit: two reactor columns straddling a coke drum, on a scaffolding deck.
 * <p>
 * Wide rather than tall, deliberately. It will nearly always be built next to the Distillation
 * Tower it takes its feed from, and two tall square columns side by side would read as one
 * building; a squat, broad machine beside a slim, tall one reads as a refinery.
 * <p>
 * <strong>The product faces are on opposite sides of the head.</strong> Gasoline leaves along the
 * front row, diesel along the back, and the row between them connects to nothing. Two fluids on one
 * face would make the split unenforceable -- a mistake this expansion has already made once -- and
 * putting them front and back rather than left and right means a player standing where the feed
 * goes in can see both without walking round.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockCrackingUnit implements IMultiblock
{
	public static final MultiblockCrackingUnit instance = new MultiblockCrackingUnit();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.CRACKER_SIZE;
	private static final int HEIGHT = PetroleumGeometry.CRACKER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.CRACKER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.CRACKER_WIDTH;

	/**
	 * The deck the whole thing stands on, and the course the feed goes into.
	 */
	public static final char DECK = 'D';
	/**
	 * The reactor columns.
	 */
	public static final char VESSEL = 'V';
	/**
	 * Oilfield frame: the coke drum up the middle and the product offtakes across the head.
	 */
	public static final char FRAME = 'F';
	/**
	 * The gaps between the columns, which are not part of the machine.
	 */
	public static final char EMPTY = '.';

	/**
	 * {@code SHAPE[height][depth]}, one character per width, depth 0 being the front face -- where
	 * the gasoline comes out and where the coke drops.
	 */
	private static final String[][] SHAPE = {
			//The deck. Solid: it carries two reactors and a coke drum, and a machine on legs does
			//not read as something that holds pressure.
			{"DDDDD", "DDDDD", "DDDDD"},
			//Four courses of reactor either side of the coke drum. The drum is frame rather than
			//vessel so the silhouette has something in the middle of it.
			{"V.F.V", "V.F.V", "V.F.V"},
			{"V.F.V", "V.F.V", "V.F.V"},
			{"V.F.V", "V.F.V", "V.F.V"},
			{"V.F.V", "V.F.V", "V.F.V"},
			//The head: offtakes across the whole course, front row and back row carrying the two
			//products and the middle row carrying nothing at all.
			{"FFFFF", "FFFFF", "FFFFF"}};

	/**
	 * Master: the front-left corner of the deck, and the origin the structure is laid out from.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, 0);
	/**
	 * The middle of the deck's front face. Petcoke is pushed into whatever inventory stands against
	 * this block, so the machine has one obvious place to put a chest.
	 */
	public static final int COKE_OUTLET_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, WIDTH/2);

	public static char shapeAt(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return EMPTY;
		return SHAPE[h][l].charAt(w);
	}

	public static boolean isPart(int h, int l, int w)
	{
		return shapeAt(h, l, w)!=EMPTY;
	}

	public static int blockCount(char material)
	{
		int count = 0;
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(SHAPE[h][l].charAt(w)==material)
						count++;
		return count;
	}

	//	=================================
	//		PORTS
	//	=================================

	/**
	 * @return whether feed may be pushed into this part. The whole deck, as the scrubber's is:
	 * a feed line that has to find one exact block of a fifty-eight block machine is a feed line
	 * the player gets wrong.
	 */
	public static boolean isFeedPort(int pos)
	{
		return pos >= 0&&PetroleumGeometry.heightOf(SIZE, pos)==0;
	}

	/**
	 * @return whether the light product may be drawn from this part: the front row of the head.
	 */
	public static boolean isLightPort(int pos)
	{
		return isHeadRow(pos, 0);
	}

	/**
	 * @return whether the heavy product may be drawn from this part: the back row of the head.
	 */
	public static boolean isHeavyPort(int pos)
	{
		return isHeadRow(pos, DEPTH-1);
	}

	private static boolean isHeadRow(int pos, int depth)
	{
		if(pos < 0||PetroleumGeometry.heightOf(SIZE, pos)!=HEIGHT-1)
			return false;
		return pos%(DEPTH*WIDTH)/WIDTH==depth;
	}

	private static final String ORE_DECK = "scaffoldingSteel";
	private static final String ORE_VESSEL = "blockSheetmetalSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:CrackingUnit";
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
						structure[h][l][w] = manualStack(SHAPE[h][l].charAt(w));
		}
		return structure;
	}

	private static ItemStack manualStack(char cell)
	{
		switch(cell)
		{
			case DECK:
				return new ItemStack(IEContent.blockMetalDecoration1, 1,
						BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
			case VESSEL:
				return new ItemStack(IEContent.blockSheetmetal, 1, BlockTypes_MetalsAll.STEEL.getMeta());
			case FRAME:
				return new ItemStack(IEContent.blockPetroleumDevice, 1,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				return null;
		}
	}

	private IngredientStack[] materials;

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		if(materials==null)
			materials = new IngredientStack[]{
					new IngredientStack(ORE_VESSEL, blockCount(VESSEL)),
					new IngredientStack(ORE_DECK, blockCount(DECK)),
					new IngredientStack(new ItemStack(IEContent.blockPetroleumDevice, blockCount(FRAME),
							BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta()))};
		return materials;
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
		return 9;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return false;
	}

	private static ItemStack renderStack;

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		if(renderStack==null)
			renderStack = new ItemStack(IEContent.blockPetroleumMultiblock, 1,
					BlockTypes_PetroleumMultiblock.CRACKING_UNIT.getMeta());
		GlStateManager.translate(2.5, 3, 1.5);
		ClientUtils.mc().getRenderItem().renderItem(renderStack, ItemCameraTransforms.TransformType.GUI);
	}

	//	=================================
	//		FORMATION
	//	=================================

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Frame occupies the coke drum and the whole head, so the hammered block is one of those.
		//Orientation is read off the structure rather than trusted from the click, as everywhere
		//else in this expansion: a machine built facing the other way still forms.
		for(EnumFacing facing : new EnumFacing[]{guess, guess.rotateY(), guess.getOpposite(), guess.rotateYCCW()})
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
					{
						if(SHAPE[h][l].charAt(w)!=FRAME)
							continue;
						BlockPos origin = pos.add(0, -h, 0).offset(facing, -l).offset(facing.rotateY(), -w);
						if(matches(world, origin, facing))
							return form(world, origin, facing, player);
					}
		return false;
	}

	private static boolean matches(World world, BlockPos origin, EnumFacing facing)
	{
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(!cellMatches(world, cell(origin, facing, h, l, w), SHAPE[h][l].charAt(w)))
						return false;
		return true;
	}

	private static boolean cellMatches(World world, BlockPos cell, char type)
	{
		switch(type)
		{
			case DECK:
				return Utils.isOreBlockAt(world, cell, ORE_DECK);
			case VESSEL:
				return Utils.isOreBlockAt(world, cell, ORE_VESSEL);
			case FRAME:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				//The gaps either side of the coke drum. Unchecked rather than required to be air:
				//pipework threaded through them is what the space is there for.
				return true;
		}
	}

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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.CRACKING_UNIT.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(SHAPE[h][l].charAt(w)==EMPTY)
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityCrackingUnit)
					{
						TileEntityCrackingUnit part = (TileEntityCrackingUnit)te;
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
