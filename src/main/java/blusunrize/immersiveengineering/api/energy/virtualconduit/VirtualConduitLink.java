/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * One outlet: a place where one conductor of one junction box leaves the run, kept so that it can go
 * on delivering while the box's own chunk is unloaded.
 * <p>
 * <strong>Why a conduit needs this at all.</strong> A run moves energy one hop per tick inside
 * {@code TileEntityJunctionBox.update()}, and an unloaded block does not tick. A wire does not have
 * the problem: {@code IICProxy} stands in for an unloaded connector and the route search steps over
 * it. There is no equivalent for a box, because a proxy is pass-through only -- it answers false to
 * {@code isEnergyOutput} and zero to {@code outputEnergy}, so it can carry energy past itself but
 * never receive any -- and the route search refuses to cross a bundle at all. So an inter-town
 * conduit, whose middle is unloaded essentially always, carries nothing. Fly the line and the far
 * town lights up; leave and it goes dark.
 * <p>
 * <strong>One record per conductor per box per kind, not per run.</strong> The thing that has to keep
 * happening is the last step: the far box handing its conductor's energy to whatever is bolted or
 * strung to that conductor's face. What the middle of the run is doing does not need modelling,
 * because nothing loaded can observe it. Recording it per conductor is also what keeps the sixteen
 * separate -- a bundle carrying a lighting circuit and a workshop circuit must not merge them the
 * moment nobody is standing there.
 * <p>
 * <strong>An outlet is static.</strong> It exists while the breakout exists, lit or dark. The first version
 * recorded one only while the conductor was carrying something and deleted it the moment it went
 * out, and a far box loaded without its feeder goes dark in a second through no fault of the
 * circuit -- so visiting the far end of a line deleted the very record that was holding the far town
 * up. Whether a conductor is <em>live</em> is a separate question, asked of
 * {@link VirtualConduits#isLive} against the feeds, and it is asked at push time rather than baked
 * into whether the record exists.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class VirtualConduitLink
{
	/**
	 * The two ways a conductor gets out of a box, which are two different pushes.
	 * <p>
	 * They are kept as one record type with a discriminator rather than as two classes because
	 * everything around them -- the table, the save file, the sweep, the command -- wants to treat
	 * them alike, and only the push itself differs.
	 */
	public enum Kind
	{
		/**
		 * A catenary strung straight at the breakout face. The push leaves from the box's own
		 * position and is filtered to that one wire, because a box has up to six of them and each is
		 * a different circuit.
		 */
		WIRE,
		/**
		 * A connector bolted against the breakout face. The push leaves from the <em>connector's</em>
		 * position and is not filtered at all: a connector has one terminal and therefore one
		 * circuit, so there is nothing to keep apart.
		 * <p>
		 * This is the kind that was missing, and it is the kind everything on the playtester's line
		 * actually is. All four boxes on those poles have an empty wire table; every breakout is an
		 * HV connector on a box face. The first version hooked only the catenary path, the demo rig used a
		 * wire, and so the feature passed its test and recorded nothing whatsoever in the field.
		 */
		NEIGHBOUR;

		public static final Kind[] VALUES = values();

		@Nullable
		public static Kind byName(@Nullable String name)
		{
			if(name==null)
				return null;
			for(Kind kind : VALUES)
				if(kind.name().equals(name))
					return kind;
			return null;
		}
	}

	private final int dimension;
	private final BlockPos boxPos;
	private final WireChannel channel;
	private final Kind kind;

	/**
	 * What this outlet can hand over, IF/t.
	 * <p>
	 * The hardware's own figure -- the wire's transfer rate, or the connector's input rate -- and
	 * never one tick's acceptance. A conductor is not less able to deliver on a tick when the far end
	 * happened to be full, and recording what came back would have a box remember a quiet moment and
	 * supply that forever.
	 */
	private int rate;

	/**
	 * Which face of the box the breakout is on. For {@link Kind#NEIGHBOUR} it is also where the push
	 * starts from, one block that way; for {@link Kind#WIRE} it is only worth saying in the readout.
	 */
	@Nullable
	private EnumFacing face;

	/**
	 * The wire on the breakout face, as {@code WireType.getUniqueName()}, and the node at its far
	 * end. Together they are the route filter: a route counts if its first hop is that wire type and
	 * arrives at that position. {@link Kind#WIRE} only.
	 * <p>
	 * They are stored rather than re-derived because deriving them needs the box's patch and wire
	 * tables, which are the tile entity's state and therefore exactly what is missing when this
	 * record is used.
	 */
	@Nullable
	private String wireTypeName;
	@Nullable
	private BlockPos wireEnd;

	/**
	 * Not saved: what this link actually delivered last tick, for the readouts and the command.
	 */
	private int lastDelivered;

	public VirtualConduitLink(int dimension, BlockPos boxPos, WireChannel channel, Kind kind)
	{
		this.dimension = dimension;
		this.boxPos = boxPos.toImmutable();
		this.channel = channel;
		this.kind = kind;
	}

	public int getDimension()
	{
		return dimension;
	}

	public BlockPos getBoxPos()
	{
		return boxPos;
	}

	public WireChannel getChannel()
	{
		return channel;
	}

	public Kind getKind()
	{
		return kind;
	}

	public int getRate()
	{
		return rate;
	}

	@Nullable
	public EnumFacing getFace()
	{
		return face;
	}

	@Nullable
	public String getWireTypeName()
	{
		return wireTypeName;
	}

	@Nullable
	public BlockPos getWireEnd()
	{
		return wireEnd;
	}

	/**
	 * Where the push starts from: the box for a catenary, and the bolted connector itself for the
	 * other kind, because that is the node the wire graph knows.
	 */
	public BlockPos getOutletPos()
	{
		return kind==Kind.NEIGHBOUR&&face!=null?boxPos.offset(face): boxPos;
	}

	/**
	 * Record what this outlet is able to hand over, from a box that is loaded and can see it.
	 *
	 * @return true if anything changed, so the caller knows whether the save is now dirty
	 */
	public boolean observe(int rate, @Nullable EnumFacing face, @Nullable String wireTypeName,
						   @Nullable BlockPos wireEnd)
	{
		BlockPos end = wireEnd==null?null: wireEnd.toImmutable();
		boolean changed = this.rate!=rate||this.face!=face
				||!java.util.Objects.equals(this.wireTypeName, wireTypeName)
				||!java.util.Objects.equals(this.wireEnd, end);
		this.rate = rate;
		this.face = face;
		this.wireTypeName = wireTypeName;
		this.wireEnd = end;
		return changed;
	}

	/**
	 * @return true when this record has everything the push needs. A catenary outlet without a wire
	 * has no route to filter on; a bolted outlet without a face has nowhere to push from.
	 */
	public boolean isUsable()
	{
		if(rate <= 0)
			return false;
		if(kind==Kind.NEIGHBOUR)
			return face!=null;
		return wireTypeName!=null&&!wireTypeName.isEmpty()&&wireEnd!=null;
	}

	public int getLastDelivered()
	{
		return lastDelivered;
	}

	public void setLastDelivered(int lastDelivered)
	{
		this.lastDelivered = Math.max(0, lastDelivered);
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("dim", dimension);
		tag.setLong("box", boxPos.toLong());
		tag.setString("channel", channel.getName());
		//By name rather than by ordinal, so the file stays readable and reordering the enum cannot
		//silently turn every bolted outlet in a save into a catenary one.
		tag.setString("kind", kind.name());
		tag.setInteger("rate", rate);
		if(face!=null)
			tag.setByte("face", (byte)face.ordinal());
		if(wireTypeName!=null)
			tag.setString("wire", wireTypeName);
		if(wireEnd!=null)
			tag.setLong("end", wireEnd.toLong());
		return tag;
	}

	/**
	 * @return the link, or null if the tag names a conductor this build does not have -- which is
	 * how a save written by a version with more of them comes back without throwing.
	 */
	@Nullable
	public static VirtualConduitLink readFromNBT(NBTTagCompound tag)
	{
		WireChannel channel = WireChannel.byName(tag.getString("channel"));
		if(channel==null)
			return null;
		//A record written before bolted connectors were outlets at all has no kind, and every one of
		//them was a catenary. Anything unrecognised reads the same way, for the same reason the
		//channel does: a save from a later build must load, minus what means nothing here.
		Kind kind = Kind.byName(tag.hasKey("kind")?tag.getString("kind"): null);
		VirtualConduitLink link = new VirtualConduitLink(tag.getInteger("dim"),
				BlockPos.fromLong(tag.getLong("box")), channel, kind==null?Kind.WIRE: kind);
		link.rate = Math.max(0, tag.getInteger("rate"));
		link.face = tag.hasKey("face")?EnumFacing.byIndex(tag.getByte("face")): null;
		link.wireTypeName = tag.hasKey("wire")?tag.getString("wire"): null;
		link.wireEnd = tag.hasKey("end")?BlockPos.fromLong(tag.getLong("end")): null;
		return link;
	}

	@Override
	public String toString()
	{
		return "VirtualConduitLink{dim="+dimension+" "+boxPos.getX()+" "+boxPos.getY()+" "+boxPos.getZ()
				+" "+channel.getName()+" "+kind+" "+rate+" IF/t}";
	}
}
