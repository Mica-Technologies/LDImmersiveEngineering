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
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridDevice;
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridMultiblock;
import blusunrize.immersiveengineering.common.blocks.grid.SubstationGeometry;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridSubstation;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.state.IBlockState;
import blusunrize.immersiveengineering.client.ClientUtils;
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
 * Forms a Substation from twelve Substation Frames: three wide, two deep, two tall.
 * <p>
 * Struck on a vertical face like the console, because the yard has a front -- it is the side its
 * two devices exchange power across, and a structure whose orientation depended on where the player
 * happened to be standing would be worse than one that says so.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class MultiblockSubstation implements IMultiblock
{
	public static MultiblockSubstation instance = new MultiblockSubstation();

	static ItemStack[][][] structure =
			new ItemStack[SubstationGeometry.HEIGHT][SubstationGeometry.DEPTH][SubstationGeometry.WIDTH];

	static
	{
		for(int h = 0; h < SubstationGeometry.HEIGHT; h++)
			for(int d = 0; d < SubstationGeometry.DEPTH; d++)
				for(int w = 0; w < SubstationGeometry.WIDTH; w++)
					structure[h][d][w] = new ItemStack(IEContent.blockGridDevice, 1,
							BlockTypes_GridDevice.SUBSTATION_FRAME.getMeta());
	}

	@Override
	public String getUniqueName()
	{
		return "IE:Substation";
	}

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		return Utils.blockstateMatches(state, IEContent.blockGridDevice,
				BlockTypes_GridDevice.SUBSTATION_FRAME.getMeta());
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
		//Smaller than the console's 25: this is twelve blocks against four, so the same scale would
		//push it off the edge of the page.
		return 12;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		GlStateManager.translate(1, 1, 1);
		ClientUtils.mc().getRenderItem().renderItem(
				new ItemStack(IEContent.blockGridMultiblock, 1,
						BlockTypes_GridMultiblock.SUBSTATION.getMeta()),
				ItemCameraTransforms.TransformType.GUI);
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//A yard has a front. Reject a top or bottom hit rather than guessing which way it faces.
		if(side.getAxis()==EnumFacing.Axis.Y)
			return false;
		EnumFacing front = side;
		EnumFacing right = front.rotateYCCW();

		for(BlockPos origin : SubstationGeometry.candidateOrigins(pos, front, right))
			if(matches(world, origin, front, right)&&form(world, origin, front, right, player))
				return true;
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing front, EnumFacing right)
	{
		for(BlockPos cell : SubstationGeometry.cells(origin, front, right))
			if(!Utils.isBlockAt(world, cell, IEContent.blockGridDevice,
					BlockTypes_GridDevice.SUBSTATION_FRAME.getMeta()))
				return false;
		return true;
	}

	static final IngredientStack[] materials = new IngredientStack[]{
			new IngredientStack(new ItemStack(IEContent.blockGridDevice,
					SubstationGeometry.BLOCK_COUNT,
					BlockTypes_GridDevice.SUBSTATION_FRAME.getMeta()))};

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		return materials;
	}

	private boolean form(World world, BlockPos origin, EnumFacing front, EnumFacing right,
						 EntityPlayer player)
	{
		ItemStack hammer = player.getHeldItemMainhand().getItem()
				.getToolClasses(player.getHeldItemMainhand()).contains(Lib.TOOL_HAMMER)
				?player.getHeldItemMainhand(): player.getHeldItemOffhand();
		if(MultiblockHandler.fireMultiblockFormationEventPost(player, this, origin, hammer).isCanceled())
			return false;

		IBlockState state = IEContent.blockGridMultiblock
				.getStateFromMeta(BlockTypes_GridMultiblock.SUBSTATION.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, front);
		for(int h = 0; h < SubstationGeometry.HEIGHT; h++)
			for(int d = 0; d < SubstationGeometry.DEPTH; d++)
				for(int w = 0; w < SubstationGeometry.WIDTH; w++)
				{
					BlockPos target = SubstationGeometry.cell(origin, front, right, h, d, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(!(te instanceof TileEntityGridSubstation))
						continue;
					TileEntityGridSubstation part = (TileEntityGridSubstation)te;
					part.formed = true;
					part.facing = front;
					part.pos = SubstationGeometry.structureIndex(h, d, w);
					part.offset = new int[]{
							target.getX()-origin.getX(),
							target.getY()-origin.getY(),
							target.getZ()-origin.getZ()};
					part.markDirty();
					world.addBlockEvent(target, IEContent.blockGridMultiblock, 255, 0);
				}
		return true;
	}

}
