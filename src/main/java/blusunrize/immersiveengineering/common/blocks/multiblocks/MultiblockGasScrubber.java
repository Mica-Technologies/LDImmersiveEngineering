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
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasScrubber;
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
 * The Gas Scrubber: twin vertical vessels on a shared skid, six blocks tall.
 * <p>
 * Two columns of steel sheetmetal run the full height at either side of a three by three
 * footprint, with an open alley between them and a run of Oilfield Frame bridging their tops. The
 * alley is not decoration: it is what stops a six-block wall of sheetmetal reading as a silo, and
 * it is where a player stands to plumb the thing. The skid course underneath is scaffolding, and
 * that is the layer the sour gas goes into.
 * <p>
 * <strong>The height is the interface</strong>, exactly as it is on the distillation tower. Sour
 * gas enters anywhere on the skid, sweet gas leaves anywhere along the head course, and the
 * sulfur drops out of the front of the skid. Nothing in the middle four courses connects to
 * anything, so a pipe run climbing past the vessels can never pick up the wrong stream.
 * <p>
 * The shape is a character table and the {@code ItemStack} templates are built on first use
 * rather than in a static initialiser, following {@link MultiblockIndustrialBurner}: a structure
 * that half-matches fails silently -- the player hammers a machine that never forms and is told
 * nothing -- so the shape stays loadable, and therefore testable, outside a running game.
 * <p>
 * The frames sit only on the front face and on the crossover, which means the arrangement of
 * blocks alone determines which way the machine points. Formation exploits that: rather than
 * trusting the clicked face it tries all four orientations and takes the one the player built.
 * The footprint is symmetric across the width, so there is no mirrored variant to check for.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockGasScrubber implements IMultiblock
{
	public static final MultiblockGasScrubber instance = new MultiblockGasScrubber();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.SCRUBBER_SIZE;
	private static final int HEIGHT = PetroleumGeometry.SCRUBBER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.SCRUBBER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.SCRUBBER_WIDTH;

	/**
	 * The skid the vessels stand on, and the course sour gas is fed into.
	 */
	public static final char DECK = 'D';
	/**
	 * The vessels themselves.
	 */
	public static final char VESSEL = 'V';
	/**
	 * Oilfield frame: the two feed manifolds, the two vapour offtakes and the crossover.
	 */
	public static final char FRAME = 'F';
	/**
	 * The alley between the vessels, which is not part of the machine at all.
	 */
	public static final char EMPTY = '.';

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the front
	 * face -- where the frames are, where a player stands, and where the sulfur comes out.
	 */
	private static final String[][] SHAPE = {
			//The skid. Solid, because it carries both vessels and because a machine that floats on
			//two legs does not read as something that holds pressure.
			{"DDD", "DDD", "DDD"},
			//The foot of each vessel, with the feed manifolds let into the front face at standing
			//height -- the two blocks of the machine a player can always reach without climbing.
			{"F.F", "V.V", "V.V"},
			{"V.V", "V.V", "V.V"},
			{"V.V", "V.V", "V.V"},
			{"V.V", "V.V", "V.V"},
			//The head. Offtakes on the front, and the crossover run bridging the two vessel tops,
			//which is the one thing that makes the silhouette read as a pair rather than as two
			//unrelated columns that happen to be adjacent.
			{"F.F", "VFV", "V.V"}};

	/**
	 * Master of the assembled machine: the front-left corner of the skid, which is also the origin
	 * the structure is laid out from.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, 0);
	/**
	 * The middle of the skid's front face. Sulfur is pushed into whatever inventory stands against
	 * this block, so the machine has one obvious place to put a chest.
	 */
	public static final int SULFUR_OUTLET_POS = PetroleumGeometry.structureIndex(SIZE, 0, 0, WIDTH/2);
	/**
	 * The crossover, in the middle of the head course. The vapour plume rises from either side of
	 * it.
	 */
	public static final int CROSSOVER_POS = PetroleumGeometry.structureIndex(SIZE, HEIGHT-1, DEPTH/2, WIDTH/2);

	/**
	 * @return which material occupies this cell, or {@link #EMPTY} for the alley and for anything
	 * outside the box
	 */
	public static char shapeAt(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return EMPTY;
		return SHAPE[h][l].charAt(w);
	}

	/**
	 * @return whether the structure occupies this cell of its {@code H x L x W} box
	 */
	public static boolean isPart(int h, int l, int w)
	{
		return shapeAt(h, l, w)!=EMPTY;
	}

	/**
	 * @return how many blocks of the given material a complete scrubber is built from
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

	private static final String ORE_DECK = "scaffoldingSteel";
	private static final String ORE_VESSEL = "blockSheetmetalSteel";

	@Override
	public String getUniqueName()
	{
		return "IE:GasScrubber";
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
				return new ItemStack(IEContent.blockSheetmetal, 1,
						BlockTypes_MetalsAll.STEEL.getMeta());
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
					//By ore name, so scaffolding and sheetmetal from any mod that registers them
					//count -- the same courtesy the tower and the arc furnace already extend.
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
		return 10;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return false;
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
					BlockTypes_PetroleumMultiblock.GAS_SCRUBBER.getMeta());
		GlStateManager.translate(1.5, 3, 1.5);
		ClientUtils.mc().getRenderItem().renderItem(renderStack, ItemCameraTransforms.TransformType.GUI);
	}

	//	=================================
	//		FORMATION
	//	=================================

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		//The frames, and not the vessels: sheetmetal and scaffolding are everywhere in a built-up
		//base, and triggering a fifty-four cell check on every hammered sheet would cost more than
		//the structure is worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the crossover carries no useful direction, so fall back to where the player is
		//standing, as the burner and the pumpjack do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Only frame is a trigger, and frame sits in five cells, so the player has hammered one of
		//those five. Orientation is then read off the structure itself rather than trusted from the
		//click: the guess is tried first so a correctly-approached machine forms on the first check,
		//but a scrubber built facing the other way still forms rather than refusing in silence.
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
			case DECK:
				return Utils.isOreBlockAt(world, cell, ORE_DECK);
			case VESSEL:
				return Utils.isOreBlockAt(world, cell, ORE_VESSEL);
			case FRAME:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				//The alley. Deliberately unchecked rather than required to be air: a walkway or a
				//ladder run between the vessels is exactly what the gap is there to invite.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.GAS_SCRUBBER.getMeta())
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
					if(te instanceof TileEntityGasScrubber)
					{
						TileEntityGasScrubber part = (TileEntityGasScrubber)te;
						part.formed = true;
						part.facing = facing;
						part.pos = PetroleumGeometry.structureIndex(SIZE, h, l, w);
						//The origin is the master, so the offsets are simply the cell's own position
						//within the box.
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
