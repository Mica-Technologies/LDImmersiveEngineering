/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

/**
 * What an unloaded Feed Unit can still supply, from a metered plant in front of it.
 * <p>
 * A Feed Unit whose chunk is unloaded has no endpoint and would otherwise drop out of its segment. When a
 * Generation Meter sits between a generator and that Feed Unit, virtual generation knows the plant's
 * measured output, and the engine keeps counting it. A seam rather than a direct call, so the grid engine
 * stays testable against a fake and does not depend on how virtual generation stores its records.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public interface IVirtualFeedSupply
{
	IVirtualFeedSupply NONE = new IVirtualFeedSupply()
	{
		@Override
		public int rate(GridDevice feed, boolean cityMode)
		{
			return 0;
		}

		@Override
		public void delivered(GridDevice feed, int amount)
		{
		}
	};

	/**
	 * @return IF/t the metered plant in front of this unloaded feed supplies in the given mode, or 0
	 */
	int rate(GridDevice feed, boolean cityMode);

	/**
	 * Reports what the engine actually drew, for the plant's readouts.
	 */
	void delivered(GridDevice feed, int amount);
}
