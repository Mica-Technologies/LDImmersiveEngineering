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
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration0;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityPumpjack;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The Pumpjack: a walking beam on a samson post, driven by a motor at the back, nodding over the
 * well it stands on.
 * <p>
 * The silhouette is the point, so the shape spends its height on the beam rather than on bulk:
 * a low skid, an open bay at the front for the well, a lattice post at the middle and a beam
 * across the top that overhangs the bay. The machine is symmetric about its centre line, which
 * is why -- unlike most IE structures -- there is no mirrored variant to check.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockPumpjack implements IMultiblock
{
	public static final MultiblockPumpjack instance = new MultiblockPumpjack();

	/**
	 * The shape: {@code SHAPE[height][depth]}, one character per width, depth 0 being the front
	 * where the horsehead hangs.
	 * <p>
	 * A table rather than the usual nest of coordinate conditionals because the manual template
	 * and the world check have to agree exactly, and a structure that half-matches fails silently
	 * -- the player just hammers a machine that never forms and is told nothing.
	 * <p>
	 * {@code F} oilfield frame, {@code S} steel scaffolding, {@code E} heavy engineering,
	 * {@code L} light engineering, {@code W} the well bay, {@code .} not part of the structure.
	 */
	private static final String[][] SHAPE = {
			//The skid. Its front edge opens around the well instead of sitting on top of it, so
			//the machine stands flush on the ground rather than on stilts above the wellhead.
			{"FWF", "FFF", "FFF", "FFF", "FFF", "EEE"},
			//Samson post legs, crank housing, prime mover.
			{"...", "...", "S.S", "...", ".L.", "EEE"},
			//The post closes to full width; the counterweight rides beside it.
			{"...", "...", "SSS", "...", ".L.", "..."},
			//Pivot bearing, pitman arm, and the horsehead hanging off the front of the beam.
			{".F.", "...", ".S.", "...", ".L.", "..."},
			//The walking beam, overhanging the bay so the horsehead nods over the well.
			{".F.", ".F.", ".F.", ".F.", ".F.", "..."}};

	/**
	 * The block the player hammers, and the master: the middle of the skid's front edge, right
	 * behind the well bay. It is the one part of the machine that is always at standing height
	 * and always reachable -- the beam is four blocks further up.
	 */
	private static final int MASTER_HEIGHT = 0;
	private static final int MASTER_DEPTH = 1;
	private static final int MASTER_WIDTH = 1;

	/**
	 * H, L, W
	 */
	static ItemStack[][][] structure = new ItemStack[PetroleumGeometry.PUMPJACK_HEIGHT][PetroleumGeometry.PUMPJACK_DEPTH][PetroleumGeometry.PUMPJACK_WIDTH];

	static
	{
		for(int h = 0; h < PetroleumGeometry.PUMPJACK_HEIGHT; h++)
			for(int l = 0; l < PetroleumGeometry.PUMPJACK_DEPTH; l++)
				for(int w = 0; w < PetroleumGeometry.PUMPJACK_WIDTH; w++)
					structure[h][l][w] = manualStack(SHAPE[h][l].charAt(w));
	}

	private static ItemStack manualStack(char cell)
	{
		switch(cell)
		{
			case 'F':
				return new ItemStack(IEContent.blockPetroleumDevice, 1,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			case 'S':
				return new ItemStack(IEContent.blockMetalDecoration1, 1,
						BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
			case 'E':
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case 'L':
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta());
			case 'W':
				//Drawn so the manual page makes the siting obvious, but deliberately absent from
				//the materials list: a wellhead is what drilling leaves behind, not a part you
				//build the machine out of.
				return new ItemStack(IEContent.blockPetroleumDevice, 1,
						BlockTypes_PetroleumDevice.WELLHEAD.getMeta());
			default:
				return null;
		}
	}

	@Override
	public String getUniqueName()
	{
		return "IE:Pumpjack";
	}

	@Override
	public ItemStack[][][] getStructureManual()
	{
		return structure;
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

	static ItemStack renderStack = ItemStack.EMPTY;

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		if(renderStack.isEmpty())
			renderStack = new ItemStack(IEContent.blockPetroleumMultiblock, 1,
					BlockTypes_PetroleumMultiblock.PUMPJACK.getMeta());
		GlStateManager.translate(2.5, 1.5, 1.5);
		GlStateManager.rotate(-45, 0, 1, 0);
		GlStateManager.rotate(-20, 1, 0, 0);
		GlStateManager.scale(5.5, 5.5, 5.5);
		GlStateManager.disableCull();
		ClientUtils.mc().getRenderItem().renderItem(renderStack, ItemCameraTransforms.TransformType.GUI);
		GlStateManager.enableCull();
	}

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		EnumFacing facing = side.getOpposite();
		//A hit on the top or bottom of the skid carries no useful direction -- and the top is the
		//easy face to reach once the well is already standing in the bay -- so fall back to where
		//the player is standing, as the diesel generator does.
		if(facing.getAxis()==EnumFacing.Axis.Y)
			facing = EnumFacing.fromAngle(player.rotationYaw);
		BlockPos origin = pos.offset(facing, -MASTER_DEPTH).offset(facing.rotateY(), -MASTER_WIDTH);
		if(!matches(world, origin, facing))
			return false;

		ItemStack hammer = player.getHeldItemMainhand().getItem()
				.getToolClasses(player.getHeldItemMainhand()).contains(Lib.TOOL_HAMMER)
				?player.getHeldItemMainhand(): player.getHeldItemOffhand();
		if(MultiblockHandler.fireMultiblockFormationEventPost(player, this, pos, hammer).isCanceled())
			return false;

		form(world, origin, facing);
		return true;
	}

	private static boolean matches(World world, BlockPos origin, EnumFacing facing)
	{
		for(int h = 0; h < PetroleumGeometry.PUMPJACK_HEIGHT; h++)
			for(int l = 0; l < PetroleumGeometry.PUMPJACK_DEPTH; l++)
				for(int w = 0; w < PetroleumGeometry.PUMPJACK_WIDTH; w++)
					if(!cellMatches(world, cellPos(origin, facing, h, l, w), SHAPE[h][l].charAt(w)))
						return false;
		return true;
	}

	private static boolean cellMatches(World world, BlockPos cell, char type)
	{
		switch(type)
		{
			case 'F':
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
			case 'S':
				return Utils.isOreBlockAt(world, cell, "scaffoldingSteel");
			case 'E':
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
			case 'L':
				return Utils.isBlockAt(world, cell, IEContent.blockMetalDecoration0,
						BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta());
			case 'W':
				//The bay has to be clear for the polished rod, but the well itself is welcome in
				//it -- standing over the well is the entire reason the machine is sited here.
				return Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
						BlockTypes_PetroleumDevice.WELLHEAD.getMeta())
						||world.getBlockState(cell).getBlock().isReplaceable(world, cell);
			default:
				//Cells the shape does not use are neither checked nor touched, so a pumpjack can
				//be cut into a hillside like every other IE multiblock.
				return true;
		}
	}

	private void form(World world, BlockPos origin, EnumFacing facing)
	{
		IBlockState state = IEContent.blockPetroleumMultiblock
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.PUMPJACK.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		BlockPos masterPos = cellPos(origin, facing, MASTER_HEIGHT, MASTER_DEPTH, MASTER_WIDTH);
		for(int h = 0; h < PetroleumGeometry.PUMPJACK_HEIGHT; h++)
			for(int l = 0; l < PetroleumGeometry.PUMPJACK_DEPTH; l++)
				for(int w = 0; w < PetroleumGeometry.PUMPJACK_WIDTH; w++)
				{
					char cell = SHAPE[h][l].charAt(w);
					//The bay keeps whatever is standing in it -- overwriting it would eat the
					//very wellhead the machine exists to drive.
					if(cell=='.'||cell=='W')
						continue;
					BlockPos target = cellPos(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityPumpjack)
					{
						TileEntityPumpjack part = (TileEntityPumpjack)te;
						part.formed = true;
						part.facing = facing;
						part.pos = PetroleumGeometry.structureIndex(
								PetroleumGeometry.PUMPJACK_SIZE, h, l, w);
						part.offset = new int[]{
								target.getX()-masterPos.getX(),
								target.getY()-masterPos.getY(),
								target.getZ()-masterPos.getZ()};
						part.markDirty();
						world.addBlockEvent(target, IEContent.blockPetroleumMultiblock, 255, 0);
					}
				}
	}

	/**
	 * Maps a structure cell to a world position the same way {@code getBlockPosForPos} does, so
	 * the machine can address its own parts after formation.
	 */
	private static BlockPos cellPos(BlockPos origin, EnumFacing facing, int h, int l, int w)
	{
		return origin.offset(facing, l).offset(facing.rotateY(), w).add(0, h, 0);
	}

	static final IngredientStack[] materials = new IngredientStack[]{
			new IngredientStack(new ItemStack(IEContent.blockPetroleumDevice, 20,
					BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta())),
			new IngredientStack("scaffoldingSteel", 6),
			new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, 6,
					BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta())),
			new IngredientStack(new ItemStack(IEContent.blockMetalDecoration0, 3,
					BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta()))};

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		return materials;
	}
}
