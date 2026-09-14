/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxReceiver;
import blusunrize.immersiveengineering.api.energy.virtualgen.OutputMeter;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGenConfig;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGeneration;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualSource;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityConnectorLV;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityDynamo;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityThermoelectricGen;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Generation Meter: an in-line pass-through between a generator and the block that carries its power away.
 * <p>
 * <b>Layout.</b> {@code facing} points at the generator -- placing the meter against a dynamo does that,
 * exactly as a connector faces what it is bolted to. The generator pushes into that face; the meter hands
 * every insert straight on out of the opposite face, usually into a wire connector, and holds no energy
 * of its own. A meter in the way changes nothing about how much reaches the wire.
 * <p>
 * <b>What it adds.</b> It records what the generator <em>offered</em> ({@link OutputMeter}) and keeps the
 * plant's {@link VirtualSource} up to date, so that while the plant's chunks are unloaded the wire network
 * goes on receiving that output. Wire networks only: nothing here depends on the virtual grid, a Feed Unit
 * or a console, and it works with the grid switched off.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public class TileEntityGenerationMeter extends TileEntityIEBase implements ITickable, IDirectionalTile,
		IPlayerInteraction, IFluxReceiver
{
	/**
	 * Ticks between registry updates. The measurement itself is taken on every insert; this only decides
	 * how often it is written into the record.
	 */
	private static final int PUBLISH_INTERVAL = 20;

	public EnumFacing facing = EnumFacing.NORTH;
	private final OutputMeter meter = new OutputMeter(VirtualGenConfig.measureWindowSeconds);
	/**
	 * World time this meter started measuring since it was loaded (or since its window changed), or -1
	 * before the first publish. Not saved: see {@link OutputMeter#chooseRate}.
	 */
	private long observingSince = -1;
	private final InputWrapper inputWrapper = new InputWrapper();
	/**
	 * Guards against a loop of meters pointed at one another handing an insert round forever.
	 */
	private boolean forwarding;

	public EnumFacing getInputSide()
	{
		return facing;
	}

	public EnumFacing getOutputSide()
	{
		return facing.getOpposite();
	}

	public BlockPos getGeneratorPos()
	{
		return pos.offset(getInputSide());
	}

	public BlockPos getSourcePos()
	{
		return pos.offset(getOutputSide());
	}

	//	=================================
	//		PASS-THROUGH
	//	=================================

	@Override
	public boolean canConnectEnergy(@Nullable EnumFacing from)
	{
		return from==getInputSide();
	}

	@Override
	public int receiveEnergy(@Nullable EnumFacing from, int amount, boolean simulate)
	{
		if(world==null||world.isRemote||from!=getInputSide()||amount <= 0||forwarding)
			return 0;
		TileEntity target = Utils.getExistingTileEntity(world, getSourcePos());
		int accepted = 0;
		if(target!=null)
		{
			forwarding = true;
			try
			{
				accepted = Math.max(0, EnergyHelper.insertFlux(target, getOutputSide().getOpposite(), amount, simulate));
			} finally
			{
				forwarding = false;
			}
		}
		//The offer, not the acceptance: see OutputMeter. Simulated inserts are probes, not production.
		if(!simulate)
			meter.record(world.getTotalWorldTime(), amount);
		return accepted;
	}

	@Override
	public int getEnergyStored(@Nullable EnumFacing from)
	{
		return 0;
	}

	@Override
	public int getMaxEnergyStored(@Nullable EnumFacing from)
	{
		return 0;
	}

	@Override
	public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing side)
	{
		if(capability==CapabilityEnergy.ENERGY)
			return side==getInputSide();
		return super.hasCapability(capability, side);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing side)
	{
		if(capability==CapabilityEnergy.ENERGY)
			return side==getInputSide()?(T)inputWrapper: null;
		return super.getCapability(capability, side);
	}

	/**
	 * Forge Energy face for other mods' generators and cables. Receive-only, like the flux face.
	 */
	private final class InputWrapper implements IEnergyStorage
	{
		@Override
		public int receiveEnergy(int maxReceive, boolean simulate)
		{
			return TileEntityGenerationMeter.this.receiveEnergy(getInputSide(), maxReceive, simulate);
		}

		@Override
		public int extractEnergy(int maxExtract, boolean simulate)
		{
			return 0;
		}

		@Override
		public int getEnergyStored()
		{
			return 0;
		}

		@Override
		public int getMaxEnergyStored()
		{
			return 0;
		}

		@Override
		public boolean canExtract()
		{
			return false;
		}

		@Override
		public boolean canReceive()
		{
			return true;
		}
	}

	//	=================================
	//		MEASUREMENT AND REGISTRY
	//	=================================

	@Override
	public void update()
	{
		if(world==null||world.isRemote)
			return;
		long time = world.getTotalWorldTime();
		if((time+(pos.getX()^pos.getZ()))%PUBLISH_INTERVAL!=0)
			return;
		publish(time);
	}

	/**
	 * Writes the current measurement into this plant's record, creating it if needed.
	 * <p>
	 * A generator that has stopped offering reads zero here, and zero is published: a plant that is broken,
	 * dismantled or idle while someone is watching it must not keep supplying a town from memory once they
	 * walk away.
	 */
	void publish(long time)
	{
		int window = VirtualGenConfig.measureWindowSeconds;
		if(meter.setWindowChanged(window))
			observingSince = time;
		if(observingSince < 0)
			observingSince = time;
		TileEntity generator = Utils.getExistingTileEntity(world, getGeneratorPos());
		VirtualSource source = VirtualGeneration.INSTANCE.getOrCreate(world.provider.getDimension(), pos,
				getGeneratorPos(), getSourcePos());
		int rate = OutputMeter.chooseRate(meter.getRate(time), source.getMeasuredRate(), time-observingSince, window,
				generator!=null);
		boolean changed = source.updateMeasurement(rate, connectorRate(), isFreeSource(generator),
				generatorId(), time);
		//In front of a Feed Unit the plant supplies a grid segment rather than a wire network.
		changed |= source.setFeedsGrid(Utils.getExistingTileEntity(world, getSourcePos()) instanceof TileEntityGridFeed);
		if(changed)
			VirtualGeneration.INSTANCE.markDirty();
	}

	/**
	 * @return the rate of whatever the meter feeds, or 0 if it is not a wire connector (unknown: the configured
	 * ceiling applies alone)
	 */
	private int connectorRate()
	{
		TileEntity target = Utils.getExistingTileEntity(world, getSourcePos());
		if(target instanceof TileEntityConnectorLV)
			return ((TileEntityConnectorLV)target).getMaxOutput();
		return 0;
	}

	/**
	 * Fuel-free generators run virtually in both modes; everything else only in City Mode. IE's kinetic
	 * dynamo and thermoelectric generator burn nothing; other mods' generators count as free only when a
	 * pack lists them.
	 */
	static boolean isFreeSource(@Nullable TileEntity generator)
	{
		if(generator==null)
			return false;
		if(generator instanceof TileEntityDynamo||generator instanceof TileEntityThermoelectricGen)
			return true;
		ResourceLocation id = generator.getBlockType()==null?null: generator.getBlockType().getRegistryName();
		return id!=null&&VirtualGenConfig.freeSources.contains(id.toString());
	}

	@Nullable
	private String generatorId()
	{
		TileEntity generator = Utils.getExistingTileEntity(world, getGeneratorPos());
		if(generator==null||generator.getBlockType()==null||generator.getBlockType().getRegistryName()==null)
			return null;
		return generator.getBlockType().getRegistryName().toString();
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world!=null&&!world.isRemote)
			//Register at once rather than on the first publish tick, so a plant whose chunk is unloaded again
			//within a second of loading still has a record with the right positions.
			VirtualGeneration.INSTANCE.getOrCreate(world.provider.getDimension(), pos, getGeneratorPos(),
					getSourcePos());
	}

	/**
	 * Called by the block before it is removed: the plant stops being virtual with the meter.
	 */
	public void onBlockBroken()
	{
		if(world!=null&&!world.isRemote)
			VirtualGeneration.INSTANCE.remove(world.provider.getDimension(), pos);
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem, float hitX,
							float hitY, float hitZ)
	{
		if(world.isRemote)
			return true;
		publish(world.getTotalWorldTime());
		for(String line : describe())
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(line));
		return true;
	}

	List<String> describe()
	{
		List<String> lines = new ArrayList<>();
		VirtualSource source = VirtualGeneration.INSTANCE.get(world.provider.getDimension(), pos);
		TileEntity generator = Utils.getExistingTileEntity(world, getGeneratorPos());
		TileEntity target = Utils.getExistingTileEntity(world, getSourcePos());
		lines.add("§6Generation Meter");
		if(generator==null)
			lines.add("No generator on the input side. Place the meter against the generator's output face.");
		else
			lines.add("Generator: "+generator.getClass().getSimpleName().replace("TileEntity", "")
					+(isFreeSource(generator)?" (fuel-free)": " (burns fuel)"));
		if(target==null)
			lines.add("Nothing on the output side. Put the wire connector against the meter's front.");
		else if(target instanceof TileEntityConnectorLV)
			lines.add("Feeds a connector rated "+((TileEntityConnectorLV)target).getMaxOutput()+" IF/t.");
		else if(target instanceof TileEntityGridFeed)
			lines.add("Feeds a Grid Feed Unit: while unloaded the plant keeps supplying its segment.");
		int rate = source==null?0: source.getMeasuredRate();
		lines.add("Measured output: "+rate+" IF/t (peak over the last "+VirtualGenConfig.measureWindowSeconds+" s).");
		if(!VirtualGenConfig.enabled)
			lines.add("Virtual generation is switched off in the server config.");
		else if(source==null||rate <= 0)
			lines.add("While unloaded: nothing -- no output has been measured yet.");
		else if(!source.qualifies(CityMode.wires()))
			lines.add("While unloaded: nothing -- outside City Mode only fuel-free generators keep supplying.");
		else
			lines.add("While unloaded: keeps supplying "+source.getVirtualRate()+" IF/t to loaded consumers.");
		return lines;
	}

	//	=================================
	//		FACING AND NBT
	//	=================================

	@Override
	public EnumFacing getFacing()
	{
		return facing;
	}

	@Override
	public void setFacing(EnumFacing facing)
	{
		this.facing = facing;
	}

	@Override
	public int getFacingLimitation()
	{
		//Side clicked, mirrored: the meter faces the block it was placed against, which is the generator.
		return 0;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		return true;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ, EntityLivingBase entity)
	{
		return false;
	}

	@Override
	public boolean canRotate(EnumFacing axis)
	{
		return false;
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		facing = EnumFacing.byIndex(nbt.getInteger("facing"));
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
	}

}
