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
import blusunrize.immersiveengineering.common.blocks.fluidnet.BlockTypes_FluidNetDevice;
import blusunrize.immersiveengineering.common.blocks.fluidnet.BlockTypes_FluidNetMultiblock;
import blusunrize.immersiveengineering.common.blocks.fluidnet.FluidConsoleGeometry;
import blusunrize.immersiveengineering.common.blocks.fluidnet.TileEntityFluidConsole;
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
 * The Fluid Control Console: four Fluid Console Housing blocks in a 2 wide x 2 tall wall, hammered
 * on the face that becomes the screen.
 * <p>
 * Structure indices are H*L*W with L (depth) fixed at 1, so {@code pos} is simply {@code h*2 + w}.
 * The master is the bottom-left block as seen from the front.
 * <p>
 * The deliberate mirror of {@code MultiblockGridConsole}, gesture for gesture: a player who has
 * built one console knows how to build the other, which is worth more than any variety a different
 * shape would buy.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class MultiblockFluidConsole implements IMultiblock
{
	public static MultiblockFluidConsole instance = new MultiblockFluidConsole();

	/**
	 * H, L, W
	 */
	static ItemStack[][][] structure = new ItemStack[2][1][2];

	static
	{
		for(int h = 0; h < 2; h++)
			for(int w = 0; w < 2; w++)
				structure[h][0][w] = new ItemStack(IEContent.blockFluidNetDevice, 1,
						BlockTypes_FluidNetDevice.CONSOLE_HOUSING.getMeta());
	}

	@Override
	public String getUniqueName()
	{
		return "IE:FluidConsole";
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
		return 25;
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
				new ItemStack(IEContent.blockFluidNetMultiblock, 1,
						BlockTypes_FluidNetMultiblock.FLUID_CONSOLE.getMeta()),
				ItemCameraTransforms.TransformType.GUI);
	}

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		return Utils.blockstateMatches(state, IEContent.blockFluidNetDevice,
				BlockTypes_FluidNetDevice.CONSOLE_HOUSING.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//The console is a wall panel, so only its front face is meaningful. Reject a top/bottom hit
		//outright rather than guessing an orientation.
		if(side.getAxis()==EnumFacing.Axis.Y)
			return false;
		EnumFacing front = side;
		EnumFacing right = front.rotateYCCW();

		//The clicked block may be any of the four, so try every origin that would put it inside the
		//square.
		for(BlockPos origin : FluidConsoleGeometry.candidateOrigins(pos, right))
			if(matches(world, origin, right)&&form(world, origin, front, right, player))
				return true;
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing right)
	{
		for(BlockPos cell : FluidConsoleGeometry.cells(origin, right))
			if(!isHousing(world, cell))
				return false;
		return true;
	}

	private static boolean isHousing(World world, BlockPos pos)
	{
		return Utils.isBlockAt(world, pos, IEContent.blockFluidNetDevice,
				BlockTypes_FluidNetDevice.CONSOLE_HOUSING.getMeta());
	}

	private boolean form(World world, BlockPos origin, EnumFacing front, EnumFacing right,
						 EntityPlayer player)
	{
		ItemStack hammer = player.getHeldItemMainhand().getItem()
				.getToolClasses(player.getHeldItemMainhand()).contains(Lib.TOOL_HAMMER)
				?player.getHeldItemMainhand(): player.getHeldItemOffhand();
		if(MultiblockHandler.fireMultiblockFormationEventPost(player, this, origin, hammer).isCanceled())
			return false;

		IBlockState state = IEContent.blockFluidNetMultiblock
				.getStateFromMeta(BlockTypes_FluidNetMultiblock.FLUID_CONSOLE.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, front);
		for(int h = 0; h < FluidConsoleGeometry.HEIGHT; h++)
			for(int w = 0; w < FluidConsoleGeometry.WIDTH; w++)
			{
				BlockPos target = origin.add(0, h, 0).offset(right, w);
				world.setBlockState(target, state);
				TileEntity te = world.getTileEntity(target);
				if(te instanceof TileEntityFluidConsole)
				{
					TileEntityFluidConsole part = (TileEntityFluidConsole)te;
					part.formed = true;
					part.facing = front;
					part.pos = FluidConsoleGeometry.structureIndex(h, w);
					part.offset = new int[]{
							target.getX()-origin.getX(),
							target.getY()-origin.getY(),
							target.getZ()-origin.getZ()};
					part.markDirty();
					world.addBlockEvent(target, IEContent.blockFluidNetMultiblock, 255, 0);
				}
			}
		return true;
	}

	static final IngredientStack[] materials = new IngredientStack[]{
			new IngredientStack(new ItemStack(IEContent.blockFluidNetDevice, 4,
					BlockTypes_FluidNetDevice.CONSOLE_HOUSING.getMeta()))};

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		return materials;
	}
}
