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
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityHRSG;
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
 * The Heat Recovery Steam Generator: a duct with a boiler inside it, three high and five long.
 * <p>
 * Every other structure in this expansion is shaped to read from a distance. This one is shaped
 * to <em>dock</em>. Its intake is a solid three-by-three face and so is a Gas Turbine's exhaust
 * end, and that is the entire reason both machines are three wide and three tall: a player who
 * builds the two in line finds that they fit, and a player who builds them side by side finds
 * that they do not. The layout constraint is the mechanic, so the geometry has to state it.
 * <p>
 * What is inside is a real HRSG in miniature and in the right order. Gas enters at depth 0
 * through the <em>inlet bonnet</em> -- the one course that must stay solid across its whole face,
 * because that face is the seal against the turbine -- crosses three courses of finned
 * <em>tube bank</em>, and leaves through the <em>cold-end plenum</em> at depth 4, by which point
 * the useful heat has been taken out of it. Above the tube bank, standing clear of the casing on
 * both sides, runs the <em>steam drum</em>: the one part of the machine you can see working, and
 * the reason the silhouette is a box with a spine rather than a plain box.
 * <p>
 * The two ends carry the two frames, which are the only trigger blocks. The far one is the
 * master, and that is not arbitrary: once the machine is docked, the near end is buried against
 * forty-two blocks of turbine and cannot be reached, so the head a player has to be able to
 * hammer, wire and pipe has to be the one at the cold end.
 * <p>
 * As with the turbine, the shape is a character table and the {@code ItemStack} templates are
 * built on first use rather than in a static initialiser: a structure that half-matches fails
 * silently -- the player hammers it, nothing happens, and nothing is logged -- so the shape is
 * kept loadable, and therefore testable, outside a running game.
 * <p>
 * Every row of the table is a palindrome across the width, so the machine has no mirrored variant
 * and formation never has to check for one.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockHRSG implements IMultiblock
{
	public static final MultiblockHRSG instance = new MultiblockHRSG();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.HRSG_SIZE;
	private static final int HEIGHT = PetroleumGeometry.HRSG_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.HRSG_DEPTH;
	private static final int WIDTH = PetroleumGeometry.HRSG_WIDTH;

	/**
	 * Steel sheetmetal: the skid under the unit and the gas-tight casing round it.
	 */
	public static final char SHELL = 'S';
	/**
	 * Radiators: the finned tube banks the exhaust crosses. The one block in the mod that already
	 * reads as a heat exchanger, which is exactly what this machine is made of.
	 */
	public static final char BANK = 'R';
	/**
	 * Heavy engineering: the steam drum along the spine.
	 */
	public static final char DRUM = 'H';
	/**
	 * Oilfield frame: the inlet bonnet at the turbine end and the outlet head at the cold end.
	 */
	public static final char FRAME = 'F';
	/**
	 * Nothing: the air either side of the drum.
	 */
	public static final char EMPTY = '.';

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the intake
	 * that docks against the turbine's exhaust and depth 4 the cold end.
	 */
	private static final String[][] SHAPE = {
			//The skid. One continuous raft, as the turbine's is: the two machines are craned onto
			//a shared foundation and their bases have to read as one plant rather than two boxes
			//that happen to touch.
			{"SSS", "SSS", "SSS", "SSS", "SSS"},
			//The gas path. Bonnet, three courses of tube bank, outlet head. The banks run the full
			//width on purpose -- the exhaust crosses the whole duct, not a core down the middle,
			//and the turbine's nacelle already owns the cased-core silhouette.
			{"SFS", "RRR", "RRR", "RRR", "SFS"},
			//The drum course. Solid at both ends because the intake face has to seal against the
			//turbine and the cold end has to close the duct, bare down the middle because the drum
			//is meant to be visible sitting on top of the casing.
			{"SSS", ".H.", ".H.", ".H.", "SSS"}};

	/**
	 * The outlet head at the cold end: master of the assembled machine, where the steam leaves
	 * and where a wire lands. The far end from the turbine, because the near end is unreachable
	 * once the machine is docked.
	 */
	public static final int MASTER_POS = PetroleumGeometry.structureIndex(SIZE, 1, DEPTH-1, WIDTH/2);
	/**
	 * The inlet bonnet, at the middle of the face that butts against the turbine's exhaust.
	 */
	public static final int BONNET_POS = PetroleumGeometry.structureIndex(SIZE, 1, 0, WIDTH/2);
	/**
	 * The depth the intake face sits at. Named rather than written as a zero, because every
	 * adjacency test in {@link TileEntityHRSG} is about this face and no other.
	 */
	public static final int INTAKE_DEPTH = 0;

	/**
	 * @return the structure index of a cell of the three-by-three intake face, which is the face
	 * that has to be against a Gas Turbine's exhaust end for the machine to do anything at all
	 */
	public static int intakePos(int h, int w)
	{
		return PetroleumGeometry.structureIndex(SIZE, h, INTAKE_DEPTH, w);
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
	 * @return how many blocks of the given material a complete unit is built from
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
		return "IE:HRSG";
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
			case BANK:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.RADIATOR.getMeta());
			case DRUM:
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
					//Sheetmetal by ore name, so a block from any mod that registers one counts --
					//the same courtesy the tower and the turbine already extend.
					new IngredientStack(ORE_SHELL, blockCount(SHELL)),
					new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, blockCount(BANK),
							BlockTypes_MetalDecoration0.RADIATOR.getMeta())),
					new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, blockCount(DRUM),
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
		return 12;
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
					BlockTypes_PetroleumMultiblock.HRSG.getMeta());
		GlStateManager.translate(2.5, 1.5, 1.5);
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
		//The frame, and not the casing: sheetmetal is everywhere in a built-up base, and a
		//forty-five block check on every hammered sheet would cost more than the structure is
		//worth.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A hit on the top face carries no useful direction, so fall back to where the player is
		//standing, as the turbine and the burner do.
		EnumFacing guess = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw): side.getOpposite();
		//Only the frame is a trigger and the frame sits in exactly two cells, so the player has
		//hammered one of those two. Orientation is then read off the structure itself rather than
		//trusted from the click: the guess is tried first so a correctly-approached machine forms
		//on the first check, but a unit built pointing the other way still forms rather than
		//refusing in silence. That matters more here than anywhere else in the expansion -- a
		//backwards HRSG is a machine that will never couple, and the player would have no idea
		//why.
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
			case BANK:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.RADIATOR.getMeta());
			case DRUM:
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case FRAME:
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			default:
				//The air either side of the drum. Left unchecked on purpose: a steam pipe run along
				//the flank of the drum is the intended use of the machine, so refusing to form
				//round one would be refusing the assembly a player has just correctly built.
				return true;
		}
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code getBlockPosForPos} does, so the
	 * machine can address its own parts -- and find its turbine -- after formation.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.HRSG.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cell(origin, facing, 1, DEPTH-1, WIDTH/2);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityHRSG)
					{
						TileEntityHRSG part = (TileEntityHRSG)te;
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
