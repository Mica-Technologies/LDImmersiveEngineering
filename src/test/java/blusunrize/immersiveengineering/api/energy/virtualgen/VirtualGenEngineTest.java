/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VirtualGenEngine}: which plants push, from where, how much -- against a fake world that records
 * every push and never loads anything.
 */
class VirtualGenEngineTest
{
	private static final BlockPos GEN = new BlockPos(0, 60, 0);
	private static final BlockPos METER = new BlockPos(1, 60, 0);
	private static final BlockPos CONN = new BlockPos(2, 60, 0);

	private VirtualGeneration registry;
	private FakeWorld world;

	static final class FakeWorld implements IVirtualGenWorld
	{
		final Set<Integer> dims = new HashSet<>(Collections.singleton(0));
		final Set<BlockPos> loaded = new HashSet<>();
		final List<Object[]> pushes = new ArrayList<>();
		/**
		 * How much the network accepts per push; -1 accepts everything.
		 */
		int acceptLimit = -1;

		@Override
		public boolean isDimensionLoaded(int dimension)
		{
			return dims.contains(dimension);
		}

		@Override
		public boolean isLoaded(int dimension, BlockPos pos)
		{
			return dims.contains(dimension)&&loaded.contains(pos);
		}

		@Override
		public int push(int dimension, BlockPos source, int amount, int rate, boolean cityMode)
		{
			pushes.add(new Object[]{dimension, source, amount, rate, cityMode});
			return acceptLimit < 0?amount: Math.min(amount, acceptLimit);
		}
	}

	@BeforeEach
	void setUp()
	{
		VirtualGenConfig.resetToDefaults();
		registry = new VirtualGeneration();
		world = new FakeWorld();
	}

	@AfterEach
	void tearDown()
	{
		VirtualGenConfig.resetToDefaults();
	}

	private VirtualSource plant(BlockPos meter, BlockPos gen, BlockPos conn, int rate, int connectorRate, boolean free)
	{
		VirtualSource source = registry.getOrCreate(0, meter, gen, conn);
		source.updateMeasurement(rate, connectorRate, free, "immersiveengineering:dynamo", 100);
		return source;
	}

	@Nested
	@DisplayName("real or virtual")
	class RealOrVirtual
	{
		@Test
		@DisplayName("a fully loaded plant is running for real and is not pushed")
		void loadedNotPushed()
		{
			plant(METER, GEN, CONN, 4096, 4096, true);
			world.loaded.addAll(Arrays.asList(GEN, METER, CONN));
			assertEquals(0, VirtualGenEngine.tick(registry, world, true));
			assertTrue(world.pushes.isEmpty());
		}

		@Test
		@DisplayName("an unloaded plant pushes its measured rate from its connector")
		void unloadedPushes()
		{
			VirtualSource source = plant(METER, GEN, CONN, 3000, 4096, true);
			assertEquals(3000, VirtualGenEngine.tick(registry, world, true));
			assertEquals(1, world.pushes.size());
			assertEquals(CONN, world.pushes.get(0)[1]);
			assertEquals(3000, world.pushes.get(0)[2]);
			assertTrue(source.isVirtualActive());
			assertEquals(3000, source.getLastVirtualDelivered());
		}

		@Test
		@DisplayName("any one of generator, meter or connector unloaded breaks the real path, so the plant goes virtual")
		void partlyLoaded()
		{
			plant(METER, GEN, CONN, 1000, 4096, true);
			for(BlockPos missing : Arrays.asList(GEN, METER, CONN))
			{
				world.pushes.clear();
				world.loaded.clear();
				world.loaded.addAll(Arrays.asList(GEN, METER, CONN));
				world.loaded.remove(missing);
				VirtualGenEngine.tick(registry, world, true);
				assertEquals(1, world.pushes.size(), "unloaded: "+missing);
			}
		}

		@Test
		@DisplayName("a plant in an unloaded dimension is not pushed -- nothing there could receive it")
		void unloadedDimension()
		{
			plant(METER, GEN, CONN, 1000, 4096, true);
			world.dims.clear();
			VirtualGenEngine.tick(registry, world, true);
			assertTrue(world.pushes.isEmpty());
		}
	}

	@Nested
	@DisplayName("qualification")
	class Qualification
	{
		@Test
		@DisplayName("a free source runs virtually in both modes")
		void freeBothModes()
		{
			plant(METER, GEN, CONN, 1000, 4096, true);
			VirtualGenEngine.tick(registry, world, false);
			VirtualGenEngine.tick(registry, world, true);
			assertEquals(2, world.pushes.size());
			assertEquals(false, world.pushes.get(0)[4]);
			assertEquals(true, world.pushes.get(1)[4]);
		}

		@Test
		@DisplayName("a fuel-burning plant runs virtually only in City Mode")
		void fuelOnlyCity()
		{
			VirtualSource source = plant(METER, GEN, CONN, 1000, 4096, false);
			VirtualGenEngine.tick(registry, world, false);
			assertTrue(world.pushes.isEmpty());
			assertFalse(source.isVirtualActive());
			VirtualGenEngine.tick(registry, world, true);
			assertEquals(1, world.pushes.size());
		}

		@Test
		@DisplayName("a disabled meter, a zero measurement and the master switch all stop the push")
		void stops()
		{
			VirtualSource source = plant(METER, GEN, CONN, 1000, 4096, true);
			source.setEnabled(false);
			VirtualGenEngine.tick(registry, world, true);
			source.setEnabled(true);
			source.updateMeasurement(0, 4096, true, null, 200);
			VirtualGenEngine.tick(registry, world, true);
			source.updateMeasurement(1000, 4096, true, null, 300);
			VirtualGenConfig.enabled = false;
			VirtualGenEngine.tick(registry, world, true);
			assertTrue(world.pushes.isEmpty());
		}
	}

	@Nested
	@DisplayName("caps and coalescing")
	class Caps
	{
		@Test
		@DisplayName("the push never exceeds the connector's rate")
		void connectorCap()
		{
			plant(METER, GEN, CONN, 10000, 4096, true);
			VirtualGenEngine.tick(registry, world, true);
			assertEquals(4096, world.pushes.get(0)[2]);
		}

		@Test
		@DisplayName("the push never exceeds the configured ceiling")
		void configCap()
		{
			VirtualGenConfig.maxRate = 500;
			plant(METER, GEN, CONN, 10000, 4096, true);
			VirtualGenEngine.tick(registry, world, true);
			assertEquals(500, world.pushes.get(0)[2]);
		}

		@Test
		@DisplayName("meters feeding one connector push once, summed and capped at that connector")
		void coalesced()
		{
			VirtualSource a = plant(new BlockPos(1, 60, 0), new BlockPos(0, 60, 0), CONN, 3000, 4096, true);
			VirtualSource b = plant(new BlockPos(2, 61, 0), new BlockPos(2, 62, 0), CONN, 3000, 4096, true);
			VirtualGenEngine.tick(registry, world, true);
			assertEquals(1, world.pushes.size());
			assertEquals(4096, world.pushes.get(0)[2]);
			assertEquals(4096, a.getLastVirtualDelivered()+b.getLastVirtualDelivered(),
					"readouts split the delivery without losing any of it");
		}

		@Test
		@DisplayName("meters on different connectors push separately")
		void separate()
		{
			plant(new BlockPos(1, 60, 0), new BlockPos(0, 60, 0), new BlockPos(2, 60, 0), 1000, 4096, true);
			plant(new BlockPos(1, 60, 5), new BlockPos(0, 60, 5), new BlockPos(2, 60, 5), 1000, 4096, true);
			assertEquals(2000, VirtualGenEngine.tick(registry, world, true));
			assertEquals(2, world.pushes.size());
		}

		@Test
		@DisplayName("delivery reported is what the network accepted, not what was offered")
		void partialAcceptance()
		{
			world.acceptLimit = 24;
			VirtualSource source = plant(METER, GEN, CONN, 4096, 4096, true);
			assertEquals(24, VirtualGenEngine.tick(registry, world, true));
			assertEquals(24, source.getLastVirtualDelivered());
		}

		@Test
		@DisplayName("a connector rate of zero (unknown) leaves only the configured ceiling")
		void unknownConnectorRate()
		{
			plant(METER, GEN, CONN, 5000, 0, true);
			VirtualGenEngine.tick(registry, world, true);
			assertEquals(5000, world.pushes.get(0)[2]);
		}
	}

	@Nested
	@DisplayName("registry and persistence")
	class Registry
	{
		@Test
		@DisplayName("getOrCreate returns the same record and follows a rotated meter")
		void getOrCreate()
		{
			VirtualSource first = registry.getOrCreate(0, METER, GEN, CONN);
			VirtualSource again = registry.getOrCreate(0, METER, CONN, GEN);
			assertSame(first, again);
			assertEquals(CONN, again.getGeneratorPos());
			assertEquals(GEN, again.getSourcePos());
			assertEquals(1, registry.size());
		}

		@Test
		@DisplayName("a record round-trips through NBT with everything that is saved")
		void roundTrip()
		{
			VirtualSource source = plant(METER, GEN, CONN, 2500, 1024, false);
			source.setEnabled(false);
			NBTTagCompound tag = registry.writeToNBT(new NBTTagCompound());
			VirtualGeneration loaded = new VirtualGeneration();
			loaded.readFromNBT(tag);
			VirtualSource back = loaded.get(0, METER);
			assertNotNull(back);
			assertEquals(GEN, back.getGeneratorPos());
			assertEquals(CONN, back.getSourcePos());
			assertEquals(2500, back.getMeasuredRate());
			assertEquals(1024, back.getConnectorRate());
			assertFalse(back.isFreeSource());
			assertFalse(back.isEnabled());
			assertEquals("immersiveengineering:dynamo", back.getGeneratorId());
			assertEquals(100, back.getLastMeasuredTime());
		}

		@Test
		@DisplayName("a record written before the enabled flag existed loads enabled")
		void legacyEnabled()
		{
			NBTTagCompound tag = plant(METER, GEN, CONN, 1, 1, true).writeToNBT();
			tag.removeTag("enabled");
			assertTrue(VirtualSource.readFromNBT(tag).isEnabled());
		}

		@Test
		@DisplayName("a truncated record is skipped rather than loaded broken")
		void truncated()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("dim", 0);
			assertNull(VirtualSource.readFromNBT(tag));
		}

		@Test
		@DisplayName("adding and removing mark the save dirty; a no-op getOrCreate does not")
		void dirty()
		{
			int[] count = {0};
			registry.setDirtyListener(() -> count[0]++);
			registry.getOrCreate(0, METER, GEN, CONN);
			registry.getOrCreate(0, METER, GEN, CONN);
			assertEquals(1, count[0]);
			assertTrue(registry.remove(0, METER));
			assertFalse(registry.remove(0, METER));
			assertEquals(2, count[0]);
		}

		@Test
		@DisplayName("updateMeasurement reports whether anything saved changed")
		void measurementChanged()
		{
			VirtualSource source = registry.getOrCreate(0, METER, GEN, CONN);
			assertTrue(source.updateMeasurement(10, 20, true, "a", 1));
			assertFalse(source.updateMeasurement(10, 20, true, "a", 2), "only the timestamp moved");
			assertTrue(source.updateMeasurement(11, 20, true, "a", 3));
		}
	}
}
