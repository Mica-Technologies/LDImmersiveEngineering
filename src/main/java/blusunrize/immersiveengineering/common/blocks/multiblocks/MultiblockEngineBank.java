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
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityEngineBank;
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
 * The Reciprocating Engine Bank: an engine house, five cylinders wide.
 * <p>
 * The shape is a building rather than a package, and it is laid out the way an engine house
 * actually is, front to back along the facing: a <em>fuel gallery</em> across the front face at
 * standing height, an open <em>walkway</em> behind it, the <em>cylinder row</em> itself, the
 * <em>alternators</em> on the crank side, and a back wall. Above that sits an exhaust trunk over
 * the cylinders with three stacks through the roof, and an open bay over the alternators which is
 * the switchyard -- the one part of the building deliberately left outdoors, because that is where
 * the flux leaves and a player has to be able to see and reach it.
 * <p>
 * <strong>Every course is laid out across the width, and that is the whole point.</strong> The
 * five cylinders sit in a row across the width of the building; so do the fuel gallery, the
 * walkway and the roof. Banks link along that same width axis (see {@code TileEntityEngineBank}),
 * so butting a second bank against the first does not produce two buildings standing next to each
 * other -- it produces one longer engine house, with the cylinder row running unbroken through it,
 * one fuel gallery along the whole front, and a walkway you can walk end to end. The linking rule
 * and the silhouette agree, which is the only reason the linking reads as anything other than an
 * arbitrary adjacency check.
 * <p>
 * The box is a hundred cells and the building fills seventy-five of them. The twenty-five that are
 * empty are the ones that make it a building: the walkway is open floor to roof, the front is open
 * above the gallery so the cylinders are visible from outside, and the switchyard bay is open to
 * the sky.
 * <p>
 * As with the turbine and the burner, the shape is a character table and the {@code ItemStack}
 * templates are built on first use rather than in a static initialiser: a structure that
 * half-matches fails silently -- the player hammers it, nothing happens, and nothing is logged --
 * so the shape is kept loadable, and therefore testable, outside a running game.
 * <p>
 * Every row of the table is a palindrome across the width, so the building has no mirrored variant
 * and formation never has to check for one. That matters more here than anywhere else in the
 * expansion: linking compares two banks' handedness, and a shape that could form mirrored would
 * give two adjacent halls that look identical and refuse to link, with nothing to tell the player
 * why.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockEngineBank implements IMultiblock
{
	public static final MultiblockEngineBank instance = new MultiblockEngineBank();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.ENGINE_SIZE;
	private static final int HEIGHT = PetroleumGeometry.ENGINE_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.ENGINE_DEPTH;
	private static final int WIDTH = PetroleumGeometry.ENGINE_WIDTH;

	/**
	 * Steel sheetmetal: the raft the engines are bolted to, the exhaust trunk and the back wall.
	 */
	public static final char SHELL = 'S';
	/**
	 * Steel scaffolding: the roof trusses. A solid roof would make the building a warehouse; a
	 * trussed one lets the cylinder row be seen from above, which is how anyone ever notices that
	 * two linked banks share one row.
	 */
	public static final char TRUSS = 'D';
	/**
	 * Heavy engineering: one cell per cylinder. The row of these <em>is</em> the machine.
	 */
	public static final char CYLINDER = 'H';
	/**
	 * The generator block, on the crank side. Three rather than five: the alternators are geared
	 * off a common crankshaft, and three is also the number of electrical terminals the machine
	 * offers, which keeps "how many connectors does this want" and "how many alternators can I
	 * see" the same question.
	 */
	public static final char ALTERNATOR = 'G';
	/**
	 * Oilfield frame: the fuel gallery across the front, and the three exhaust stacks.
	 */
	public static final char FRAME = 'F';
	/**
	 * Nothing: the walkway, the open front above the gallery, and the switchyard bay.
	 */
	public static final char EMPTY = '.';

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the front
	 * where the fuel gallery is and depth 4 the back wall.
	 */
	private static final String[][] SHAPE = {
			//The raft. One continuous steel foundation, because a reciprocating engine that is not
			//bolted to a common bedplate shakes itself apart, and because the raft is what carries
			//the linking check: the two cells at either end of it are the mating faces.
			{"SSSSS", "SSSSS", "SSSSS", "SSSSS", "SSSSS"},
			//The engine deck. Gallery, walkway, cylinders, alternators, back wall.
			{"FFFFF", ".....", "HHHHH", "GSGSG", "SSSSS"},
			//Head height. Open over the gallery and the walkway -- that is the building's front
			//elevation and its inside -- with the exhaust trunk over the cylinders and the
			//switchyard bay left open behind it.
			{".....", ".....", "SSSSS", ".....", "SSSSS"},
			//The roof: a trussed canopy carried on the trunk and the back wall, with the three
			//stacks coming through it, and no roof at all over the switchyard.
			{"DDDDD", "DDDDD", "FDFDF", ".....", "DDDDD"}};

	/**
	 * The middle of the fuel gallery. Master of the assembled bank, where a fuel line is meant to
	 * land, and the block a player naturally walks up to.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 1, 0, WIDTH/2);
	/**
	 * The one block of the bank a comparator reads: the left-hand corner of the fuel gallery.
	 * Deliberately not the master, so that the block a player is most likely to plumb into and the
	 * block they wire a comparator to are two different, individually reachable cells of the same
	 * front face.
	 */
	public static final int REDSTONE_POS = PetroleumGeometry.structureIndex(SIZE, 1, 0, 0);

	/**
	 * The depth the alternators sit at, and therefore the depth the flux leaves at.
	 */
	public static final int TERMINAL_DEPTH = 3;
	/**
	 * The depth the cylinders and their exhaust trunk sit at.
	 */
	public static final int CYLINDER_DEPTH = 2;
	/**
	 * How many electrical terminals one bank offers. One per alternator.
	 */
	public static final int TERMINAL_COUNT = 3;
	/**
	 * How many exhaust stacks one bank has. One per alternator, above the cylinder they serve.
	 */
	public static final int STACK_COUNT = 3;

	/**
	 * Where the bank hands its flux over: the switchyard bay immediately above each alternator.
	 * The tile entity pushes into whatever accepts flux from below at these positions, exactly as
	 * the gas turbine pushes into the deck over its own alternator.
	 *
	 * @param terminal which of the three, 0 being the low-width end
	 * @return the structure index whose <em>upper</em> neighbour is that terminal
	 */
	public static int terminalPos(int terminal)
	{
		return PetroleumGeometry.structureIndex(SIZE, 1, TERMINAL_DEPTH, terminal*2);
	}

	/**
	 * @param stack which of the three, 0 being the low-width end
	 * @return the structure index of that stack's mouth, which is where its plume leaves
	 */
	public static int stackPos(int stack)
	{
		return PetroleumGeometry.structureIndex(SIZE, HEIGHT-1, CYLINDER_DEPTH, stack*2);
	}

	/**
	 * The raft cell at the low-width end of the front row. A bank's <em>left</em> neighbour, if it
	 * has one, presents its {@link #MATING_HIGH} cell against this one.
	 */
	public static final int MATING_LOW = PetroleumGeometry.structureIndex(SIZE, 0, 0, 0);
	/**
	 * The raft cell at the high-width end of the front row, and the mirror of {@link #MATING_LOW}.
	 * <p>
	 * Both are on the raft rather than anywhere more interesting because the raft is the one course
	 * that is solid all the way across: whatever else a player builds around a bank, these two
	 * cells exist and are exactly one block apart across the seam between two flush banks.
	 */
	public static final int MATING_HIGH = PetroleumGeometry.structureIndex(SIZE, 0, 0, WIDTH-1);

	/**
	 * @return which material occupies this cell, {@link #EMPTY} for an open cell of the box, and
	 * {@code '?'} for anything outside it
	 */
	public static char shapeAt(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return '?';
		return SHAPE[h][l].charAt(w);
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
	 * @return how many blocks of the given material a complete bank is built from
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

	/**
	 * @return how many blocks a complete bank is built from, open cells excluded
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
	private static final String ORE_TRUSS = "scaffoldingSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:EngineBank";
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
			case SHELL:
				return new ItemStack(IEContent.blockSheetmetal, 1,
						BlockTypes_MetalsAll.STEEL.getMeta());
			case TRUSS:
				return new ItemStack(IEContent.blockMetalDecoration1, 1,
						BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
			case CYLINDER:
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
					//one counts -- the same courtesy the rest of the expansion extends.
					new IngredientStack(ORE_SHELL, blockCount(SHELL)),
					new IngredientStack(ORE_TRUSS, blockCount(TRUSS)),
					new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, blockCount(CYLINDER),
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
					BlockTypes_PetroleumMultiblock.ENGINE_BANK.getMeta());
		GlStateManager.translate(2.5, 2, 2.5);
		GlStateManager.rotate(-45, 0, 1, 0);
		GlStateManager.rotate(-20, 1, 0, 0);
		GlStateManager.scale(6, 6, 6);
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
		//The frame, and not the raft: sheetmetal and scaffolding are everywhere in a built-up base,
		//and triggering a seventy-five block check on every hammered sheet would cost more than the
		//structure is worth. This matters twice over here, because a hall of eight banks is six
		//hundred blocks of sheetmetal standing in one place.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A gallery block is hammered from in front, so the clicked face is normally a side; a stack
		//is hammered from above, which carries no useful direction, so fall back to where the
		//player is standing.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Orientation is read off the structure rather than trusted from the click: the guess is
		//tried first so a correctly-approached building forms on the first check, but a bank built
		//facing the other way still forms rather than refusing in silence. That is worth more here
		//than elsewhere, because the second bank of a hall is usually approached from the end of
		//the first one rather than from its front.
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
			case SHELL:
				return Utils.isOreBlockAt(world, cell, ORE_SHELL);
			case TRUSS:
				return Utils.isOreBlockAt(world, cell, ORE_TRUSS);
			case CYLINDER:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case ALTERNATOR:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.GENERATOR.getMeta());
			case FRAME:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				//The walkway, the open front and the switchyard bay. Left unchecked on purpose: a
				//connector standing in the switchyard and a pipe run crossing the walkway are both
				//the intended use of the building, so refusing to form round one would be refusing
				//the assembly a player has just correctly built.
				return true;
		}
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code getBlockPosForPos} does, so the
	 * building can address its own parts -- and find the bank next door -- after formation.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.ENGINE_BANK.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cell(origin, facing, 1, 0, WIDTH/2);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityEngineBank)
					{
						TileEntityEngineBank part = (TileEntityEngineBank)te;
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
		//Nothing needs to be told about the bank next door here. Every part of this building is a
		//tile entity that has just been created, so its link cache starts out stale by definition,
		//and the first thing it does when it resolves is hand the same staleness back to whatever
		//it finds against its mating faces. A hall assembles itself from one hammer blow.
		return true;
	}
}
