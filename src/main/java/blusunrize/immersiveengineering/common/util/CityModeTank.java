/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import javax.annotation.Nullable;

/**
 * A {@link FluidTank} that stops consuming its own contents in city mode.
 * <p>
 * A tank holding anything at all becomes an unlimited source of it: draw as much as you like, as
 * often as you like, and the level does not move. Empty is still empty -- a tank nobody ever filled
 * gives nothing, so the act of filling one still matters and a dry tank still reads as dry.
 * <p>
 * This is the same trade every other city-mode subsystem makes, and the same one the virtual grid
 * makes most explicitly: presence instead of accounting. In a build where the refinery is set
 * dressing, "is there fuel in the tank" is a yes-or-no question, and the arithmetic that tracked
 * the last eleven thousand millibuckets was spent on something nobody was going to read.
 * <p>
 * <strong>Filling is untouched.</strong> Only the draw side is free, so a tank still fills at its
 * ordinary rate from whatever is feeding it, and a player who wants to watch a tank fill still can.
 * The asymmetry is deliberate: making filling free as well would leave nothing to build.
 *
 * @author LDImmersiveEngineering
 */
public class CityModeTank extends FluidTank
{
	public CityModeTank(int capacity)
	{
		super(capacity);
	}

	public CityModeTank(@Nullable FluidStack stack, int capacity)
	{
		super(stack, capacity);
	}

	/**
	 * @return true if this tank is currently behaving as an unlimited source
	 */
	public boolean isBottomless()
	{
		return CityMode.tanks()&&getFluidAmount() > 0;
	}

	@Override
	@Nullable
	public FluidStack drain(int maxDrain, boolean doDrain)
	{
		if(!isBottomless())
			return super.drain(maxDrain, doDrain);
		if(maxDrain <= 0)
			return null;
		//A copy at the requested size, and the level left exactly where it was. Note this does not
		//call onContentsChanged: nothing changed, and firing it would have every tank in a city
		//marking its chunk dirty on every draw -- which would cost more than the arithmetic city
		//mode is here to avoid.
		FluidStack out = new FluidStack(getFluid(), maxDrain);
		return out.amount > 0?out: null;
	}

	@Override
	@Nullable
	public FluidStack drain(FluidStack resource, boolean doDrain)
	{
		//Forge's implementation delegates to drain(int, boolean) after matching the fluid, so this
		//override exists only to keep that guarantee explicit rather than inherited: a future
		//version that stopped delegating would silently make one of the two paths finite.
		if(resource==null||!resource.isFluidEqual(getFluid()))
			return null;
		return drain(resource.amount, doDrain);
	}
}
