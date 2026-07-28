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
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntitySteamTurbineHall;
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
 * The Steam Turbine Hall: the largest structure in the expansion, five tall, nine long and five
 * wide, read front to back down its long axis as a real power house is -- condenser, generator
 * hall, switchyard.
 * <p>
 * Every other petroleum multiblock in this fork is either a packaged skid (the Gas Turbine) or a
 * column (the Distillation Tower). A machine this size has to read as neither: it is a
 * <em>building</em>, and a 5x9x5 box that is solid all through would be a block of steel, not a
 * hall. So the shape is a shell -- a continuous foundation raft, a roof, and steel walls -- with
 * the whole seven-layer, three-wide interior left open air. What little machinery shows is
 * exactly the three things the flavour text promises, laid out in the order a plant is actually
 * walked through: louvred condenser venting at the front wall, a two-layer turbine-generator
 * core down the centre line, and a switchyard deck at the back with its bus open to the sky --
 * the same "flux leaves through an open deck" language the Gas Turbine already uses for its
 * terminals, just wider.
 * <p>
 * Like the tower, the shape is <em>generated</em> from a handful of named depths rather than
 * hand-authored as a forty-five-character grid: {@link #shapeAt} is the one function that knows
 * where the walls, the core and the deck are, and {@link #isPart}, {@link #blockCount} and
 * {@link #getStructureManual} all read off it. A single wrong band boundary would otherwise be
 * the kind of typo nobody notices in a hand-written table this size until a player hammers a
 * finished building and nothing happens.
 * <p>
 * The footprint is symmetric across its width, so there is no mirrored variant to check for --
 * the same discipline the turbine and the tower both keep.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockSteamTurbineHall implements IMultiblock
{
	public static final MultiblockSteamTurbineHall instance = new MultiblockSteamTurbineHall();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.HALL_SIZE;
	public static final int HEIGHT = PetroleumGeometry.HALL_HEIGHT;
	public static final int DEPTH = PetroleumGeometry.HALL_DEPTH;
	public static final int WIDTH = PetroleumGeometry.HALL_WIDTH;

	/**
	 * Steel sheetmetal: the foundation raft, the roof, the walls and the switchyard deck.
	 */
	public static final char SHELL = 'S';
	/**
	 * Steel scaffolding: the louvred wall at the condenser end, standing in for the venting a real
	 * condenser needs -- the same "has to read as mesh, not wall" job the turbine's filter house
	 * does at its own intake.
	 */
	public static final char MESH = 'D';
	/**
	 * Heavy engineering: the turbine-generator core, two layers tall so the centre of the hall
	 * reads as the biggest piece of machinery in the mod rather than a single block wearing a big
	 * number.
	 */
	public static final char CORE = 'H';
	/**
	 * The generator block, at the end of the core nearest the switchyard.
	 */
	public static final char ALTERNATOR = 'G';
	/**
	 * Oilfield frame: the steam inlet skid. The one block a player hammers to form the hall, and
	 * the master of the assembled structure.
	 */
	public static final char FRAME = 'F';
	/**
	 * Nothing: the hall interior, and the open bus deck over the switchyard.
	 */
	public static final char EMPTY = '.';

	/**
	 * Depth of the condenser's front wall, whose middle course is louvred rather than solid.
	 */
	public static final int CONDENSER_WALL = 0;
	/**
	 * Depth of the steam inlet skid -- the hall's master block, standing just inside the condenser
	 * bay so the line from a boiler house lands before the steam ever reaches the core.
	 */
	public static final int INLET_DEPTH = 2;
	/**
	 * The two depths the turbine-generator core occupies. Two rather than one, both so the core
	 * reads as a real machine from outside through the walls' proportions and so it visually
	 * separates the inlet from the alternator it drives.
	 */
	public static final int CORE_DEPTH_1 = 3;
	public static final int CORE_DEPTH_2 = 4;
	/**
	 * Depth of the alternator, immediately behind the core on the way to the switchyard.
	 */
	public static final int ALTERNATOR_DEPTH = 5;
	/**
	 * Depth of the switchyard bus deck -- solid one layer down from the roof, open above it, in
	 * exactly the shape the Gas Turbine already uses for its own terminals. Where the flux leaves.
	 */
	public static final int ENERGY_DEPTH = 7;

	/**
	 * How many bus positions the switchyard offers. Three, the same count the Gas Turbine settles
	 * on and for the same reason: {@link TileEntitySteamTurbineHall#MAX_OUTPUT} divides evenly by
	 * it, so a comparator or a player reading the split never meets a remainder.
	 */
	public static final int TERMINAL_COUNT = WIDTH-2;

	/**
	 * @return which material occupies this cell, {@link #EMPTY} for open hall or roof deck, and
	 * {@code '?'} for anything outside the box
	 */
	public static char shapeAt(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return '?';

		//The raft: one continuous slab under the whole building, exactly as the turbine's skid and
		//the tower's deck are each one continuous course.
		if(h==0)
			return SHELL;

		//The roof, solid but for the switchyard's open bus deck directly beneath it -- the same
		//"open course over the generator" the turbine's terminal deck already is.
		if(h==HEIGHT-1)
			return l==ENERGY_DEPTH&&w > 0&&w < WIDTH-1?EMPTY: SHELL;

		boolean outerWall = l==0||l==DEPTH-1||w==0||w==WIDTH-1;
		if(outerWall)
		{
			//The condenser's louvres: the middle course of the front wall only, so the wall still
			//reads as a wall from outside and not as a hole with a frame around it.
			if(l==CONDENSER_WALL&&h==2&&w > 0&&w < WIDTH-1)
				return MESH;
			return SHELL;
		}

		//From here on the cell is inside the hall. Most of it is air -- that is the point of a
		//building -- and only the named machinery depths put anything there at all.
		if(l==INLET_DEPTH&&h==1&&w==WIDTH/2)
			return FRAME;
		if((l==CORE_DEPTH_1||l==CORE_DEPTH_2)&&(h==1||h==2)&&w==WIDTH/2)
			return CORE;
		if(l==ALTERNATOR_DEPTH&&h==1&&w==WIDTH/2)
			return ALTERNATOR;
		//The deck the open roof above sits on: solid so a connector standing in that gap has
		//something under it to be read as "above the switchyard" from, exactly as the turbine's
		//alternator course sits under its own open deck.
		if(l==ENERGY_DEPTH&&h==HEIGHT-2)
			return SHELL;

		return EMPTY;
	}

	/**
	 * @return whether the structure occupies this cell of its {@code H x L x W} box
	 */
	public static boolean isPart(int h, int l, int w)
	{
		char cell = shapeAt(h, l, w);
		return cell!=EMPTY&&cell!='?';
	}

	/**
	 * @return how many blocks of the given material a complete hall is built from
	 */
	public static int blockCount(char material)
	{
		int count = 0;
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(shapeAt(h, l, w)==material)
						count++;
		return count;
	}

	/**
	 * The steam inlet skid: the hall's master block and its only hammer trigger.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 1, INLET_DEPTH, WIDTH/2);

	/**
	 * Where the hall hands its flux over: the deck immediately above the switchyard bus, one cell
	 * per terminal. The tile entity pushes into whatever accepts flux from below at these
	 * positions, exactly as the Gas Turbine pushes into the cells above its own alternator.
	 *
	 * @param w which of the {@link #TERMINAL_COUNT} terminals
	 * @return the structure index whose <em>upper</em> neighbour is that terminal
	 */
	public static int terminalPos(int w)
	{
		return PetroleumGeometry.structureIndex(SIZE, HEIGHT-2, ENERGY_DEPTH, w+1);
	}

	private static final String ORE_SHELL = "blockSheetmetalSteel";
	private static final String ORE_MESH = "scaffoldingSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:SteamTurbineHall";
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
						structure[h][l][w] = manualStack(shapeAt(h, l, w));
		}
		return structure;
	}

	private static ItemStack manualStack(char cell)
	{
		switch(cell)
		{
			case SHELL:
				return new ItemStack(IEContent.blockSheetmetal, 1,
						BlockTypes_MetalsAll.STEEL.getMeta());
			case MESH:
				return new ItemStack(IEContent.blockMetalDecoration1, 1,
						BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
			case CORE:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case ALTERNATOR:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.GENERATOR.getMeta());
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
					//Sheetmetal and scaffolding by ore name, so a block from any mod that registers
					//one counts -- the same courtesy the turbine and the tower already extend.
					new IngredientStack(ORE_SHELL, blockCount(SHELL)),
					new IngredientStack(ORE_MESH, blockCount(MESH)),
					new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, blockCount(CORE),
							BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta())),
					new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, blockCount(ALTERNATOR),
							BlockTypes_MetalDecoration0.GENERATOR.getMeta())),
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
		//Shrunk further than the turbine's own 10: the hall is half again as long and has to fit
		//the same manual page.
		return 14;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return true;
	}

	/**
	 * Left null rather than {@code ItemStack.EMPTY} so that nothing about this class touches the
	 * item registry until a manual page is actually drawn, which is what keeps {@link #shapeAt}
	 * loadable outside a running game.
	 */
	private static ItemStack renderStack;

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		if(renderStack==null)
			renderStack = new ItemStack(IEContent.blockPetroleumMultiblock, 1,
					BlockTypes_PetroleumMultiblock.STEAM_TURBINE_HALL.getMeta());
		GlStateManager.translate(4.5, 2.5, 2.5);
		GlStateManager.rotate(-45, 0, 1, 0);
		GlStateManager.rotate(-20, 1, 0, 0);
		GlStateManager.scale(9, 9, 9);
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
		//The inlet skid, and not the walls: sheetmetal and scaffolding are everywhere in a built-up
		//base, and triggering a two-hundred-odd block check on every hammered sheet would cost more
		//than the structure is worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the skid carries no useful direction, so fall back to where the player is
		//standing, as the turbine and the tower both do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Only the skid is a trigger and it sits in exactly one cell, so there is only one candidate
		//per facing to check.
		for(EnumFacing facing : orderedFacings(guess))
			for(int h = 0; h < HEIGHT; h++)
				for(int l = 0; l < DEPTH; l++)
					for(int w = 0; w < WIDTH; w++)
					{
						if(shapeAt(h, l, w)!=FRAME)
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
					if(!cellMatches(world, cell(origin, facing, h, l, w), shapeAt(h, l, w)))
						return false;
		return true;
	}

	private static boolean cellMatches(World world, BlockPos cell, char type)
	{
		switch(type)
		{
			case SHELL:
				return Utils.isOreBlockAt(world, cell, ORE_SHELL);
			case MESH:
				return Utils.isOreBlockAt(world, cell, ORE_MESH);
			case CORE:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case ALTERNATOR:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.GENERATOR.getMeta());
			case FRAME:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				//The hall interior and the open bus deck. Left unchecked on purpose: a player
				//standing inside the hall, or a connector on the switchyard deck, is the intended
				//use of the structure and must not stop it forming.
				return true;
		}
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code getBlockPosForPos} does, so the
	 * hall can address its own parts after formation.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.STEAM_TURBINE_HALL.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cell(origin, facing, 1, INLET_DEPTH, WIDTH/2);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntitySteamTurbineHall)
					{
						TileEntitySteamTurbineHall part = (TileEntitySteamTurbineHall)te;
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
