/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.WireApi;
import blusunrize.immersiveengineering.api.energy.wires.WireType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The wire type a conduit run's connection is made of.
 * <p>
 * A bundle still needs to be a {@code Connection} to live in {@code ImmersiveNetHandler}, and a
 * connection needs a wire type, so here is one. It is not a wire anybody can craft or hold: it is
 * created by laying conduit and removed by breaking it, and there is deliberately no coil.
 * <p>
 * <strong>It renders nothing.</strong> The conduit blocks are the visible wire; a catenary strung
 * through them would be both wrong and drawn twice. The render paths skip bundles outright rather
 * than drawing this at zero width, because a zero-width catenary is still geometry submitted every
 * frame for every run in view.
 * <p>
 * The rate here is the rate of <em>one channel</em>, not of the bundle. Sixteen conductors in a
 * sleeve each carry what a conductor carries -- see the plan's decision 7 -- and the per-channel
 * tier a player actually gets is capped further by whichever connector they hang on the breakout,
 * which is IE's existing mechanism and needed no new one.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitWireType extends WireType
{
	public static final String NAME = "CONDUIT_BUNDLE";

	/**
	 * Matched to IE's steel wire, so a bundle never becomes the reason a circuit is slow. What the
	 * player gets on a given channel is whatever their connector allows.
	 */
	public static final int TRANSFER_RATE = 32768;

	/**
	 * Lower than any catenary of the same rate. Conduit is a short indoor run clipped to a wall
	 * rather than a span across a valley, and charging span losses for a corridor would make the
	 * tidy option the expensive one for no reason a player could see.
	 */
	public static final double LOSS_RATIO = 0.01;

	public static ConduitWireType INSTANCE;

	/**
	 * Registered once, from IE's own init, alongside the wires IE ships.
	 */
	public static void init()
	{
		if(INSTANCE!=null)
			return;
		INSTANCE = new ConduitWireType();
		WireApi.registerWireType(INSTANCE);
	}

	@Override
	public String getUniqueName()
	{
		return NAME;
	}

	@Override
	public double getLossRatio()
	{
		return LOSS_RATIO;
	}

	@Override
	public int getTransferRate()
	{
		return TRANSFER_RATE;
	}

	@Override
	public int getColour(Connection connection)
	{
		//Never drawn, but a sane value beats a zero that some other renderer might one day divide by.
		return 0xB0B0B6;
	}

	@Override
	public double getSlack()
	{
		//Taut. A bundle's endpoints are joined by blocks that lie flat against a wall, so if this
		//ever were drawn, a sagging line between them would be visibly wrong.
		return 1;
	}

	@SideOnly(Side.CLIENT)
	@Override
	public TextureAtlasSprite getIcon(Connection connection)
	{
		return iconDefaultWire;
	}

	@Override
	public int getMaxLength()
	{
		//Bounded by how far a run can be walked rather than by a coil's length, so this only has to
		//be large enough not to be the binding constraint.
		return 512;
	}

	@Override
	public ItemStack getWireCoil()
	{
		//There is none. A bundle is made by laying conduit, so breaking one must not drop a coil
		//that cannot be crafted or used.
		return ItemStack.EMPTY;
	}

	@Override
	public ItemStack getWireCoil(Connection c)
	{
		return ItemStack.EMPTY;
	}

	@Override
	public double getRenderDiameter()
	{
		return 0;
	}

	@Override
	public boolean isEnergyWire()
	{
		return true;
	}

	@Override
	public String getCategory()
	{
		//Its own category, so a bundle cannot share a connector with an ordinary wire. The two meet
		//at a junction box's breakout, which is a block, not at a shared endpoint.
		return "CONDUIT";
	}
}
