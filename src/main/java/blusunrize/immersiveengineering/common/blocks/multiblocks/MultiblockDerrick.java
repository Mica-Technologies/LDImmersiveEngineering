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
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityDerrick;
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
 * The Drilling Derrick: a lattice tower nine blocks tall on a three-by-three drill floor,
 * built entirely from Oilfield Frame.
 * <p>
 * Layer by layer, bottom to top: a solid drill floor, corner legs, a girt band halfway up, a
 * solid water table, and a crown block. That silhouette is the whole point -- a derrick has to
 * be recognisable across a landscape, which is why it is tall and open rather than a compact
 * box. Every block of it butts against one below or beside it, so it can be built from the
 * ground up by hand.
 * <p>
 * The shape is a pure predicate ({@link #isPart}) and the {@code ItemStack} template is built
 * on first use rather than in a static initialiser. Every other {@code Multiblock*} class
 * bakes its template statically, which makes the class impossible to load outside a running
 * game -- and the shape is exactly the part that can silently go wrong, with "hammering does
 * nothing, and says nothing" as its failure mode. Deferring the stacks keeps it testable.
 * <p>
 * The footprint is symmetric across its width, so there is no mirrored variant to check for:
 * a mirrored derrick is the same derrick.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockDerrick implements IMultiblock
{
	public static final MultiblockDerrick instance = new MultiblockDerrick();

	/**
	 * H, L, W
	 */
	public static final int[] SIZE = PetroleumGeometry.DERRICK_SIZE;
	private static final int HEIGHT = PetroleumGeometry.DERRICK_HEIGHT;
	private static final int DEPTH = PetroleumGeometry.DERRICK_DEPTH;
	private static final int WIDTH = PetroleumGeometry.DERRICK_WIDTH;

	/**
	 * The braced band halfway up the legs.
	 */
	public static final int GIRT_LAYER = HEIGHT/2;
	/**
	 * The platform the crown sits on.
	 */
	public static final int TABLE_LAYER = HEIGHT-2;
	/**
	 * The crown block, alone at the top of the tower.
	 */
	public static final int CROWN_LAYER = HEIGHT-1;

	/**
	 * @return whether the structure occupies this cell of its {@code H x L x W} box
	 */
	public static boolean isPart(int h, int l, int w)
	{
		if(h < 0||h >= HEIGHT||l < 0||l >= DEPTH||w < 0||w >= WIDTH)
			return false;
		boolean centre = l==DEPTH/2&&w==WIDTH/2;
		//Both solid layers: the drill floor is what the rig stands on and what the bore runs
		//through, and the water table is the platform carrying the crown.
		if(h==0||h==TABLE_LAYER)
			return true;
		if(h==CROWN_LAYER)
			return centre;
		//The girt band leaves the shaft open; every one of its blocks still butts against a leg,
		//so the tower can be built from the ground up without scaffolding.
		if(h==GIRT_LAYER)
			return !centre;
		return (l==0||l==DEPTH-1)&&(w==0||w==WIDTH-1);
	}

	/**
	 * @return how many blocks a complete derrick is built from
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

	@Override
	public String getUniqueName()
	{
		return "IE:Derrick";
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
						if(isPart(h, l, w))
							structure[h][l][w] = frame(1);
		}
		return structure;
	}

	private IngredientStack[] materials;

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		if(materials==null)
			materials = new IngredientStack[]{new IngredientStack(frame(blockCount()))};
		return materials;
	}

	private static ItemStack frame(int count)
	{
		return new ItemStack(IEContent.blockPetroleumDevice, count,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
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
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		GlStateManager.translate(1.5, 4.5, 1.5);
		ClientUtils.mc().getRenderItem().renderItem(
				new ItemStack(IEContent.blockPetroleumMultiblock, 1,
						BlockTypes_PetroleumMultiblock.DERRICK.getMeta()),
				ItemCameraTransforms.TransformType.GUI);
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
		//A tower is hammered from the ground, so the clicked face is normally a side. Falling back
		//to where the player is standing means clicking the drill floor from above still works
		//rather than being a silent no-op.
		EnumFacing facing = side.getAxis()==Axis.Y
				?EnumFacing.fromAngle(player.rotationYaw).getOpposite(): side;

		//The player may hammer any block of the tower, so every cell the clicked block could be
		//is tried as a candidate. Ascending height first, so the common case -- somebody standing
		//at the drill floor -- is found immediately.
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos origin = pos.add(0, -h, 0).offset(facing, -l).offset(facing.rotateY(), -w);
					if(matches(world, origin, facing))
						return form(world, origin, facing, player);
				}
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing facing)
	{
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
					if(isPart(h, l, w)&&!isFrame(world, cell(origin, facing, h, l, w)))
						return false;
		return true;
	}

	private static boolean isFrame(World world, BlockPos pos)
	{
		return Utils.isBlockAt(world, pos, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code TileEntityMultiblockPart}
	 * does, so that {@code getBlockPosForPos} agrees with what was placed here.
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
				.getStateFromMeta(BlockTypes_PetroleumMultiblock.DERRICK.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		for(int h = 0; h < HEIGHT; h++)
			for(int l = 0; l < DEPTH; l++)
				for(int w = 0; w < WIDTH; w++)
				{
					if(!isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityDerrick)
					{
						TileEntityDerrick part = (TileEntityDerrick)te;
						part.formed = true;
						part.facing = facing;
						part.pos = PetroleumGeometry.structureIndex(SIZE, h, l, w);
						part.offset = new int[]{
								target.getX()-origin.getX(),
								target.getY()-origin.getY(),
								target.getZ()-origin.getZ()};
						part.markDirty();
						world.addBlockEvent(target, IEContent.blockPetroleumMultiblock, 255, 0);
					}
				}
		//Deposits are invisible, so a rig sited over nothing would otherwise stand there looking
		//busy forever. The tile entity refuses to drill on its own, but the player has to hear
		//about it at the moment they build, not after a minute of watching nothing happen.
		TileEntity master = world.getTileEntity(origin);
		if(master instanceof TileEntityDerrick)
			((TileEntityDerrick)master).reportSiteTo(player);
		return true;
	}
}
