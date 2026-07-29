/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.energy.immersiveflux.*;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;

/**
 * @author BluSunrize - 29.11.2016
 */
public class EnergyHelper
{
	public static boolean isFluxItem(ItemStack stack)
	{
		if(stack.isEmpty())
			return false;
		if(stack.getItem() instanceof IFluxContainerItem)
			return true;
		return stack.hasCapability(CapabilityEnergy.ENERGY, null);
	}

	public static int getEnergyStored(ItemStack stack)
	{
		if(stack.isEmpty())
			return 0;
		if(stack.getItem() instanceof IFluxContainerItem)
			return ((IFluxContainerItem)stack.getItem()).getEnergyStored(stack);
		if(stack.hasCapability(CapabilityEnergy.ENERGY, null))
			return stack.getCapability(CapabilityEnergy.ENERGY, null).getEnergyStored();
		return 0;
	}

	public static int getMaxEnergyStored(ItemStack stack)
	{
		if(stack.isEmpty())
			return 0;
		if(stack.getItem() instanceof IFluxContainerItem)
			return ((IFluxContainerItem)stack.getItem()).getMaxEnergyStored(stack);
		if(stack.hasCapability(CapabilityEnergy.ENERGY, null))
			return stack.getCapability(CapabilityEnergy.ENERGY, null).getMaxEnergyStored();
		return 0;
	}

	public static int insertFlux(ItemStack stack, int energy, boolean simulate)
	{
		if(stack.isEmpty())
			return 0;
		if(stack.getItem() instanceof IFluxContainerItem)
			return ((IFluxContainerItem)stack.getItem()).receiveEnergy(stack, energy, simulate);
		if(stack.hasCapability(CapabilityEnergy.ENERGY, null))
			return stack.getCapability(CapabilityEnergy.ENERGY, null).receiveEnergy(energy, simulate);
		return 0;
	}

	public static int extractFlux(ItemStack stack, int energy, boolean simulate)
	{
		if(stack.isEmpty())
			return 0;
		if(stack.getItem() instanceof IFluxContainerItem)
			return ((IFluxContainerItem)stack.getItem()).extractEnergy(stack, energy, simulate);
		if(stack.hasCapability(CapabilityEnergy.ENERGY, null))
			return stack.getCapability(CapabilityEnergy.ENERGY, null).extractEnergy(energy, simulate);
		return 0;
	}

	static HashMap<Item, Boolean> reverseInsertion = new HashMap<Item, Boolean>();

	public static int forceExtractFlux(ItemStack stack, int energy, boolean simulate)
	{
		if(stack.isEmpty())
			return 0;
		Boolean b = reverseInsertion.get(stack.getItem());
		if(b==Boolean.TRUE)
		{
			int stored = getEnergyStored(stack);
			insertFlux(stack, -energy, simulate);
			return stored-getEnergyStored(stack);
		}
		else
		{
			int drawn = extractFlux(stack, energy, simulate);
			if(b==null)
			{
				int stored = getEnergyStored(stack);
				insertFlux(stack, -energy, simulate);
				drawn = stored-getEnergyStored(stack);
				//if reverse insertion was succesful, it'll be the default approach in future
				reverseInsertion.put(stack.getItem(), drawn > 0?Boolean.TRUE: Boolean.FALSE);
			}
			return drawn;
		}
	}

	public static boolean isFluxReceiver(TileEntity tile, EnumFacing facing)
	{
		if(tile==null)
			return false;
		if(tile instanceof IFluxReceiver&&((IFluxReceiver)tile).canConnectEnergy(facing))
			return true;
		if(tile.hasCapability(CapabilityEnergy.ENERGY, facing))
			return tile.getCapability(CapabilityEnergy.ENERGY, facing).canReceive();
		return false;
	}

	/**
	 * Which side of a neighbour will actually take flux, given the side of it that touches you.
	 * <p>
	 * Normally that is the touching side and nothing more. A wire connector is the exception, and
	 * it is an exception worth codifying: an IE connector accepts flux on exactly one side, the
	 * block it is bolted to. Mount one on a grid box and it works. Mount one on the wall <em>beside</em>
	 * that box -- the same gesture as far as a player is concerned, and often the only one the
	 * geometry leaves room for -- and it faces the wall instead. It touches a live box, reports
	 * itself as no kind of receiver, and does nothing, with nothing said about it anywhere.
	 * <p>
	 * So a connector is fed on whichever side it does accept. The rule that replaces "bolt it to
	 * the box" is <em>a connector next to the box is fed by it</em>, which is easier both to state
	 * and to see. Nothing but a connector gets the exemption: on a machine the accepting face is a
	 * real configuration choice, not an artefact of where there was room.
	 * <p>
	 * A relay accepts on no side at all -- it carries wire rather than terminating it -- so this
	 * finds nothing for one, which is correct.
	 *
	 * @return the side to hand energy to, or null if the neighbour will not take any
	 */
	@Nullable
	public static EnumFacing acceptingSide(@Nullable TileEntity target, EnumFacing touching)
	{
		if(target==null)
			return null;
		if(isFluxReceiver(target, touching))
			return touching;
		if(!(target instanceof IImmersiveConnectable&&target instanceof IDirectionalTile))
			return null;
		EnumFacing mount = ((IDirectionalTile)target).getFacing();
		return isFluxReceiver(target, mount)?mount: null;
	}

	public static int insertFlux(TileEntity tile, EnumFacing facing, int energy, boolean simulate)
	{
		if(tile==null)
			return 0;
		if(tile instanceof IFluxReceiver&&((IFluxReceiver)tile).canConnectEnergy(facing))
			return ((IFluxReceiver)tile).receiveEnergy(facing, energy, simulate);
		if(tile.hasCapability(CapabilityEnergy.ENERGY, facing))
			return tile.getCapability(CapabilityEnergy.ENERGY, facing).receiveEnergy(energy, simulate);
		return 0;
	}

	public interface IIEInternalFluxHandler extends IIEInternalFluxConnector, IFluxReceiver, IFluxProvider
	{
		@Nonnull
		FluxStorage getFluxStorage();

		default void postEnergyTransferUpdate(int energy, boolean simulate)
		{

		}

		@Override
		default int extractEnergy(@Nullable EnumFacing fd, int amount, boolean simulate)
		{
			if(((TileEntity)this).getWorld().isRemote||getEnergySideConfig(fd)!=SideConfig.OUTPUT)
				return 0;
			int r = getFluxStorage().extractEnergy(amount, simulate);
			postEnergyTransferUpdate(-r, simulate);
			return r;
		}

		@Override
		default int getEnergyStored(@Nullable EnumFacing fd)
		{
			return getFluxStorage().getEnergyStored();
		}

		@Override
		default int getMaxEnergyStored(@Nullable EnumFacing fd)
		{
			return getFluxStorage().getMaxEnergyStored();
		}

		@Override
		default int receiveEnergy(@Nullable EnumFacing fd, int amount, boolean simulate)
		{
			if(((TileEntity)this).getWorld().isRemote||getEnergySideConfig(fd)!=SideConfig.INPUT)
				return 0;
			int r = getFluxStorage().receiveEnergy(amount, simulate);
			postEnergyTransferUpdate(r, simulate);
			return r;
		}
	}

	public interface IIEInternalFluxConnector extends IFluxConnection
	{
		@Nonnull
		SideConfig getEnergySideConfig(@Nullable EnumFacing facing);

		@Override
		default boolean canConnectEnergy(@Nullable EnumFacing fd)
		{
			return getEnergySideConfig(fd)!=SideConfig.NONE;
		}

		IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing);
	}

	public static class IEForgeEnergyWrapper implements IEnergyStorage
	{
		final IIEInternalFluxConnector fluxHandler;
		public final EnumFacing side;

		public IEForgeEnergyWrapper(IIEInternalFluxConnector fluxHandler, EnumFacing side)
		{
			this.fluxHandler = fluxHandler;
			this.side = side;
		}

		@Override
		public int receiveEnergy(int maxReceive, boolean simulate)
		{
			if(fluxHandler instanceof IIEInternalFluxHandler)
				return ((IIEInternalFluxHandler)fluxHandler).receiveEnergy(side, maxReceive, simulate);
			return 0;
		}

		@Override
		public int extractEnergy(int maxExtract, boolean simulate)
		{
			if(fluxHandler instanceof IIEInternalFluxHandler)
				return ((IIEInternalFluxHandler)fluxHandler).extractEnergy(side, maxExtract, simulate);
			return 0;
		}

		@Override
		public int getEnergyStored()
		{
			if(fluxHandler instanceof IIEInternalFluxHandler)
				return ((IIEInternalFluxHandler)fluxHandler).getEnergyStored(side);
			return 0;
		}

		@Override
		public int getMaxEnergyStored()
		{
			if(fluxHandler instanceof IIEInternalFluxHandler)
				return ((IIEInternalFluxHandler)fluxHandler).getMaxEnergyStored(side);
			return 0;
		}

		@Override
		public boolean canExtract()
		{
			if(fluxHandler instanceof IIEInternalFluxHandler)
				return ((IIEInternalFluxHandler)fluxHandler).getFluxStorage().getLimitExtract() > 0;
			return false;
		}

		@Override
		public boolean canReceive()
		{
			if(fluxHandler instanceof IIEInternalFluxHandler)
				return ((IIEInternalFluxHandler)fluxHandler).getFluxStorage().getLimitReceive() > 0;
			return false;
		}

		public static IEForgeEnergyWrapper[] getDefaultWrapperArray(IIEInternalFluxConnector handler)
		{
			return new IEForgeEnergyWrapper[]{
					new IEForgeEnergyWrapper(handler, EnumFacing.DOWN),
					new IEForgeEnergyWrapper(handler, EnumFacing.UP),
					new IEForgeEnergyWrapper(handler, EnumFacing.NORTH),
					new IEForgeEnergyWrapper(handler, EnumFacing.SOUTH),
					new IEForgeEnergyWrapper(handler, EnumFacing.WEST),
					new IEForgeEnergyWrapper(handler, EnumFacing.EAST)
			};
		}
	}

	public interface IIEEnergyItem extends IFluxContainerItem
	{
		@Override
		default int receiveEnergy(ItemStack container, int energy, boolean simulate)
		{
			return ItemNBTHelper.insertFluxItem(container, energy, getMaxEnergyStored(container), simulate);
		}

		@Override
		default int extractEnergy(ItemStack container, int energy, boolean simulate)
		{
			return ItemNBTHelper.extractFluxFromItem(container, energy, simulate);
		}

		@Override
		default int getEnergyStored(ItemStack container)
		{
			return ItemNBTHelper.getFluxStoredInItem(container);
		}
	}

	public static class ItemEnergyStorage implements IEnergyStorage
	{
		ItemStack stack;
		IIEEnergyItem ieEnergyItem;

		public ItemEnergyStorage(ItemStack item)
		{
			assert (item.getItem() instanceof IIEEnergyItem);
			this.stack = item;
			this.ieEnergyItem = (IIEEnergyItem)item.getItem();
		}

		@Override
		public int receiveEnergy(int maxReceive, boolean simulate)
		{
			return this.ieEnergyItem.receiveEnergy(stack, maxReceive, simulate);
		}

		@Override
		public int extractEnergy(int maxExtract, boolean simulate)
		{
			return this.ieEnergyItem.extractEnergy(stack, maxExtract, simulate);
		}

		@Override
		public int getEnergyStored()
		{
			return this.ieEnergyItem.getEnergyStored(stack);
		}

		@Override
		public int getMaxEnergyStored()
		{
			return this.ieEnergyItem.getMaxEnergyStored(stack);
		}

		@Override
		public boolean canExtract()
		{
			return true;
		}

		@Override
		public boolean canReceive()
		{
			return true;
		}
	}

}
