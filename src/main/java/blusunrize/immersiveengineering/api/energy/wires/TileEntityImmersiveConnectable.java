/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;


import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.TargetingInfo;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.IELogger;
import blusunrize.immersiveengineering.common.util.Utils;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraftforge.common.property.IExtendedBlockState;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static blusunrize.immersiveengineering.api.energy.wires.WireApi.canMix;
import static blusunrize.immersiveengineering.api.energy.wires.WireType.*;

public abstract class TileEntityImmersiveConnectable extends TileEntityIEBase implements IImmersiveConnectable
{
	protected WireType limitType = null;

	protected boolean canTakeLV()
	{
		return false;
	}

	protected boolean canTakeMV()
	{
		return false;
	}

	protected boolean canTakeHV()
	{
		return false;
	}

	protected boolean isRelay()
	{
		return false;
	}

	@Override
	public void onEnergyPassthrough(int amount)
	{
	}

	@Override
	public boolean allowEnergyToPass(Connection con)
	{
		return true;
	}

	@Override
	public boolean canConnect()
	{
		return true;
	}

	@Override
	public boolean isEnergyOutput()
	{
		return false;
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType)
	{
		return 0;
	}

	@Override
	public BlockPos getConnectionMaster(WireType cableType, TargetingInfo target)
	{
		return getPos();
	}

	@Override
	public boolean canConnectCable(WireType cableType, TargetingInfo target, Vec3i offset)
	{
		String category = cableType.getCategory();
		boolean foundAccepting = (HV_CATEGORY.equals(category)&&canTakeHV())
				||(MV_CATEGORY.equals(category)&&canTakeMV())
				||(LV_CATEGORY.equals(category)&&canTakeLV());
		if(!foundAccepting)
			return false;
		return limitType==null||(this.isRelay()&&canMix(limitType, cableType));
	}

	@Override
	public void connectCable(WireType cableType, TargetingInfo target, IImmersiveConnectable other)
	{
		this.limitType = cableType;
	}

	@Override
	public WireType getCableLimiter(TargetingInfo target)
	{
		return this.limitType;
	}

	@Override
	public void removeCable(Connection connection)
	{
		WireType type = connection!=null?connection.cableType: null;
		Set<Connection> outputs = ImmersiveNetHandler.INSTANCE.getConnections(world, Utils.toCC(this));
		if(outputs==null||outputs.size()==0)
		{
			if(type==limitType||type==null)
				this.limitType = null;
		}
		this.markDirty();
		if(world!=null)
		{
			IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}
	}

	private List<Pair<Float, Consumer<Float>>> sources = new ArrayList<>();
	private long lastSourceUpdate = Long.MIN_VALUE;

	@Override
	public void addAvailableEnergy(float amount, Consumer<Float> consume)
	{
		refreshSources();
		if(amount > 0&&consume!=null)
			sources.add(new ImmutablePair<>(amount, consume));
	}

	/**
	 * Rebuilds the list of energy this node could draw on for wire-shock damage, at most once per
	 * tick.
	 * <p>
	 * The list used to be filled by every powered connector on the network broadcasting to every
	 * node it could reach, every tick, whether or not anything was touching a wire. Profiling
	 * measured that broadcast as the single most expensive method in the mod. It is now built on
	 * demand: nothing happens until {@link #getDamageAmount} or {@link #processDamage} actually
	 * asks, which only occurs when an entity is genuinely in contact with a live wire.
	 * <p>
	 * The per-tick stamp keeps repeat calls within one tick free -- several entities touching the
	 * same wire, or the paired getDamageAmount/processDamage calls, walk the network once between
	 * them.
	 */
	private void refreshSources()
	{
		//addAvailableEnergy is public API, so this can be reached from an addon outside a tick body
		//where the tile has no world yet. Guarded here rather than one frame deeper, where it used
		//to sit uselessly behind this dereference.
		if(world==null)
			return;
		long currentTime = world.getTotalWorldTime();
		if(lastSourceUpdate==currentTime)
			return;
		sources.clear();
		Pair<Float, Consumer<Float>> own = getOwnEnergy();
		if(own!=null)
			sources.add(own);
		//Stamped before gathering: gatherAvailableEnergy walks the network and may re-enter this
		//node, and the stamp is what stops that recursing.
		lastSourceUpdate = currentTime;
		gatherAvailableEnergy();
	}

	/**
	 * Collects the energy available from the rest of the network into {@link #sources}.
	 * <p>
	 * This lives on the base node rather than on connectors because damage is asked of whatever a
	 * wire is attached to, which includes transformers, breaker switches, feedthroughs, energy
	 * meters and razor wire -- none of which store energy themselves but all of which previously
	 * had this list filled for them by the network-wide broadcast. The walk needs nothing
	 * connector-specific: it asks each reachable node what it could supply, and only nodes that
	 * actually hold energy answer.
	 */
	protected void gatherAvailableEnergy()
	{
		if(world.isRemote)
			return;
		//This node must be willing to pass energy before it may draw on the network, because the
		//push it replaces applied exactly that test to the receiver: a source only ever advertised
		//to a node whose allowEnergyToPass was true, so an open breaker switch never accumulated
		//any energy and the spans attached to it never shocked.
		//
		//It has to be checked here rather than relying on the search. getIndirectEnergyConnections
		//refuses to expand *through* a node that blocks energy, but it seeds from the starting
		//node's own connections without ever asking whether that node blocks -- so a breaker switch
		//asked for a damage figure can see straight across itself to the live side.
		if(!allowEnergyToPass(null))
			return;
		for(ImmersiveNetHandler.AbstractConnection con :
				ImmersiveNetHandler.INSTANCE.getIndirectEnergyConnections(pos, world, true))
		{
			if(con.cableType==null)
				continue;
			IImmersiveConnectable end = ApiUtils.toIIC(con.end, world);
			if(end==null||!end.allowEnergyToPass(null))
				continue;
			Pair<Float, Consumer<Float>> e = end.getAvailableEnergy(con);
			if(e!=null&&e.getKey() > 0)
				addAvailableEnergy(e.getKey(), e.getValue());
		}
	}

	@Nullable
	protected Pair<Float, Consumer<Float>> getOwnEnergy()
	{
		return null;
	}

	@Override
	public float getDamageAmount(Entity e, Connection c)
	{
		float baseDmg = getBaseDamage(c);
		float max = getMaxDamage(c);
		if(baseDmg==0)
			return 0;
		//The old staleness check (more than a tick since the last broadcast) is gone because the
		//list is now built on demand and so is never stale. It was standing in for "is anything on
		//this network actually powered": an unpowered network simply reports zero available energy
		//and the loop below still yields no damage.
		refreshSources();
		float damage = 0;
		for(int i = 0; i < sources.size()&&damage < max; i++)
		{
			int consume = (int)Math.min(sources.get(i).getLeft(), (max-damage)/baseDmg);
			damage += baseDmg*consume;
		}
		return damage;
	}

	@Override
	public void processDamage(Entity e, float amount, Connection c)
	{
		float baseDmg = getBaseDamage(c);
		//Always follows a getDamageAmount in the same tick, so this is a cheap stamp check that
		//reuses the list that call built rather than walking the network a second time.
		refreshSources();
		float damage = 0;
		for(int i = 0; i < sources.size()&&damage < amount; i++)
		{
			float consume = Math.min(sources.get(i).getLeft(), (amount-damage)/baseDmg);
			sources.get(i).getRight().accept(consume);
			damage += baseDmg*consume;
			if(consume==sources.get(i).getLeft())
			{
				sources.remove(i);
				i--;
			}
		}
	}

	protected float getBaseDamage(Connection c)
	{
		if(c.cableType==COPPER)
			return 8*2F/c.cableType.getTransferRate();
		else if(c.cableType==ELECTRUM)
			return 8*5F/c.cableType.getTransferRate();
		else if(c.cableType==STEEL)
			return 8*15F/c.cableType.getTransferRate();
		return 0;
	}

	protected float getMaxDamage(Connection c)
	{
		return c.cableType.getTransferRate()/8*getBaseDamage(c);
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket()
	{
		NBTTagCompound nbttagcompound = new NBTTagCompound();
		this.writeToNBT(nbttagcompound);
		writeConnsToNBT(nbttagcompound);
		return new SPacketUpdateTileEntity(this.pos, 3, nbttagcompound);
	}

	@Override
	public void onDataPacket(@Nonnull NetworkManager net, @Nonnull SPacketUpdateTileEntity pkt)
	{
		NBTTagCompound nbt = pkt.getNbtCompound();
		this.readFromNBT(nbt);
		loadConnsFromNBT(nbt);
	}

	@Override
	public boolean receiveClientEvent(int id, int arg)
	{
		if(id==-1||id==255)
		{
			IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
			return true;
		}
		else if(id==254)
		{
			IBlockState state = world.getBlockState(pos);
			if(state instanceof IExtendedBlockState)
			{
				state = state.getActualState(world, getPos());
				state = state.getBlock().getExtendedState(state, world, getPos());
				ImmersiveEngineering.proxy.removeStateFromSmartModelCache((IExtendedBlockState)state);
				ImmersiveEngineering.proxy.removeStateFromConnectionModelCache((IExtendedBlockState)state);
			}
			world.notifyBlockUpdate(pos, state, state, 3);
			return true;
		}
		return super.receiveClientEvent(id, arg);
	}

	@Override
	public void readCustomNBT(@Nonnull NBTTagCompound nbt, boolean descPacket)
	{
		try
		{
			if(nbt.hasKey("limitType"))
				limitType = ApiUtils.getWireTypeFromNBT(nbt, "limitType");
			else
				limitType = null;
			if(nbt.hasKey("connectionList"))
				loadConnsFromNBT(nbt);
		} catch(Exception e)
		{
			IELogger.error("TileEntityImmersiveConenctable encountered MASSIVE error reading NBT. You should probably report this.");
			IELogger.logger.catching(Level.ERROR, e);
		}
	}

	@Override
	public void writeCustomNBT(@Nonnull NBTTagCompound nbt, boolean descPacket)
	{
		try
		{
			if(limitType!=null)
				nbt.setString("limitType", limitType.getUniqueName());
			if(descPacket)
				writeConnsToNBT(nbt);

			//			if(this.world!=null)
			//			{
			//				nbt.setIntArray("prevPos", new int[]{this.world.provider.dimensionId,xCoord,yCoord,zCoord});
			//			}
		} catch(Exception e)
		{
			IELogger.error("TileEntityImmersiveConenctable encountered MASSIVE error writing NBT. You should probably report this.");
			IELogger.logger.catching(Level.ERROR, e);
		}
	}

	private void loadConnsFromNBT(NBTTagCompound nbt)
	{
		if(world!=null&&world.isRemote&&!Minecraft.getMinecraft().isSingleplayer()&&nbt!=null)
		{
			NBTTagList connectionList = nbt.getTagList("connectionList", 10);
			ImmersiveNetHandler.INSTANCE.clearConnectionsOriginatingFrom(Utils.toCC(this), world);
			for(int i = 0; i < connectionList.tagCount(); i++)
			{
				NBTTagCompound conTag = connectionList.getCompoundTagAt(i);
				Connection con = Connection.readFromNBT(conTag);
				if(con!=null)
				{
					ImmersiveNetHandler.INSTANCE.addConnection(world, Utils.toCC(this), con);
				}
				else
					IELogger.error("CLIENT read connection as null from {}", nbt);
			}
		}
	}

	private void writeConnsToNBT(NBTTagCompound nbt)
	{
		if(world!=null&&!world.isRemote&&nbt!=null)
		{
			NBTTagList connectionList = new NBTTagList();
			Set<Connection> conL = ImmersiveNetHandler.INSTANCE.getConnections(world, Utils.toCC(this));
			if(conL!=null)
				for(Connection con : conL)
					connectionList.appendTag(con.writeToNBT());
			nbt.setTag("connectionList", connectionList);
		}
	}

	public Set<Connection> genConnBlockstate()
	{
		Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(world, pos);
		if(conns==null)
			return ImmutableSet.of();
		Set<Connection> ret = new HashSet<Connection>()
		{
			@Override
			public boolean equals(Object o)
			{
				if(o==this)
					return true;
				if(!(o instanceof HashSet))
					return false;
				HashSet<Connection> other = (HashSet<Connection>)o;
				if(other.size()!=this.size())
					return false;
				for(Connection c : this)
					if(!other.contains(c))
						return false;
				return true;
			}
		};
		//TODO thread safety!
		for(Connection c : conns)
		{
			IImmersiveConnectable end = ApiUtils.toIIC(c.end, world, false);
			if(end==null)
				continue;
			// generate subvertices
			c.getSubVertices(world);
			ret.add(c);
		}

		return ret;
	}

	@Override
	public void onChunkUnload()
	{
		super.onChunkUnload();
		if(!world.isRemote)
			ImmersiveNetHandler.INSTANCE.addProxy(new IICProxy(this));
	}

	@Override
	public void validate()
	{
		super.validate();
		if(!world.isRemote)
			ApiUtils.addFutureServerTask(world, () -> ImmersiveNetHandler.INSTANCE.onTEValidated(this));
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		if(world.isRemote&&!Minecraft.getMinecraft().isSingleplayer())
			ImmersiveNetHandler.INSTANCE.clearAllConnectionsFor(pos, world, this, false);
	}
}