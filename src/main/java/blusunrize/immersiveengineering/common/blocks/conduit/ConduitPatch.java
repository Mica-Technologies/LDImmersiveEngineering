/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import javax.annotation.Nullable;

/**
 * A junction box's patch table: which channel, if any, leaves by each of its six faces.
 * <p>
 * This is where per-channel addressing actually lives. A bundle arrives carrying sixteen
 * conductors; the box decides that blue leaves north and red leaves east, and an ordinary LV or HV
 * connector placed against those faces picks up that channel and nothing else. Which is a patch
 * panel, and is meant to read as one.
 * <p>
 * <strong>A box passes every channel through regardless of what is patched.</strong> A breakout
 * says where a conductor <em>leaves</em>, not whether it exists -- so a box put in mid-run purely
 * to turn a corner carries the whole bundle without anybody configuring it, which is the only
 * behaviour that would not be infuriating. The alternative, where carriage is derived from what the
 * far end offers, is circular the moment a run has three boxes on it.
 * <p>
 * The tier of a channel is not stored here either. It is whatever connector the player hangs on
 * that face -- an LV connector makes it an LV circuit, an HV connector an HV one -- because IE's
 * connectors already cap throughput by tier and inventing a second place to say so would only
 * create somewhere for the two to disagree.
 * <p>
 * World-free, so the rules can be tested without a game.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitPatch
{
	/**
	 * What a patched face does with its conductor.
	 * <p>
	 * A face is one of these, never two. Reading and emitting redstone on the same face is the
	 * classic way to build a loop that latches itself on and never lets go, and the cost of
	 * forbidding it is one extra right-click when somebody wants both directions on one box.
	 */
	public enum Mode
	{
		/** Exchanges flux with whatever is against the face -- ordinarily an IE connector. */
		POWER,
		/** Reads the redstone signal arriving at the face and puts it on the conductor. */
		REDSTONE_IN,
		/** Emits the conductor's signal as redstone. */
		REDSTONE_OUT;

		public Mode next()
		{
			return VALUES[(ordinal()+1)%VALUES.length];
		}

		public boolean isRedstone()
		{
			return this!=POWER;
		}

		public static final Mode[] VALUES = values();

		public static Mode byName(String name)
		{
			for(Mode mode : VALUES)
				if(mode.name().equalsIgnoreCase(name))
					return mode;
			return POWER;
		}
	}

	private final WireChannel[] faces = new WireChannel[6];
	private final Mode[] modes = new Mode[6];

	@Nullable
	public WireChannel get(@Nullable EnumFacing face)
	{
		return face==null?null: faces[face.ordinal()];
	}

	public boolean isPatched(@Nullable EnumFacing face)
	{
		return get(face)!=null;
	}

	/**
	 * @return what a patched face does. {@link Mode#POWER} for an unpatched one, since that is what
	 * a face becomes the moment it is patched and nothing reads the mode of a bare face.
	 */
	public Mode modeOf(@Nullable EnumFacing face)
	{
		if(face==null||modes[face.ordinal()]==null)
			return Mode.POWER;
		return modes[face.ordinal()];
	}

	public boolean setMode(EnumFacing face, Mode mode)
	{
		if(face==null||mode==null||modeOf(face)==mode)
			return false;
		modes[face.ordinal()] = mode;
		return true;
	}

	/**
	 * @return true if any face on this box does redstone, which is what lets a box skip the signal
	 * walk entirely when nobody has asked for one
	 */
	public boolean hasRedstone()
	{
		for(EnumFacing face : EnumFacing.VALUES)
			if(faces[face.ordinal()]!=null&&modeOf(face).isRedstone())
				return true;
		return false;
	}

	/**
	 * Patch a face to a channel, or pass null to clear it.
	 *
	 * @return true if anything changed, so a caller can skip a block update it does not need
	 */
	public boolean set(EnumFacing face, @Nullable WireChannel channel)
	{
		if(face==null)
			return false;
		if(faces[face.ordinal()]==channel)
			return false;
		faces[face.ordinal()] = channel;
		//A face that has just been cleared forgets what it was doing. Otherwise re-patching it
		//later silently inherits a mode from a circuit somebody removed weeks ago.
		if(channel==null)
			modes[face.ordinal()] = null;
		return true;
	}

	/**
	 * @return the face that channel leaves by, or null if it is not broken out here
	 */
	@Nullable
	public EnumFacing faceOf(@Nullable WireChannel channel)
	{
		if(channel==null)
			return null;
		for(EnumFacing face : EnumFacing.VALUES)
			if(faces[face.ordinal()]==channel)
				return face;
		return null;
	}

	/**
	 * The same channel on two faces would mean a conductor arriving at one connector and also at
	 * another, which is a short rather than a feature. Patching a channel somewhere new therefore
	 * takes it off wherever it was.
	 *
	 * @return the face it was taken off, or null if it was not patched anywhere
	 */
	@Nullable
	public EnumFacing moveTo(EnumFacing face, WireChannel channel)
	{
		EnumFacing previous = faceOf(channel);
		if(previous==face)
			return null;
		if(previous!=null)
		{
			//The mode travels with the conductor: somebody who set a face to emit and then moved
			//that colour to a different face meant to move the whole arrangement, not to reset it.
			modes[face.ordinal()] = modes[previous.ordinal()];
			faces[previous.ordinal()] = null;
			modes[previous.ordinal()] = null;
		}
		faces[face.ordinal()] = channel;
		return previous;
	}

	public int count()
	{
		int n = 0;
		for(WireChannel channel : faces)
			if(channel!=null)
				n++;
		return n;
	}

	public boolean isEmpty()
	{
		return count()==0;
	}

	public void clear()
	{
		for(int i = 0; i < faces.length; i++)
		{
			faces[i] = null;
			modes[i] = null;
		}
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		for(EnumFacing face : EnumFacing.VALUES)
			if(faces[face.ordinal()]!=null)
			{
				tag.setString(face.getName(), faces[face.ordinal()].getName());
				//Written only when it is not the default, so a box full of ordinary power
				//breakouts saves exactly what it did before redstone channels existed.
				if(modeOf(face)!=Mode.POWER)
					tag.setString(face.getName()+"_mode", modeOf(face).name());
			}
		return tag;
	}

	public void readFromNBT(@Nullable NBTTagCompound tag)
	{
		clear();
		if(tag==null)
			return;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			if(!tag.hasKey(face.getName()))
				continue;
			//An unrecognised colour clears the face rather than guessing one. A channel renamed by
			//a future version should cost the player a visible blank, not a silent rewire.
			faces[face.ordinal()] = WireChannel.byName(tag.getString(face.getName()));
			if(faces[face.ordinal()]!=null&&tag.hasKey(face.getName()+"_mode"))
				//An unrecognised mode falls back to POWER, which is what an absent key means too.
				//A face whose mode will not parse should be inert, not guessing at redstone.
				modes[face.ordinal()] = Mode.byName(tag.getString(face.getName()+"_mode"));
		}
	}
}
