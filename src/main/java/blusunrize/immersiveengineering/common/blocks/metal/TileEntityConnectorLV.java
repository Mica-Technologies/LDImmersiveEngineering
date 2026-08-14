/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.TileEntityImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.WireType;
import blusunrize.immersiveengineering.common.Config;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockBounds;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.WireNetTransfer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

//@Optional.Interface(iface = "ic2.api.energy.tile.IEnergySink", modid = "IC2")
public class TileEntityConnectorLV extends TileEntityImmersiveConnectable implements ITickable, IDirectionalTile, IIEInternalFluxHandler, IBlockBounds//, ic2.api.energy.tile.IEnergySink
{
	boolean inICNet = false;
	public EnumFacing facing = EnumFacing.DOWN;
	public int currentTickToMachine = 0;
	public int currentTickToNet = 0;
	public static int[] connectorInputValues = Config.IEConfig.Machines.wireConnectorInput;
	private FluxStorage energyStorage = new FluxStorage(getMaxInput(), getMaxInput(), 0);

	boolean firstTick = true;

	@Override
	public void update()
	{
		if(!world.isRemote)
		{
			//				if(Lib.IC2 && !this.inICNet)
			//				{
			//					IC2Helper.loadIC2Tile(this);
			//					this.inICNet = true;
			//				}
			if(energyStorage.getEnergyStored() > 0)
			{
				if(CityMode.wires())
					cityModeTransfer();
				else
				{
					int temp = this.transferEnergy(energyStorage.getEnergyStored(), true, 0);
					if(temp > 0)
					{
						energyStorage.modifyEnergyStored(-this.transferEnergy(temp, false, 0));
						markDirty();
					}
				}
				//No wire-damage bookkeeping happens here any more. The source list that feeds it is
				//built on demand by gatherAvailableEnergy when something actually touches a wire,
				//rather than being broadcast across the network by every connector every tick.
			}
			currentTickToMachine = 0;
			currentTickToNet = 0;
		}
		else if(firstTick)
		{
			Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(world, pos);
			if(conns!=null)
				for(Connection conn : conns)
					if(pos.compareTo(conn.end) < 0&&world.isBlockLoaded(conn.end))
						this.markContainingBlockForUpdate(null);
			firstTick = false;
		}
	}
	//	@Override
	//	public void invalidate()
	//	{
	//		super.invalidate();
	//		unload();
	//	}
	//	void unload()
	//	{
	//		if(Lib.IC2 && this.inICNet)
	//		{
	//			IC2Helper.unloadIC2Tile(this);
	//			this.inICNet = false;
	//		}
	//	}
	//	@Override
	//	public void onChunkUnload()
	//	{
	//		unload();
	//	}


	@Override
	public EnumFacing getFacing()
	{
		return this.facing;
	}

	@Override
	public void setFacing(EnumFacing facing)
	{
		this.facing = facing;
	}

	@Override
	public int getFacingLimitation()
	{
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
	protected boolean canTakeLV()
	{
		return true;
	}

	@Override
	public boolean isEnergyOutput()
	{
		BlockPos outPos = getPos().offset(facing);
		if(isRelay())
			return false;
		TileEntity tile = Utils.getExistingTileEntity(world, outPos);
		return EnergyHelper.isFluxReceiver(tile, facing.getOpposite());
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType)
	{
		if(isRelay())
			return 0;
		int acceptanceLeft = getMaxOutput()-currentTickToMachine;
		if(acceptanceLeft <= 0)
			return 0;
		int toAccept = Math.min(acceptanceLeft, amount);

		TileEntity capacitor = Utils.getExistingTileEntity(world, getPos().offset(facing));
		int ret = EnergyHelper.insertFlux(capacitor, facing.getOpposite(), toAccept, simulate);
		//		if(capacitor instanceof IFluxReceiver && ((IFluxReceiver)capacitor).canConnectEnergy(facing.getOpposite()))
		//		{
		//			ret = ((IFluxReceiver)capacitor).receiveEnergy(facing.getOpposite(), toAccept, simulate);
		//		}
		//		else if(capacitor instanceof IEnergyReceiver && ((IEnergyReceiver)capacitor).canConnectEnergy(facing.getOpposite()))
		//		{
		//			ret = ((IEnergyReceiver)capacitor).receiveEnergy(facing.getOpposite(), toAccept, simulate);
		//		}
		//		else if(Lib.IC2 && IC2Helper.isAcceptingEnergySink(capacitor, this, fd.getOpposite()))
		//		{
		//			double left = IC2Helper.injectEnergy(capacitor, fd.getOpposite(), ModCompatability.convertRFtoEU(toAccept, getIC2Tier()), canTakeHV()?(256*256): canTakeMV()?(128*128) : (32*32), simulate);
		//			ret = amount-ModCompatability.convertEUtoRF(left);
		//		}
		//		else if(Lib.GREG && GregTechHelper.gregtech_isValidEnergyOutput(capacitor))
		//		{
		//			long translAmount = (long)ModCompatability.convertRFtoEU(toAccept, getIC2Tier());
		//			long accepted = GregTechHelper.gregtech_outputGTPower(capacitor, (byte)fd.getOpposite().ordinal(), translAmount, 1L, simulate);
		//			int reConv =  ModCompatability.convertEUtoRF(accepted);
		//			ret = reConv;
		//		}
		if(!simulate)
			currentTickToMachine += ret;
		return ret;
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("facing", facing.ordinal());
		energyStorage.writeToNBT(nbt);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		facing = EnumFacing.byIndex(nbt.getInteger("facing"));
		energyStorage.readFromNBT(nbt);
	}

	@Override
	public Vec3d getConnectionOffset(Connection con)
	{
		EnumFacing side = facing.getOpposite();
		double conRadius = con.cableType.getRenderDiameter()/2;
		return new Vec3d(.5-conRadius*side.getXOffset(), .5-conRadius*side.getYOffset(), .5-conRadius*side.getZOffset());
	}

	@SideOnly(Side.CLIENT)
	private AxisAlignedBB renderAABB;

	@SideOnly(Side.CLIENT)
	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		//		if(renderAABB==null)
		//		{
		//			if(Config.getBoolean("increasedRenderboxes"))
		//			{
		int inc = getRenderRadiusIncrease();
		return new AxisAlignedBB(this.pos.getX()-inc, this.pos.getY()-inc, this.pos.getZ()-inc, this.pos.getX()+inc+1, this.pos.getY()+inc+1, this.pos.getZ()+inc+1);
		//				renderAABB = new AxisAlignedBB(this.pos.getX()-inc,this.pos.getY()-inc,this.pos.getZ()-inc, this.pos.getX()+inc+1,this.pos.getY()+inc+1,this.pos.getZ()+inc+1);
		//			}
		//			else
		//				renderAABB = super.getRenderBoundingBox();
		//		}
		//		return renderAABB;
	}

	int getRenderRadiusIncrease()
	{
		return WireType.COPPER.getMaxLength();
	}

	IEForgeEnergyWrapper energyWrapper;

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		if(facing!=this.facing||isRelay())
			return null;
		if(energyWrapper==null||energyWrapper.side!=this.facing)
			energyWrapper = new IEForgeEnergyWrapper(this, this.facing);
		return energyWrapper;
	}

	@Override
	public FluxStorage getFluxStorage()
	{
		return energyStorage;
	}

	@Override
	public SideConfig getEnergySideConfig(EnumFacing facing)
	{
		return (!isRelay()&&facing==this.facing)?SideConfig.INPUT: SideConfig.NONE;
	}

	@Override
	public boolean canConnectEnergy(EnumFacing from)
	{
		if(isRelay())
			return false;
		return from==facing;
	}

	@Override
	public int receiveEnergy(EnumFacing from, int energy, boolean simulate)
	{
		if(world.isRemote||isRelay())
			return 0;
		energy = Math.min(getMaxInput()-currentTickToNet, energy);
		if(energy <= 0)
			return 0;

		int accepted = Math.min(Math.min(getMaxOutput(), getMaxInput()), energy);
		accepted = Math.min(getMaxOutput()-energyStorage.getEnergyStored(), accepted);
		if(accepted <= 0)
			return 0;

		if(!simulate)
		{
			energyStorage.modifyEnergyStored(accepted);
			//This used to broadcast the accepted energy across the whole network for wire-damage
			//bookkeeping, so every connector fed by an adjacent source -- a generator, a capacitor,
			//another mod's block -- walked its network once per tick. That figure is now pulled on
			//demand instead; see gatherAvailableEnergy.
			currentTickToNet += accepted;
			markDirty();
		}

		return accepted;
	}

	@Override
	public int getEnergyStored(EnumFacing from)
	{
		if(isRelay())
			return 0;
		return energyStorage.getEnergyStored();
	}

	@Override
	public int getMaxEnergyStored(EnumFacing from)
	{
		if(isRelay())
			return 0;
		return getMaxInput();
	}

	@Override
	public int extractEnergy(EnumFacing from, int energy, boolean simulate)
	{
		return 0;
	}

	//Reused within transferEnergy to avoid re-resolving each connection's endpoint (ApiUtils.toIIC ->
	//world.getTileEntity) once per output loop, twice per tick. Server-thread only and never re-entrant
	//(outputEnergy on the far side doesn't call back into this method), so a single reusable map is safe.
	private final Map<AbstractConnection, IImmersiveConnectable> transferEndCache = new HashMap<>();

	/**
	 * "City mode" power push (see {@link CityMode#wires()}). A single, lossless pass that sends
	 * this connector's stored energy straight to the devices reachable on its wire network, skipping the
	 * realistic grid's per-wire loss, distance weighting, proportional split and double simulate/transfer
	 * pass. Conductive wires (transfer rate &gt; 0) carry power with no per-wire cap beyond this
	 * connector's own output rate; non-conductive wires (structural rope, cable, redstone) still do not
	 * transfer. Energy is still conserved -- only what a device actually accepts is drawn from storage.
	 */
	private void cityModeTransfer()
	{
		if(world.isRemote)
			return;
		int available = Math.min(getMaxOutput(), energyStorage.getEnergyStored());
		int consumed = WireNetTransfer.city(world, pos, available);
		if(consumed > 0)
		{
			energyStorage.modifyEnergyStored(-consumed);
			markDirty();
		}
	}

	public int transferEnergy(int energy, boolean simulate, final int energyType)
	{
		//The body of this method now lives in WireNetTransfer, unchanged. It moved because the Grid
		//Service Unit takes a wire directly and has to put energy onto a catenary in precisely the
		//way a connector does -- same loss, same proportional split, same Energy Meter readings --
		//and two copies of arithmetic that subtle would eventually be two different behaviours.
		return WireNetTransfer.transfer(world, pos, getMaxInput(), getMaxOutput(), energy, simulate,
				energyType, transferEndCache);
	}

	/**
	 * Answers the pull that replaced the old {@code notifyAvailableEnergy} broadcast: reports what
	 * this connector could supply across {@code c}, loss included, exactly as it used to push.
	 * Relays never hold energy, so they contribute nothing.
	 */
	@Nullable
	@Override
	public Pair<Float, Consumer<Float>> getAvailableEnergy(@Nullable AbstractConnection c)
	{
		if(isRelay())
			return null;
		return getEnergyForConnection(c);
	}

	private Pair<Float, Consumer<Float>> getEnergyForConnection(@Nullable AbstractConnection c)
	{
		float loss = (c!=null&&!CityMode.wires())?c.getAverageLossRate(): 0;
		float max = (1-loss)*energyStorage.getEnergyStored();
		Consumer<Float> extract = (energy) -> {
			energyStorage.modifyEnergyStored((int)(-energy/(1-loss)));
		};
		return new ImmutablePair<>(max, extract);
	}


	public int getMaxInput()
	{
		return connectorInputValues[0];
	}

	public int getMaxOutput()
	{
		return connectorInputValues[0];
	}

	@Nullable
	@Override
	protected Pair<Float, Consumer<Float>> getOwnEnergy()
	{
		return getEnergyForConnection(null);
	}

	@Override
	public float[] getBlockBounds()
	{
		float length = this instanceof TileEntityRelayHV?.875f: this instanceof TileEntityConnectorHV?.75f: this instanceof TileEntityConnectorMV?.5625f: .5f;
		float wMin = this instanceof TileEntityConnectorStructural?.25f: .3125f;
		float wMax = this instanceof TileEntityConnectorStructural?.75f: .6875f;
		switch(facing.getOpposite())
		{
			case UP:
				return new float[]{wMin, 0, wMin, wMax, length, wMax};
			case DOWN:
				return new float[]{wMin, 1-length, wMin, wMax, 1, wMax};
			case SOUTH:
				return new float[]{wMin, wMin, 0, wMax, wMax, length};
			case NORTH:
				return new float[]{wMin, wMin, 1-length, wMax, wMax, 1};
			case EAST:
				return new float[]{0, wMin, wMin, length, wMax, wMax};
			case WEST:
				return new float[]{1-length, wMin, wMin, 1, wMax, wMax};
		}
		return new float[]{0, 0, 0, 1, 1, 1};
	}

	@Override
	public boolean moveConnectionTo(Connection c, BlockPos newEnd)
	{
		return true;
	}
}