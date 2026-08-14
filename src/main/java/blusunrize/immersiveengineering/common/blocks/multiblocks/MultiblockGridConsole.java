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
import blusunrize.immersiveengineering.common.blocks.grid.GridConsoleGeometry.Part;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridConsole;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration0;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.Block;
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
 * The Grid Management Console: one Console Housing and one each of IE's three engineering
 * blocks, in a 2 wide x 2 tall wall, hammered on the face that becomes the screen.
 * <pre>
 *     Console Housing     Redstone Engineering
 *     Light Engineering   Heavy Engineering
 * </pre>
 * Four identical housings was the old recipe and it read as "stack four of these", which is
 * not what a control room is: the terminal is the screen, the redstone block is the
 * instrument rack next to it, and the light and heavy blocks are the desk and the cabinet
 * under them.
 * <p>
 * Structure indices are H*L*W with L (depth) fixed at 1, so {@code pos} is simply
 * {@code h*2 + w}. The master is the bottom-left block as seen from the front, and
 * {@code facing} points into the wall -- see {@link GridConsoleGeometry}, where that
 * convention and the reason for it live.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class MultiblockGridConsole implements IMultiblock
{
	public static MultiblockGridConsole instance = new MultiblockGridConsole();

	/**
	 * H, L, W
	 */
	static ItemStack[][][] structure = new ItemStack[GridConsoleGeometry.HEIGHT][GridConsoleGeometry.DEPTH][GridConsoleGeometry.WIDTH];

	/**
	 * The same four components as block and meta, resolved once. {@code isBlockTrigger} runs
	 * for every registered multiblock on every hammer click, and it is not the place to be
	 * building four ItemStacks.
	 */
	private static final Block[] COMPONENT_BLOCKS = new Block[Part.values().length];
	private static final int[] COMPONENT_METAS = new int[Part.values().length];

	static
	{
		for(int h = 0; h < GridConsoleGeometry.HEIGHT; h++)
			for(int w = 0; w < GridConsoleGeometry.WIDTH; w++)
				structure[h][0][w] = componentFor(GridConsoleGeometry.partAt(h, w));
		for(Part part : Part.values())
		{
			ItemStack component = componentFor(part);
			COMPONENT_BLOCKS[part.ordinal()] = Block.getBlockFromItem(component.getItem());
			COMPONENT_METAS[part.ordinal()] = component.getItemDamage();
		}
	}

	/**
	 * The block one part of the console is built from. The arrangement itself lives in
	 * {@link GridConsoleGeometry}, which can be loaded (and tested) without a running game;
	 * only the mapping to real items is here.
	 */
	public static ItemStack componentFor(Part part)
	{
		switch(part)
		{
			case TERMINAL:
				return new ItemStack(IEContent.blockGridDevice, 1,
						BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta());
			case LOGIC:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.RS_ENGINEERING.getMeta());
			case DESK:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta());
			case POWER:
				return new ItemStack(IEContent.blockMetalDecoration0, 1,
						BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta());
		}
		return ItemStack.EMPTY;
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

	/**
	 * Any of the four components triggers, so the hammer works anywhere on the layout rather
	 * than only on the one block that happens to be the terminal. The engineering blocks are
	 * already triggers for the excavator, the refinery and the lightning rod; the handler
	 * simply tries each candidate multiblock until one of them matches.
	 */
	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		for(Part part : Part.values())
			if(Utils.blockstateMatches(state, COMPONENT_BLOCKS[part.ordinal()],
					COMPONENT_METAS[part.ordinal()]))
				return true;
		return false;
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//The console is a wall panel, so only its front face is meaningful. Reject a
		//top/bottom hit outright rather than guessing an orientation.
		if(side.getAxis()==EnumFacing.Axis.Y)
			return false;
		//IE's convention: a formed multiblock's facing points into the structure, away from
		//whoever hammered it, and the structure is laid out along facing and facing.rotateY().
		EnumFacing facing = side.getOpposite();

		//The clicked block may be any of the four, so try every origin that would put it
		//inside the square.
		for(BlockPos origin : GridConsoleGeometry.candidateOrigins(pos, facing))
			if(matches(world, origin, facing)&&form(world, origin, facing, player))
				return true;
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing facing)
	{
		BlockPos[] cells = GridConsoleGeometry.cells(origin, facing);
		for(int i = 0; i < cells.length; i++)
		{
			int part = GridConsoleGeometry.partAt(i).ordinal();
			if(!Utils.isBlockAt(world, cells[i], COMPONENT_BLOCKS[part], COMPONENT_METAS[part]))
				return false;
		}
		return true;
	}

	private boolean form(World world, BlockPos origin, EnumFacing facing, EntityPlayer player)
	{
		ItemStack hammer = player.getHeldItemMainhand().getItem()
				.getToolClasses(player.getHeldItemMainhand()).contains(Lib.TOOL_HAMMER)
				?player.getHeldItemMainhand(): player.getHeldItemOffhand();
		if(MultiblockHandler.fireMultiblockFormationEventPost(player, this, origin, hammer).isCanceled())
			return false;

		IBlockState state = IEContent.blockGridMultiblock
				.getStateFromMeta(BlockTypes_GridMultiblock.GRID_CONSOLE.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		EnumFacing right = facing.rotateY();
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
					part.facing = facing;
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
			new IngredientStack(componentFor(Part.TERMINAL)),
			new IngredientStack(componentFor(Part.LOGIC)),
			new IngredientStack(componentFor(Part.DESK)),
			new IngredientStack(componentFor(Part.POWER))};

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		return materials;
	}
}
