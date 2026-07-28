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
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration0;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityFuelOilBoiler;
import blusunrize.immersiveengineering.common.blocks.stone.BlockTypes_StoneDecoration;
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
 * The Fuel Oil Boiler: the furnace half of a power station, five tall on a five-by-seven base.
 * <p>
 * The silhouette is deliberately the same story the Industrial Burner tells at a larger scale --
 * a refractory hearth carrying the fire, a shell of water wall standing above it, and a single
 * tube-bank core rising through the middle of that shell from the firing floor to the steam
 * drum. That core is not decoration: {@link #MASTER_POS} sits at its foot, where the fuel line is
 * meant to land, and {@link #STEAM_POS} sits directly above it in the drum roof, where the steam
 * leaves. A player who can see the machine can see the one straight line fuel and steam travel
 * through it, which is the entire plumbing lesson the shape has to teach.
 * <p>
 * Unlike the burner, this machine makes no attempt to be reachable from every side: it burns the
 * three heaviest fuels in the mod for steam and nothing else, and steam is measured in the
 * hundreds of millibuckets a tick, so "one fuel line in the front, one steam line out of the
 * roof" is a plant layout choice, not a convenience the machine owes the player.
 * <p>
 * As with its siblings the shape is a character table read by pure, world-free functions, and the
 * {@code ItemStack} manual template is built on first use rather than in a static initialiser, so
 * the part of the machine that fails silently -- a structure that half-matches simply refuses to
 * form, with no message and nothing in the log -- stays loadable, and therefore testable, outside
 * a running game.
 * <p>
 * Every course is symmetric across the width, so there is no mirrored variant to check for.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockFuelOilBoiler implements IMultiblock
{
	public static final MultiblockFuelOilBoiler instance = new MultiblockFuelOilBoiler();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.BOILER_SIZE;
	private static final int HEIGHT = PetroleumGeometry.BOILER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.BOILER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.BOILER_WIDTH;

	/**
	 * Refractory lining: the hearth the fire actually sits on, exactly the material the Industrial
	 * Burner's own hearth is built from.
	 */
	public static final char BRICK = 'B';
	/**
	 * Steel sheetmetal: the water wall that carries the hearth's heat up to the steam drum.
	 */
	public static final char SHELL = 'S';
	/**
	 * Heavy engineering: the tube-bank core running up the middle of the water wall.
	 */
	public static final char CORE = 'H';
	/**
	 * Oilfield frame: the fuel line at the foot of the core and the steam takeoff above it.
	 */
	public static final char FRAME = 'F';

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the front
	 * where the fuel line lands.
	 */
	private static final String[][] SHAPE = {
			//The firing floor. Solid refractory brick, because this is the course the fire burns
			//on, with the fuel line let into the middle of the front face at standing height -- the
			//one block of the machine a player can always reach.
			{"BBBFBBB", "BBBBBBB", "BBBBBBB", "BBBBBBB", "BBBBBBB"},
			//The water wall, first course. A single length of tube-bank core rises through the
			//middle of the casing here and at every course above it.
			{"SSSSSSS", "SSSSSSS", "SSSHSSS", "SSSSSSS", "SSSSSSS"},
			{"SSSSSSS", "SSSSSSS", "SSSHSSS", "SSSSSSS", "SSSSSSS"},
			{"SSSSSSS", "SSSSSSS", "SSSHSSS", "SSSSSSS", "SSSSSSS"},
			//The steam drum roof. The takeoff sits directly above the core it is fed by, so the
			//straight line from fuel line to steam takeoff is visible from outside the machine.
			{"SSSSSSS", "SSSSSSS", "SSSFSSS", "SSSSSSS", "SSSSSSS"}};

	/**
	 * The fuel line, at the foot of the tube-bank core on the front face. Master of the assembled
	 * machine, and where a fuel pipe is meant to land.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, WIDTH/2);
	/**
	 * The steam takeoff, in the middle of the drum roof directly above the core.
	 */
	public static final int STEAM_POS = PetroleumGeometry.structureIndex(SIZE, HEIGHT-1, DEPTH/2, WIDTH/2);

	/**
	 * @return which material occupies this cell, or {@code '?'} for anything outside the box
	 */
	public static char shapeAt(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return '?';
		return SHAPE[h][l].charAt(w);
	}

	/**
	 * @return whether the structure occupies this cell of its {@code H x L x W} box. The boiler is
	 * a solid box, exactly as the burner and the turbine are, so every in-bounds cell is a part.
	 */
	public static boolean isPart(int h, int l, int w)
	{
		return h >= 0&&h < HEIGHT&&l >= 0&&l < DEPTH&&w >= 0&&w < WIDTH;
	}

	/**
	 * @return how many blocks of the given material a complete boiler is built from
	 */
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

	private static final String ORE_SHELL = "blockSheetmetalSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:FuelOilBoiler";
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
			case BRICK:
				return new ItemStack(IEContent.blockStoneDecoration, 1,
						BlockTypes_StoneDecoration.BLASTBRICK.getMeta());
			case SHELL:
				return new ItemStack(IEContent.blockSheetmetal, 1,
						BlockTypes_MetalsAll.STEEL.getMeta());
			case CORE:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
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
					new IngredientStack(new ItemStack(IEContent.blockStoneDecoration, blockCount(BRICK),
							BlockTypes_StoneDecoration.BLASTBRICK.getMeta())),
					//By ore name, so a sheetmetal block from any mod that registers one counts -- the
					//same courtesy the burner and the turbine already extend.
					new IngredientStack(ORE_SHELL, blockCount(SHELL)),
					new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, blockCount(CORE),
							BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta())),
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
		return 8;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return true;
	}

	/**
	 * Left null rather than {@code ItemStack.EMPTY} so that nothing about this class touches the
	 * item registry until a manual page is actually drawn, which is what keeps the shape above
	 * loadable outside a running game.
	 */
	private static ItemStack renderStack;

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		if(renderStack==null)
			renderStack = new ItemStack(IEContent.blockPetroleumMultiblock, 1,
					BlockTypes_PetroleumMultiblock.FUEL_OIL_BOILER.getMeta());
		GlStateManager.translate(3.5, 2.5, 2.5);
		GlStateManager.rotate(-45, 0, 1, 0);
		GlStateManager.rotate(-20, 1, 0, 0);
		GlStateManager.scale(5, 5, 5);
		GlStateManager.disableCull();
		ClientUtils.mc().getRenderItem().renderItem(renderStack, ItemCameraTransforms.TransformType.GUI);
		GlStateManager.enableCull();
	}

	//	=================================
	//		FORMATION
	//	=================================

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		//The fuel line and the steam takeoff, and not the brick or the casing: those two materials
		//are everywhere in a built-up base, and triggering a hundred-and-seventy-block check on
		//every hammered sheet would cost more than the structure is worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the takeoff carries no useful direction, so fall back to where the player is
		//standing, as the burner and the turbine do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Only the two frame cells are triggers, so the player has hammered one of those two.
		//Orientation is then read off the structure itself rather than trusted from the click: the
		//guess is tried first so a correctly-approached machine forms on the first check, but a
		//boiler built facing the other way still forms rather than refusing in silence.
		for(EnumFacing facing : orderedFacings(guess))
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

	private static EnumFacing[] orderedFacings(EnumFacing guess)
	{
		return new EnumFacing[]{guess, guess.rotateY(), guess.getOpposite(), guess.rotateYCCW()};
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
			case BRICK:
				return Utils.isBlockAt(world, cell, IEContent.blockStoneDecoration,
						BlockTypes_StoneDecoration.BLASTBRICK.getMeta());
			case SHELL:
				return Utils.isOreBlockAt(world, cell, ORE_SHELL);
			case CORE:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case FRAME:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				return true;
		}
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code getBlockPosForPos} does, so the
	 * machine can address its own parts after formation.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.FUEL_OIL_BOILER.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cell(origin, facing, 0, 0, WIDTH/2);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityFuelOilBoiler)
					{
						TileEntityFuelOilBoiler part = (TileEntityFuelOilBoiler)te;
						part.formed = true;
						part.facing = facing;
						part.pos = PetroleumGeometry.structureIndex(SIZE, h, l, w);
						part.offset = new int[]{
								target.getX()-masterPos.getX(),
								target.getY()-masterPos.getY(),
								target.getZ()-masterPos.getZ()};
						part.markDirty();
						world.addBlockEvent(target, IEContent.blockPetroleumMultiblock, 255, 0);
					}
				}
		return true;
	}
}
