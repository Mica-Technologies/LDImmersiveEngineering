/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.compat.waila;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxProvider;
import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxReceiver;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityTeslaCoil;
import blusunrize.immersiveengineering.common.blocks.plant.BlockIECrop;
import blusunrize.immersiveengineering.common.blocks.wooden.TileEntityWoodenBarrel;
import mcp.mobius.waila.api.*;
import net.minecraft.block.Block;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

public class IEWailaDataProvider implements IWailaDataProvider
{
	private static final String NBT_STATUS_LINES = "ldieStatusLines";

	public static void callbackRegister(IWailaRegistrar registrar)
	{
		IEWailaDataProvider dataProvider = new IEWailaDataProvider();
		registrar.registerBodyProvider(dataProvider, BlockIECrop.class);
		registrar.registerBodyProvider(dataProvider, TileEntityWoodenBarrel.class);
		registrar.registerNBTProvider(dataProvider, TileEntityWoodenBarrel.class);
		registrar.registerStackProvider(dataProvider, TileEntityMultiblockPart.class);

		registrar.registerBodyProvider(dataProvider, IFluxReceiver.class);
		registrar.registerNBTProvider(dataProvider, IFluxReceiver.class);
		registrar.registerBodyProvider(dataProvider, IFluxProvider.class);
		registrar.registerNBTProvider(dataProvider, IFluxProvider.class);

		//The grid and fluid network fittings. Registered by interface rather than by class so that
		//anything else that grows a status paragraph later -- conduits, most likely -- gets the
		//overlay for free.
		registrar.registerBodyProvider(dataProvider, IStatusLineProvider.class);
		registrar.registerNBTProvider(dataProvider, IStatusLineProvider.class);
	}


	@Override
	public ItemStack getWailaStack(IWailaDataAccessor accessor, IWailaConfigHandler config)
	{
		if(accessor.getTileEntity() instanceof TileEntityMultiblockPart)
			return new ItemStack(accessor.getBlock(), 1, accessor.getMetadata());
		return ItemStack.EMPTY;
	}

	@Override
	public List<String> getWailaHead(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor, IWailaConfigHandler config)
	{
		return currenttip;
	}

	@Override
	public List<String> getWailaBody(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor, IWailaConfigHandler config)
	{
		Block b = accessor.getBlock();
		TileEntity tile = accessor.getTileEntity();
		if(tile instanceof IStatusLineProvider)
		{
			NBTTagList lines = accessor.getNBTData().getTagList(NBT_STATUS_LINES, 8);
			for(int i = 0; i < lines.tagCount(); i++)
				currenttip.add(lines.getStringTagAt(i));
			return currenttip;
		}
		if(b instanceof BlockIECrop)
		{
			int meta = accessor.getMetadata();
			int min = ((BlockIECrop)b).getMinMeta(meta);
			int max = ((BlockIECrop)b).getMaxMeta(meta);
			if(min==max)
				currenttip.add(String.format("%s : %s", I18n.format("hud.msg.growth"), I18n.format("hud.msg.mature")));
			else
			{
				float growth = ((meta-min)/(float)(max-min))*100f;
				if(growth < 100.0)
					currenttip.add(String.format("%s : %.0f %%", I18n.format("hud.msg.growth"), growth));
				else
					currenttip.add(String.format("%s : %s", I18n.format("hud.msg.growth"), I18n.format("hud.msg.mature")));
			}
			return currenttip;
		}
		else if(tile instanceof TileEntityWoodenBarrel)
		{
			NBTTagCompound tank = accessor.getNBTData().getCompoundTag("tank");
			FluidStack fluid = tank.hasKey("Empty")||tank.isEmpty()
					?null: FluidStack.loadFluidStackFromNBT(tank);
			//	=================================
			//	loadFluidStackFromNBT answers null.
			//	=================================
			//
			// A non-empty tag is not a loadable fluid: the name in it is resolved against the
			// registry, and a barrel holding something from a mod that has since been removed reads
			// back as null. The tag passes both checks above and the tooltip then dereferenced it,
			// so looking at that barrel threw on every frame it was under the crosshair.
			if(fluid!=null)
				currenttip.add(String.format("%s: %d / %d mB", fluid.getLocalizedName(),
						fluid.amount, TileEntityWoodenBarrel.CAPACITY));
			else
				currenttip.add(I18n.format("hud.msg.empty"));
		}
		if(accessor.getNBTData().hasKey("Energy"))
		{
			int cur = accessor.getNBTInteger(accessor.getNBTData(), "Energy");
			int max = accessor.getNBTInteger(accessor.getNBTData(), "MaxStorage");
			if(max > 0&&((ITaggedList)currenttip).getEntries("IFEnergyStorage").size()==0)
				((ITaggedList)currenttip).add(String.format("%d / %d IF", cur, max), "IFEnergyStorage");
			if(tile instanceof TileEntityTeslaCoil&&((ITaggedList)currenttip).getEntries("teslaCoil").size()==0)
			{
				boolean rsInv = accessor.getNBTData().getBoolean("redstoneInverted");
				boolean lowPower = accessor.getNBTData().getBoolean("lowPower");
				((ITaggedList)currenttip).add(I18n.format(Lib.CHAT_INFO+"rsControl."+(rsInv?"invertedOn": "invertedOff")), "teslaCoil");
				currenttip.add(I18n.format(Lib.CHAT_INFO+"tesla."+(lowPower?"lowPower": "highPower")));
			}
		}
		return currenttip;
	}

	@Override
	public List<String> getWailaTail(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor, IWailaConfigHandler config)
	{
		return currenttip;
	}

	@Override
	public NBTTagCompound getNBTData(EntityPlayerMP player, TileEntity te, NBTTagCompound tag, World world, BlockPos pos)
	{
		int cur = -1;
		int max = -1;
		if(te instanceof IFluxReceiver)
		{
			cur = ((IFluxReceiver)te).getEnergyStored(null);
			max = ((IFluxReceiver)te).getMaxEnergyStored(null);
		}
		else if(te instanceof IFluxProvider)
		{
			cur = ((IFluxProvider)te).getEnergyStored(null);
			max = ((IFluxProvider)te).getMaxEnergyStored(null);
		}
		if(cur!=-1)
		{
			tag.setInteger("Energy", cur);
			tag.setInteger("MaxStorage", max);
		}
		if(te instanceof IStatusLineProvider)
		{
			//Sent from the server on every look, because the client only knows anything about a
			//segment while a console window is open -- a tooltip built client-side would go blank
			//the moment you closed the console, which is exactly when you want to read it.
			NBTTagList lines = new NBTTagList();
			for(String line : ((IStatusLineProvider)te).getStatusLines())
				lines.appendTag(new NBTTagString(line));
			tag.setTag(NBT_STATUS_LINES, lines);
		}
		if(te instanceof TileEntityTeslaCoil)
		{
			if(((TileEntityTeslaCoil)te).dummy)
				te = te.getWorld().getTileEntity(te.getPos().offset(((TileEntityTeslaCoil)te).facing, -1));
			//Guarded the way TileEntityTeslaCoil guards its own version of this lookup: an orphaned
			//dummy half points at a position that need not still hold a coil, and an unguarded cast
			//there threw on the server every time somebody looked at the block.
			if(te instanceof TileEntityTeslaCoil)
			{
				tag.setBoolean("redstoneInverted", ((TileEntityTeslaCoil)te).redstoneControlInverted);
				tag.setBoolean("lowPower", ((TileEntityTeslaCoil)te).lowPower);
			}
		}
		else if(te instanceof TileEntityWoodenBarrel)
			tag.setTag("tank", ((TileEntityWoodenBarrel)te).tank.writeToNBT(new NBTTagCompound()));
		return tag;
	}
}
