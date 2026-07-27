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
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityIndustrialBurner;
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
 * The Industrial Burner: a three-by-three-by-three firebox.
 * <p>
 * A refractory hearth and lining of Blast Brick carrying the fire, a steel sheetmetal crown over
 * the top, an Oilfield Frame burner head at the front where the fuel line lands, and a second
 * frame in the middle of the crown for the flue. The crown is the working face -- anything the
 * burner heats directly sits on top of it -- so it is the one course that is not brick.
 * <p>
 * The shape is a character table and the {@code ItemStack} templates are built on first use
 * rather than in a static initialiser, following {@link MultiblockDerrick}: a structure that
 * half-matches fails silently, the player simply hammers a machine that never forms and is told
 * nothing, so the shape is kept loadable -- and therefore testable -- outside a running game.
 * <p>
 * The burner head is the only asymmetry, which means the arrangement of blocks alone determines
 * which way the machine points. Formation exploits that: rather than trusting the clicked face,
 * it tries all four orientations and takes the one the player actually built.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockIndustrialBurner implements IMultiblock
{
	public static final MultiblockIndustrialBurner instance = new MultiblockIndustrialBurner();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.BURNER_SIZE;
	private static final int HEIGHT = PetroleumGeometry.BURNER_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.BURNER_DEPTH;
	private static final int WIDTH = PetroleumGeometry.BURNER_WIDTH;

	/**
	 * Refractory lining.
	 */
	public static final char BRICK = 'B';
	/**
	 * The steel crown.
	 */
	public static final char SHELL = 'S';
	/**
	 * Oilfield frame: the burner head and the flue.
	 */
	public static final char FRAME = 'F';

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the front
	 * where the burner head and its fuel line are.
	 */
	private static final String[][] SHAPE = {
			//The hearth. Solid brick, because this is the course the fire actually sits on.
			{"BBB", "BBB", "BBB"},
			//The combustion chamber, lined all round, with the burner head let into the front
			//face at standing height -- the one block of the machine a player can always reach.
			{"BFB", "BBB", "BBB"},
			//The crown. Sheetmetal rather than brick because it is a working surface: a furnace
			//set on top of the burner is sitting on a hob, not on a wall.
			{"SSS", "SFS", "SSS"}};

	/**
	 * The burner head, at the middle of the front face of the combustion chamber. Master of the
	 * assembled machine, and where a fuel line is meant to land.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 1, 0, 1);
	/**
	 * The flue, in the middle of the crown. Smoke leaves here.
	 */
	public static final int FLUE_POS = PetroleumGeometry.structureIndex(SIZE, HEIGHT-1, DEPTH/2, WIDTH/2);

	/**
	 * @return which material occupies this cell, or {@code '.'} for anything outside the box
	 */
	public static char shapeAt(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return '.';
		return SHAPE[h][l].charAt(w);
	}

	/**
	 * @return how many blocks of the given material a complete burner is built from
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

	@Override
	public String getUniqueName()
	{
		return "IE:IndustrialBurner";
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
					//By ore name, so a sheetmetal block from any mod that registers one counts --
					//the same courtesy the arc furnace and the assembler already extend.
					new IngredientStack("blockSheetmetalSteel", blockCount(SHELL)),
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
		return 16;
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
					BlockTypes_PetroleumMultiblock.INDUSTRIAL_BURNER.getMeta());
		GlStateManager.translate(1.5, 1.5, 1.5);
		GlStateManager.rotate(-45, 0, 1, 0);
		GlStateManager.rotate(-20, 1, 0, 0);
		GlStateManager.scale(4, 4, 4);
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
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the crown carries no useful direction, so fall back to where the player is
		//standing, as the pumpjack and the diesel generator do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Only the frame is a trigger, and the frame sits in exactly two cells, so the player has
		//hammered one of those two. Orientation is then read off the structure itself rather than
		//trusted from the click: the guess is tried first so a correctly-approached machine forms
		//on the first check, but a burner built facing the other way still forms rather than
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
			case BRICK:
				return Utils.isBlockAt(world, cell, IEContent.blockStoneDecoration,
						BlockTypes_StoneDecoration.BLASTBRICK.getMeta());
			case SHELL:
				return Utils.isOreBlockAt(world, cell, "blockSheetmetalSteel");
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.INDUSTRIAL_BURNER.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cell(origin, facing, 1, 0, 1);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityIndustrialBurner)
					{
						TileEntityIndustrialBurner part = (TileEntityIndustrialBurner)te;
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
