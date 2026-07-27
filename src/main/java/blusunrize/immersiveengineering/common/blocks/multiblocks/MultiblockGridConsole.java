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
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridDevice;
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridMultiblock;
import blusunrize.immersiveengineering.common.blocks.grid.GridConsoleGeometry;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridConsole;
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
 * The Grid Management Console: four Console Housing blocks in a 2 wide x 2 tall wall,
 * hammered on the face that becomes the screen.
 * <p>
 * Structure indices are H*L*W with L (depth) fixed at 1, so {@code pos} is simply
 * {@code h*2 + w}. The master is the bottom-left block as seen from the front.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class MultiblockGridConsole implements IMultiblock
{
	public static MultiblockGridConsole instance = new MultiblockGridConsole();

	/**
	 * H, L, W
	 */
	static ItemStack[][][] structure = new ItemStack[2][1][2];

	static
	{
		for(int h = 0; h < 2; h++)
			for(int w = 0; w < 2; w++)
				structure[h][0][w] = new ItemStack(IEContent.blockGridDevice, 1,
						BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta());
	}

	@Override
	public String getUniqueName()
	{
		return "IE:GridConsole";
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
				new ItemStack(IEContent.blockGridMultiblock, 1, BlockTypes_GridMultiblock.GRID_CONSOLE.getMeta()),
				ItemCameraTransforms.TransformType.GUI);
	}

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		return Utils.blockstateMatches(state, IEContent.blockGridDevice,
				BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//The console is a wall panel, so only its front face is meaningful. Reject a
		//top/bottom hit outright rather than guessing an orientation.
		if(side.getAxis()==EnumFacing.Axis.Y)
			return false;
		EnumFacing front = side;
		EnumFacing right = front.rotateYCCW();

		//The clicked block may be any of the four, so try every origin that would put it
		//inside the square.
		for(BlockPos origin : GridConsoleGeometry.candidateOrigins(pos, right))
			if(matches(world, origin, right)&&form(world, origin, front, right, player))
				return true;
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing right)
	{
		for(BlockPos cell : GridConsoleGeometry.cells(origin, right))
			if(!isHousing(world, cell))
				return false;
		return true;
	}

	private static boolean isHousing(World world, BlockPos pos)
	{
		return Utils.isBlockAt(world, pos, IEContent.blockGridDevice,
				BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta());
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
				.getStateFromMeta(BlockTypes_GridMultiblock.GRID_CONSOLE.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, front);
		for(int h = 0; h < GridConsoleGeometry.HEIGHT; h++)
			for(int w = 0; w < GridConsoleGeometry.WIDTH; w++)
			{
				BlockPos target = origin.add(0, h, 0).offset(right, w);
				world.setBlockState(target, state);
				TileEntity te = world.getTileEntity(target);
				if(te instanceof TileEntityGridConsole)
				{
					TileEntityGridConsole part = (TileEntityGridConsole)te;
					part.formed = true;
					part.facing = front;
					part.pos = GridConsoleGeometry.structureIndex(h, w);
					part.offset = new int[]{
							target.getX()-origin.getX(),
							target.getY()-origin.getY(),
							target.getZ()-origin.getZ()};
					part.markDirty();
					world.addBlockEvent(target, IEContent.blockGridMultiblock, 255, 0);
				}
			}
		return true;
	}

	static final IngredientStack[] materials = new IngredientStack[]{
			new IngredientStack(new ItemStack(IEContent.blockGridDevice, 4,
					BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta()))};

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		return materials;
	}
}
