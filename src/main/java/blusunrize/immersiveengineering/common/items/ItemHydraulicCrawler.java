/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.items;

import blusunrize.immersiveengineering.common.entities.CrawlerConfig;
import blusunrize.immersiveengineering.common.entities.EntityHydraulicCrawler;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Hydraulic Crawler, folded up small enough to carry.
 * <p>
 * A machine that big is delivered rather than built in place, which is why it is an item that becomes
 * an entity instead of a multiblock that becomes one. It also means a player can take it away again:
 * see {@code EntityHydraulicCrawler.dismantle}.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public class ItemHydraulicCrawler extends ItemIEBase
{
	public ItemHydraulicCrawler()
	{
		super("hydraulic_crawler", 1);
	}

	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
									  EnumFacing side, float hitX, float hitY, float hitZ)
	{
		//Only from the side that was clicked, so the machine lands on top of the ground rather than
		//inside it. Clicking a wall puts it against the wall, which is the same rule every block
		//placement uses and so the one a player already knows.
		BlockPos at = pos.offset(side);
		if(world.isRemote)
			return EnumActionResult.SUCCESS;

		EntityHydraulicCrawler crawler = new EntityHydraulicCrawler(world);
		//Centred on the block and sitting on its face. The entity's origin is the middle of its
		//footprint, so the half-width does not enter into the horizontal placement -- only the
		//vertical, and only because an entity's origin is at its feet.
		crawler.setLocationAndAngles(at.getX()+0.5, at.getY(), at.getZ()+0.5,
				//Facing away from the player, which is how somebody unloading a machine would leave it.
				net.minecraft.util.math.MathHelper.wrapDegrees(player.rotationYaw), 0);

		if(!hasRoomFor(world, crawler))
		{
			//Said rather than silently refused: a three by three machine will not fit in most of the
			//places a player tries to put it, and "nothing happened" is indistinguishable from a bug.
			player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(
					"chat.immersiveengineering.info.crawlerNoRoom"), true);
			return EnumActionResult.FAIL;
		}

		//Before it is spawned, so the machine is never briefly in the world with a tank the item
		//was still holding -- and so the first sync a client sees already has the right level on it.
		crawler.readFuelFromItem(player.getHeldItem(hand));
		world.spawnEntity(crawler);
		world.playSound(null, at, net.minecraft.init.SoundEvents.BLOCK_ANVIL_PLACE,
				SoundCategory.NEUTRAL, 0.7F, 0.7F);
		if(!player.capabilities.isCreativeMode)
			player.getHeldItem(hand).shrink(1);
		return EnumActionResult.SUCCESS;
	}

	/**
	 * Say how much diesel it is carrying.
	 * <p>
	 * Because a dismantled machine keeps its fuel, and fuel you cannot see is fuel you assume is
	 * gone -- which is the complaint the tag was added to answer, not a new one to introduce.
	 */
	@Override
	@SideOnly(Side.CLIENT)
	public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
							   ITooltipFlag flag)
	{
		super.addInformation(stack, world, tooltip, flag);
		int fuel = EntityHydraulicCrawler.fuelOnItem(stack);
		if(fuel > 0)
			tooltip.add(I18n.format("desc.immersiveengineering.info.crawlerFuel", fuel,
					CrawlerConfig.fuelCapacity));
	}

	/**
	 * @return true if the machine's footprint is clear of blocks and of anything else solid
	 */
	private boolean hasRoomFor(World world, EntityHydraulicCrawler crawler)
	{
		AxisAlignedBB box = crawler.getEntityBoundingBox();
		//Shrunk a hair before testing. An entity resting exactly on a floor has a box whose lower
		//face is coplanar with the block below it, and an unshrunk test reports the floor it is
		//standing on as an obstruction.
		if(!world.getCollisionBoxes(crawler, box.shrink(0.05)).isEmpty())
			return false;
		return world.checkNoEntityCollision(box, crawler);
	}
}
