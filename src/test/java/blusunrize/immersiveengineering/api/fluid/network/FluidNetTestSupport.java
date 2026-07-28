/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.fluid.network;

import blusunrize.immersiveengineering.api.DimensionBlockPos;

/**
 * Shared builders for the fluid network tests, mirroring {@code GridTestSupport}.
 * <p>
 * {@link FluidNetConfig} is process-global, so every test class here resets it in a
 * {@code @BeforeEach}; these helpers exist so a test can say what it is actually about rather
 * than restating six lines of wiring.
 */
public final class FluidNetTestSupport
{
	private FluidNetTestSupport()
	{
	}

	/**
	 * Registry names, as strings. Real ones, so a test reads like the game it describes -- and so
	 * a reader can tell "the main carries diesel" from "the main carries fluid A".
	 */
	public static final String DIESEL = "ie_diesel";
	public static final String GAS = "natural_gas";
	public static final String WATER = "water";

	private static int nextCoord = 0;

	/**
	 * A fresh position, distinct from every other one handed out this run, so devices in a test
	 * never collide by accident.
	 */
	public static DimensionBlockPos pos()
	{
		return new DimensionBlockPos(nextCoord++, 64, 0, 0);
	}

	public static DimensionBlockPos pos(int x, int y, int z, int dim)
	{
		return new DimensionBlockPos(x, y, z, dim);
	}

	/**
	 * Registers an online Inlet on {@code main} holding the given supply.
	 */
	public static FluidDevice inlet(VirtualFluidNet net, FluidMain main, String fluid,
									int available, int cap)
	{
		return inlet(net, main, fluid, available, cap, 0);
	}

	public static FluidDevice inlet(VirtualFluidNet net, FluidMain main, String fluid,
									int available, int cap, int priority)
	{
		FluidDevice device = net.registerDevice(pos(), FluidDeviceType.INLET);
		device.setTransferCap(cap);
		device.setPriority(priority);
		net.assignDevice(device, main==null?null: main.getId());
		device.setEndpoint(FakeFluidEndpoint.supplying(fluid, available));
		if(main!=null)
			main.invalidateViews();
		return device;
	}

	/**
	 * Registers an online Outlet on {@code main} with the given demand.
	 */
	public static FluidDevice outlet(VirtualFluidNet net, FluidMain main, String fluid,
									 int demand, int cap)
	{
		return outlet(net, main, fluid, demand, cap, 0, false);
	}

	public static FluidDevice outlet(VirtualFluidNet net, FluidMain main, String fluid,
									 int demand, int cap, int priority, boolean critical)
	{
		FluidDevice device = net.registerDevice(pos(), FluidDeviceType.OUTLET);
		device.setTransferCap(cap);
		device.setPriority(priority);
		device.setCritical(critical);
		net.assignDevice(device, main==null?null: main.getId());
		device.setEndpoint(FakeFluidEndpoint.accepting(fluid, demand));
		if(main!=null)
			main.invalidateViews();
		return device;
	}

	/**
	 * Registers an online Valve on {@code main}.
	 *
	 * @param output true for a valve that reports the main, false for a shut-off
	 * @param high   the redstone level the world is presenting to it
	 */
	public static FluidDevice valve(VirtualFluidNet net, FluidMain main, boolean output,
									boolean inverted, boolean high)
	{
		FluidDevice device = net.registerDevice(pos(), FluidDeviceType.VALVE);
		device.setValveOutput(output);
		device.setValveInverted(inverted);
		net.assignDevice(device, main==null?null: main.getId());
		device.setEndpoint(FakeFluidEndpoint.valve(high));
		if(main!=null)
			main.invalidateViews();
		return device;
	}

	public static FakeFluidEndpoint endpointOf(FluidDevice device)
	{
		return (FakeFluidEndpoint)device.getEndpoint();
	}

	/**
	 * A main with generous caps and no leakage, so a test only has to change the one knob it is
	 * actually interested in.
	 */
	public static FluidMain main(VirtualFluidNet net, String name)
	{
		FluidMain main = net.createMain(name);
		main.getPolicy().setMaxInput(100000);
		main.getPolicy().setMaxOutput(100000);
		main.getPolicy().setPackCap(100000);
		main.getPolicy().setLeakPct(0);
		return main;
	}

	/**
	 * A main that already knows what it carries, for the many tests that are not about typing.
	 */
	public static FluidMain main(VirtualFluidNet net, String name, String fluid)
	{
		FluidMain main = main(net, name);
		main.setFluid(fluid);
		return main;
	}

	/**
	 * Restores {@link FluidNetConfig} and widens the ceilings so the helpers above are not
	 * silently clamped by the shipped defaults.
	 */
	public static void resetConfig()
	{
		FluidNetConfig.resetToDefaults();
		FluidNetConfig.maxMainIO = 1000000;
		FluidNetConfig.packCapMax = 1000000;
	}
}
