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
import blusunrize.immersiveengineering.common.blocks.petroleum.BuriedTankGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.BuriedTankGeometry.Tier;
import blusunrize.immersiveengineering.common.blocks.petroleum.PetroleumGeometry;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityBuriedTank;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * A tank you put in the ground: a shell of sheetmetal with a Tank Fill Cap let into the middle
 * of its roof, and nothing else showing.
 * <p>
 * One class at three scales rather than three near-identical classes. Everything that differs
 * between a domestic tank and a bulk depot is in {@link Tier} -- the box, the capacity, the metal
 * it is made of -- and everything that does not is here once. That is worth doing because the
 * part most likely to be wrong is the shape arithmetic, and there is now one copy of it to get
 * right instead of three to keep in step.
 * <p>
 * <strong>Burial is enforced, not suggested.</strong> The structure forms only if every block of
 * its roof except the cap has something on top of it. Without that check the "buried" tank would
 * be a box a player could stand in a field, which is exactly the eyesore the tier exists to
 * avoid, and the existing Sheetmetal Tank is already the above-ground answer. When the shape is
 * right and only the cover is missing the player is told so -- a silent refusal here would be
 * unusually cruel, because the tank the player has just built looks completely correct.
 * <p>
 * The three tiers cannot be confused for one another even though they are the same shape:
 * the middles are hollow, so a smaller tank's footprint laid over a larger one's roof always
 * lands on a void cell, and each tier is a different metal besides.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MultiblockBuriedTank implements IMultiblock
{
	public static final MultiblockBuriedTank domestic = new MultiblockBuriedTank(
			BuriedTankGeometry.DOMESTIC, BlockTypes_PetroleumMultiblock.DOMESTIC_TANK,
			BlockTypes_MetalsAll.IRON, "blockSheetmetalIron", "IE:BuriedTankDomestic", 22);
	public static final MultiblockBuriedTank commercial = new MultiblockBuriedTank(
			BuriedTankGeometry.COMMERCIAL, BlockTypes_PetroleumMultiblock.COMMERCIAL_TANK,
			BlockTypes_MetalsAll.STEEL, "blockSheetmetalSteel", "IE:BuriedTankCommercial", 12);
	public static final MultiblockBuriedTank bulk = new MultiblockBuriedTank(
			BuriedTankGeometry.BULK, BlockTypes_PetroleumMultiblock.BULK_DEPOT,
			BlockTypes_MetalsAll.STEEL, "blockSheetmetalSteel", "IE:BuriedTankBulk", 7);

	public static final MultiblockBuriedTank[] ALL = {domestic, commercial, bulk};

	public final Tier tier;
	private final BlockTypes_PetroleumMultiblock blockType;
	private final BlockTypes_MetalsAll metal;
	private final String uniqueName;
	private final float manualScale;
	/**
	 * The ore name the walls are matched against, so sheetmetal from any mod that registers it
	 * counts -- the same courtesy every other structure in this expansion extends.
	 */
	private final String wallOre;

	private MultiblockBuriedTank(Tier tier, BlockTypes_PetroleumMultiblock blockType,
								 BlockTypes_MetalsAll metal, String wallOre, String uniqueName,
								 float manualScale)
	{
		this.tier = tier;
		this.blockType = blockType;
		this.metal = metal;
		this.wallOre = wallOre;
		this.uniqueName = uniqueName;
		this.manualScale = manualScale;
	}

	@Override
	public String getUniqueName()
	{
		return uniqueName;
	}

	public BlockTypes_PetroleumMultiblock getBlockType()
	{
		return blockType;
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
			structure = new ItemStack[tier.height][tier.depth][tier.width];
			for(int h = 0; h < tier.height; h++)
				for(int l = 0; l < tier.depth; l++)
					for(int w = 0; w < tier.width; w++)
						structure[h][l][w] = manualStack(h, l, w);
		}
		return structure;
	}

	private ItemStack manualStack(int h, int l, int w)
	{
		if(!tier.isPart(h, l, w))
			return null;
		if(tier.isCap(h, l, w))
			return new ItemStack(IEContent.blockPetroleumDevice, 1,
					BlockTypes_PetroleumDevice.TANK_FILL_CAP.getMeta());
		return new ItemStack(IEContent.blockSheetmetal, 1, metal.getMeta());
	}

	private IngredientStack[] materials;

	@Override
	public IngredientStack[] getTotalMaterials()
	{
		if(materials==null)
			materials = new IngredientStack[]{
					new IngredientStack(wallOre, tier.wallCount()),
					new IngredientStack(new ItemStack(IEContent.blockPetroleumDevice, 1,
							BlockTypes_PetroleumDevice.TANK_FILL_CAP.getMeta()))};
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
		return manualScale;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean canRenderFormedStructure()
	{
		return false;
	}

	/**
	 * Built on first use rather than in the constructor, so nothing about this class touches the
	 * item registry until a manual page is actually drawn.
	 */
	private ItemStack renderStack;

	@Override
	@SideOnly(Side.CLIENT)
	public void renderFormedStructure()
	{
		if(renderStack==null)
			renderStack = new ItemStack(IEContent.blockPetroleumMultiblock, 1, blockType.getMeta());
		GlStateManager.translate(tier.depth/2f, tier.height/2f, tier.width/2f);
		ClientUtils.mc().getRenderItem().renderItem(renderStack, ItemCameraTransforms.TransformType.GUI);
	}

	//	=================================
	//		FORMATION
	//	=================================

	@Override
	public boolean isBlockTrigger(IBlockState state)
	{
		//The cap alone. Sheetmetal is everywhere in a built-up base and a bulk depot is a
		//two-hundred-and-ninety cell check, so triggering on the walls would make hammering a
		//stray sheet one of the more expensive things a player can do.
		return Utils.blockstateMatches(state, IEContent.blockPetroleumDevice,
				BlockTypes_PetroleumDevice.TANK_FILL_CAP.getMeta());
	}

	@Override
	public boolean createStructure(World world, BlockPos pos, EnumFacing side, EntityPlayer player)
	{
		//The cap sits in exactly one cell, so there is one origin to try per orientation rather
		//than one per matching cell. The box is symmetric across both horizontal axes, so
		//whichever orientation is checked first is the one that matches -- the loop exists so the
		//stored facing is a real facing rather than an assumed one.
		for(EnumFacing facing : EnumFacing.HORIZONTALS)
		{
			BlockPos origin = pos.add(0, -(tier.height-1), 0)
					.offset(facing, -(tier.depth/2)).offset(facing.rotateY(), -(tier.width/2));
			if(!matches(world, origin, facing))
				continue;
			if(!isBuried(world, origin, facing))
			{
				//The shape is right and only the cover is missing. Saying so costs one message and
				//saves the player from dismantling a tank that was never wrong.
				if(!world.isRemote)
					ChatUtils.sendServerNoSpamMessages(player,
							new TextComponentTranslation(Lib.CHAT_INFO+"petroleum.tankNotBuried")
									.setStyle(new Style().setColor(TextFormatting.YELLOW)));
				return false;
			}
			return form(world, origin, facing, player);
		}
		return false;
	}

	private boolean matches(World world, BlockPos origin, EnumFacing facing)
	{
		for(int h = 0; h < tier.height; h++)
			for(int l = 0; l < tier.depth; l++)
				for(int w = 0; w < tier.width; w++)
				{
					//The hollow middle is deliberately unchecked rather than required to be air.
					//A player who backfilled the hole and tunnelled the shell in place has built a
					//tank; making them clear the inside as well would be busywork for a volume
					//nothing can ever reach.
					if(!tier.isPart(h, l, w))
						continue;
					BlockPos cell = cell(origin, facing, h, l, w);
					if(tier.isCap(h, l, w))
					{
						if(!Utils.isBlockAt(world, cell, IEContent.blockPetroleumDevice,
								BlockTypes_PetroleumDevice.TANK_FILL_CAP.getMeta()))
							return false;
					}
					else if(!Utils.isOreBlockAt(world, cell, wallOre))
						return false;
				}
		return true;
	}

	/**
	 * @return whether the roof is covered everywhere but the cap
	 */
	private boolean isBuried(World world, BlockPos origin, EnumFacing facing)
	{
		int h = tier.height-1;
		for(int l = 0; l < tier.depth; l++)
			for(int w = 0; w < tier.width; w++)
			{
				if(!tier.isRoof(h, l, w)||tier.isCap(h, l, w))
					continue;
				//Air is the only thing rejected, so a slab, a path block, a carpet or a road all
				//count as cover. The rule is "you cannot see the tank", not "you must use dirt".
				if(world.isAirBlock(cell(origin, facing, h, l, w).up()))
					return false;
			}
		return true;
	}

	/**
	 * Maps a structure cell to a world position exactly as {@code getBlockPosForPos} does, so the
	 * tank can address its own parts after formation.
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

		IBlockState state = IEContent.blockPetroleumMultiblock.getStateFromMeta(blockType.getMeta())
				.withProperty(IEProperties.FACING_HORIZONTAL, facing);
		//The cap is the master, not the origin corner. Nothing in TileEntityMultiblockPart requires
		//the two to be the same block -- master() is resolved from the offsets and getBlockPosForPos
		//from the structure index -- and putting the state on the cap means the tank's overlay, its
		//comparator reading and its client sync are all local to the one block a player can see.
		//On the origin corner they would be a cross-chunk tile-entity lookup from a buried block,
		//which on a nine by nine depot is a different chunk from the cap often enough to matter.
		BlockPos masterPos = cell(origin, facing, tier.height-1, tier.depth/2, tier.width/2);
		for(int h = 0; h < tier.height; h++)
			for(int l = 0; l < tier.depth; l++)
				for(int w = 0; w < tier.width; w++)
				{
					if(!tier.isPart(h, l, w))
						continue;
					BlockPos target = cell(origin, facing, h, l, w);
					world.setBlockState(target, state);
					TileEntity te = world.getTileEntity(target);
					if(te instanceof TileEntityBuriedTank)
					{
						TileEntityBuriedTank<?> part = (TileEntityBuriedTank<?>)te;
						part.formed = true;
						part.facing = facing;
						part.pos = PetroleumGeometry.structureIndex(tier.size, h, l, w);
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
