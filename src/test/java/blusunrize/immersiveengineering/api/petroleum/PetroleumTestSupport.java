/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

/**
 * Shared setup for the petroleum tests.
 * <p>
 * Both {@link PetroleumConfig} and {@link ReservoirHandler}'s registry and cache are
 * process-global, so every test class here resets them in a {@code @BeforeEach}; leaving one
 * test's deposits or bounds behind would make failures depend on execution order.
 */
public final class PetroleumTestSupport
{
	private PetroleumTestSupport()
	{
	}

	/**
	 * The name of the type {@link #registerTestType()} installs.
	 */
	public static final String TEST_TYPE = "test_crude";

	/**
	 * Restores config, registry and cache to a known-empty state.
	 */
	public static void reset()
	{
		PetroleumConfig.resetToDefaults();
		ReservoirHandler.clearTypes();
		ReservoirHandler.clear();
	}

	/**
	 * A single deposit type with a wide, easily-reasoned-about capacity range.
	 */
	public static ReservoirType registerTestType()
	{
		return ReservoirHandler.registerType(
				new ReservoirType(TEST_TYPE, "ie_crude_oil", 1, 1000, 1_000_000));
	}

	/**
	 * A full deposit of the given size, of the registered test type.
	 */
	public static Reservoir full(int capacity)
	{
		return new Reservoir(TEST_TYPE, capacity);
	}

	/**
	 * A deposit of {@code capacity} drawn down to the given fraction of its original pool.
	 */
	public static Reservoir at(int capacity, double fraction)
	{
		Reservoir reservoir = new Reservoir(TEST_TYPE, capacity);
		reservoir.deplete((int)Math.round(capacity*(1-fraction)));
		return reservoir;
	}
}
