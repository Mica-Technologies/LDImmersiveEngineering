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
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityLoadingGantry;
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
 * The Fluid Loading Gantry: two scaffolding legs, a frame beam across their tops, and a one-block
 * bay between them.
 * <p>
 * The bay is the machine. Put the crate of empties on one side and the crate for the fulls on the
 * other, and the gantry is the thing in between -- which is exactly what a loading bay is. Nine
 * blocks, no GUI, and its whole configuration is where you put the chests.
 * <p>
 * The shape is a character table and the {@code ItemStack} templates are built on first use rather
 * than in a static initialiser, so the geometry stays loadable -- and therefore testable -- outside
 * a running game.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockLoadingGantry implements IMultiblock
{
	public static final MultiblockLoadingGantry instance = new MultiblockLoadingGantry();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.GANTRY_SIZE;
	private static final int HEIGHT = PetroleumGeometry.GANTRY_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.GANTRY_DEPTH;
	private static final int WIDTH = PetroleumGeometry.GANTRY_WIDTH;

	/**
	 * A leg.
	 */
	public static final char LEG = 'L';
	/**
	 * The beam across the top, and the head the containers hang from.
	 */
	public static final char BEAM = 'F';
	/**
	 * The bay. Not part of the machine at all -- it is where a player and a minecart go.
	 */
	public static final char EMPTY = '.';

	/**
	 * {@code SHAPE[height][depth]}, one character per width.
	 */
	private static final String[][] SHAPE = {
			{"L.L"},
			{"L.L"},
			{"L.L"},
			{"FFF"}};

	/**
	 * Master, and the leg the empties come from: the left leg's foot as seen from the front.
	 */
	public static final int INTAKE_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, 0);
	/**
	 * The leg the fulls go to. Putting intake and output on opposite legs is what makes the machine
	 * read as a line rather than as a box with two faces.
	 */
	public static final int OUTPUT_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, WIDTH-1);

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

	private static final String ORE_LEG = "scaffoldingSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:LoadingGantry";
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
			case LEG:
				return new ItemStack(IEContent.blockMetalDecoration1, 1,
						BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
			case BEAM:
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
					new IngredientStack(ORE_LEG, blockCount(LEG)),
					new IngredientStack(new ItemStack(IEContent.blockPetroleumDevice, blockCount(BEAM),
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
		return 14;
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
					BlockTypes_PetroleumMultiblock.LOADING_GANTRY.getMeta());
		GlStateManager.translate(1.5, 2, .5);
		ClientUtils.mc().getRenderItem().renderItem(renderStack, ItemCameraTransforms.TransformType.GUI);
	}

	//	=================================
	//		FORMATION
	//	=================================

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		//The beam, not the legs: scaffolding is everywhere in a built-up base and a nine-cell check
		//on every hammered piece of it would cost more than the structure is worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the beam carries no useful direction, so fall back to where the player is
		//standing, as the burner and the scrubber do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//The beam spans three cells, so the player has hammered one of those three. Orientation is
		//read off the structure itself rather than trusted from the click.
		for(EnumFacing facing : new EnumFacing[]{guess, guess.rotateY(), guess.getOpposite(), guess.rotateYCCW()})
			for(int w = 0; w < WIDTH; w++)
			{
				BlockPos origin = pos.add(0, -(HEIGHT-1), 0).offset(facing.rotateY(), -w);
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
			case LEG:
				return Utils.isOreBlockAt(world, cell, ORE_LEG);
			case BEAM:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				//The bay. Deliberately unchecked rather than required to be air: a rail run through
				//it is exactly what the gap invites.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.LOADING_GANTRY.getMeta())
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
					if(te instanceof TileEntityLoadingGantry)
					{
						TileEntityLoadingGantry part = (TileEntityLoadingGantry)te;
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
