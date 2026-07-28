/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.Cancelable;

import javax.annotation.Nullable;

/**
 * Fired on the server whenever a Gas Station Pump hands fuel to somebody.
 * <p>
 * <strong>This fork does not implement currency, and this event is the reason it does not have
 * to.</strong> A city server running an economy plugin subscribes to this, reads the price the
 * forecourt has set, and charges whatever its own money system charges. That is a real, useful
 * hook; a half-built economy inside a tech mod would be neither.
 * <p>
 * Cancellable, and cancelled <em>before</em> anything moves: a plugin that finds the player
 * cannot pay stops the transaction rather than having to claw the fuel back afterwards. The
 * amount is also mutable, so a plugin may hand out less than was asked for -- a pump that
 * dispenses what the player can afford is a better object than one that refuses outright.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
@Cancelable
public class FuelDispensedEvent extends PlayerEvent
{
	/**
	 * Where the pump stands, so a plugin can tell one forecourt from another.
	 */
	public final BlockPos pump;
	/**
	 * The fluid's registry name. A name rather than a {@code Fluid} so a listener can be written
	 * against fuels this build has never heard of.
	 */
	public final String fluid;
	/**
	 * The price this forecourt has set, per bucket, in whatever unit the consuming plugin uses.
	 * Zero means the owner has not set one, which is the shipped default and reads as "free".
	 */
	public final int pricePerBucket;
	/**
	 * Millibuckets about to be handed over. Mutable: a listener may lower it. Raising it is
	 * honoured only as far as the pump actually has fuel, so a listener cannot mint diesel.
	 */
	private int amount;

	/**
	 * What is being filled, when it is an item. Null for a fill into a block or an entity.
	 */
	@Nullable
	public final String targetDescription;

	public FuelDispensedEvent(EntityPlayer player, BlockPos pump, String fluid, int amount,
							  int pricePerBucket, @Nullable String targetDescription)
	{
		super(player);
		this.pump = pump;
		this.fluid = fluid;
		this.amount = Math.max(0, amount);
		this.pricePerBucket = Math.max(0, pricePerBucket);
		this.targetDescription = targetDescription;
	}

	public int getAmount()
	{
		return amount;
	}

	public void setAmount(int amount)
	{
		this.amount = Math.max(0, amount);
	}

	/**
	 * @return what this transaction would cost at the forecourt's own price, rounded down. A
	 * convenience so every listener does not re-derive the same arithmetic -- and gets the
	 * rounding the same way round.
	 */
	public long getTotalPrice()
	{
		return (long)pricePerBucket*amount/1000L;
	}
}
