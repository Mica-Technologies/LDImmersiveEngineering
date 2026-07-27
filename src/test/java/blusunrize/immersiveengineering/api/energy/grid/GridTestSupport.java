/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;

/**
 * Shared builders for the grid tests.
 * <p>
 * {@link GridConfig} is process-global, so every test class here resets it in a
 * {@code @BeforeEach}; these helpers exist so a test can say what it is actually about
 * rather than restating six lines of wiring.
 */
public final class GridTestSupport
{
	private GridTestSupport()
	{
	}

	private static int nextCoord = 0;

	/**
	 * A fresh position, distinct from every other one handed out this run, so devices in a
	 * test never collide by accident.
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
	 * Registers an online feed unit on {@code segment} with the given supply.
	 */
	public static GridDevice feed(VirtualGrid grid, GridSegment segment, int available, int cap)
	{
		return feed(grid, segment, available, cap, 0);
	}

	public static GridDevice feed(VirtualGrid grid, GridSegment segment, int available, int cap,
								  int priority)
	{
		GridDevice device = grid.registerDevice(pos(), GridDeviceType.FEED);
		device.setTransferCap(cap);
		device.setPriority(priority);
		grid.assignDevice(device, segment==null?null: segment.getId());
		device.setEndpoint(FakeEndpoint.feeding(available));
		if(segment!=null)
			segment.invalidateViews();
		return device;
	}

	/**
	 * Registers an online service unit on {@code segment} with the given demand.
	 */
	public static GridDevice service(VirtualGrid grid, GridSegment segment, int demand, int cap)
	{
		return service(grid, segment, demand, cap, 0, false);
	}

	public static GridDevice service(VirtualGrid grid, GridSegment segment, int demand, int cap,
									 int priority, boolean critical)
	{
		GridDevice device = grid.registerDevice(pos(), GridDeviceType.SERVICE);
		device.setTransferCap(cap);
		device.setPriority(priority);
		device.setCritical(critical);
		grid.assignDevice(device, segment==null?null: segment.getId());
		device.setEndpoint(FakeEndpoint.accepting(demand));
		if(segment!=null)
			segment.invalidateViews();
		return device;
	}

	/**
	 * Registers an online Signal Unit on {@code segment}.
	 *
	 * @param output true for a unit that reports the segment, false for a kill switch
	 * @param high   the redstone level the world is presenting to it
	 */
	public static GridDevice signal(VirtualGrid grid, GridSegment segment, boolean output,
									boolean inverted, boolean high)
	{
		GridDevice device = grid.registerDevice(pos(), GridDeviceType.SIGNAL);
		device.setSignalOutput(output);
		device.setSignalInverted(inverted);
		grid.assignDevice(device, segment==null?null: segment.getId());
		device.setEndpoint(FakeEndpoint.signal(high));
		if(segment!=null)
			segment.invalidateViews();
		return device;
	}

	public static FakeEndpoint endpointOf(GridDevice device)
	{
		return (FakeEndpoint)device.getEndpoint();
	}

	/**
	 * A segment with generous caps and no loss, so a test only has to change the one knob
	 * it is actually interested in.
	 */
	public static GridSegment segment(VirtualGrid grid, String name)
	{
		GridSegment segment = grid.createSegment(name);
		segment.getPolicy().setMaxInput(100000);
		segment.getPolicy().setMaxOutput(100000);
		segment.getPolicy().setBufferCap(100000);
		segment.getPolicy().setLossPct(0);
		return segment;
	}

	/**
	 * Restores {@link GridConfig} and widens the ceilings so the helpers above are not
	 * silently clamped by the shipped defaults.
	 */
	public static void resetConfig()
	{
		GridConfig.resetToDefaults();
		GridConfig.maxSegmentIO = 1000000;
		GridConfig.bufferCapMax = 1000000;
	}
}
