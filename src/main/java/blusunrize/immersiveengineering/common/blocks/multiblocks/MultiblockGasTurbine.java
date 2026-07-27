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
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasTurbine;
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
 * The Gas Turbine: a packaged turbine set, three high and six long.
 * <p>
 * The silhouette is the machine's whole readable structure and runs front to back along the
 * facing: a two-course scaffolding <em>filter house</em> where the air is drawn in, a low
 * steel-clad <em>nacelle</em> carrying the compressor, combustor and turbine, the
 * <em>alternator</em> at the far end of the shaft, and an <em>exhaust stack</em> standing back up
 * to full height behind it. The nacelle is deliberately only two courses tall so that the two ends
 * read as towers; a solid six-by-three-by-three box would be a warehouse, not a turbine.
 * <p>
 * That open course above the nacelle is not just shape. The three cells directly over the
 * alternator are where the machine's flux leaves it -- see {@link #terminalPos(int)} -- so the
 * generator end of the package is also visibly the electrical end of it, and a player who can see
 * the machine can see where to put the connectors.
 * <p>
 * As with the industrial burner, the shape is a character table and the {@code ItemStack}
 * templates are built on first use rather than in a static initialiser: a structure that
 * half-matches fails silently -- the player hammers it, nothing happens, and nothing is logged --
 * so the shape is kept loadable, and therefore testable, outside a running game.
 * <p>
 * Every row of the table is a palindrome across the width, so the package has no mirrored variant
 * and formation never has to check for one.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockGasTurbine implements IMultiblock
{
	public static final MultiblockGasTurbine instance = new MultiblockGasTurbine();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.TURBINE_SIZE;
	private static final int HEIGHT = PetroleumGeometry.TURBINE_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.TURBINE_DEPTH;
	private static final int WIDTH = PetroleumGeometry.TURBINE_WIDTH;

	/**
	 * Steel sheetmetal: the skid the package sits on and the casing round the machine.
	 */
	public static final char SHELL = 'S';
	/**
	 * Steel scaffolding: the intake filter house, which has to read as mesh rather than as wall.
	 */
	public static final char MESH = 'D';
	/**
	 * Heavy engineering: the rotating machinery inside the nacelle.
	 */
	public static final char CORE = 'H';
	/**
	 * The generator block, at the cold end of the shaft.
	 */
	public static final char ALTERNATOR = 'G';
	/**
	 * Oilfield frame: the fuel skid at the front and the mouth of the exhaust stack.
	 */
	public static final char FRAME = 'F';
	/**
	 * Nothing: the open course over the nacelle.
	 */
	public static final char EMPTY = '.';

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the front
	 * where the filter house and the fuel skid are and depth 5 the exhaust end.
	 */
	private static final String[][] SHAPE = {
			//The skid. One continuous steel raft under the whole package, because a turbine set is
			//craned onto a foundation in one piece and not built up course by course.
			{"SSS", "SSS", "SSS", "SSS", "SSS", "SSS"},
			//The machine level: filter house, then compressor, combustor and turbine in a line,
			//then the alternator, then the exhaust plenum. The fuel skid is let into the middle of
			//the front face at standing height -- the one block of the machine a player can always
			//reach, and the same place the burner puts its own head.
			{"DFD", "SHS", "SHS", "SHS", "SGS", "SSS"},
			//Above the casing, only the two ends stand up: the filter house and the stack. What is
			//left between them is the open deck the flux leaves from.
			{"DDD", "...", "...", "...", "...", "SFS"}};

	/**
	 * The fuel skid, at the middle of the front face. Master of the assembled machine, where a gas
	 * line is meant to land, and the block whose neighbours are read for the redstone switch.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 1, 0, 1);
	/**
	 * The mouth of the exhaust stack. The plume leaves here.
	 */
	public static final int STACK_POS = PetroleumGeometry.structureIndex(SIZE, HEIGHT-1, DEPTH-1, WIDTH/2);
	/**
	 * The depth the alternator sits at, and therefore the depth the flux leaves at.
	 */
	public static final int TERMINAL_DEPTH = DEPTH-2;

	/**
	 * Where the machine hands its flux over: the deck immediately above the alternator, one cell
	 * per width. The tile entity pushes into whatever accepts flux from below at these positions,
	 * exactly as the diesel generator pushes into the three cells above its own generator course.
	 *
	 * @param w which of the three terminals
	 * @return the structure index whose <em>upper</em> neighbour is that terminal
	 */
	public static int terminalPos(int w)
	{
		return PetroleumGeometry.structureIndex(SIZE, 1, TERMINAL_DEPTH, w);
	}

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
	 * @return how many blocks of the given material a complete turbine is built from
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
	private static final String ORE_MESH = "scaffoldingSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:GasTurbine";
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
					//one counts -- the same courtesy the distillation tower already extends.
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
		return 10;
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
					BlockTypes_PetroleumMultiblock.GAS_TURBINE.getMeta());
		GlStateManager.translate(3, 1.5, 1.5);
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
		//The frame, and not the casing: sheetmetal and scaffolding are everywhere in a built-up
		//base, and triggering a forty-two block check on every hammered sheet would cost more than
		//the structure is worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the stack carries no useful direction, so fall back to where the player is
		//standing, as the burner and the diesel generator do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Only the frame is a trigger and the frame sits in exactly two cells, so the player has
		//hammered one of those two. Orientation is then read off the structure itself rather than
		//trusted from the click: the guess is tried first so a correctly-approached machine forms
		//on the first check, but a turbine built pointing the other way still forms rather than
		//refusing in silence.
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
				//The open deck over the nacelle. Left unchecked on purpose: a connector standing on
				//it is the intended use of the machine, so refusing to form round one would be
				//refusing the assembly a player has just correctly built.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.GAS_TURBINE.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cell(origin, facing, 1, 0, 1);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityGasTurbine)
					{
						TileEntityGasTurbine part = (TileEntityGasTurbine)te;
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
