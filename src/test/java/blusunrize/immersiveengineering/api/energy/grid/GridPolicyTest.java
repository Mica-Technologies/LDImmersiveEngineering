/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.grid;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GridPolicy} is the only thing standing between a hand-edited save (or a crafted
 * packet) and a segment that ignores the server's limits, so its clamping gets tested
 * harder than its happy path.
 */
class GridPolicyTest
{
	@BeforeEach
	void setUp()
	{
		GridConfig.resetToDefaults();
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	@Nested
	@DisplayName("defaults")
	class Defaults
	{
		@Test
		@DisplayName("a new policy carries the configured defaults")
		void newPolicyUsesConfig()
		{
			GridPolicy policy = new GridPolicy();
			assertEquals(GridConfig.maxSegmentIO, policy.getMaxInput());
			assertEquals(GridConfig.maxSegmentIO, policy.getMaxOutput());
			assertEquals(GridConfig.defaultLossPct, policy.getLossPct(), 0.0);
			assertEquals(GridConfig.failoverTopUpDefault, policy.isFailoverTopUp());
		}

		@Test
		@DisplayName("loss ships at zero -- the grid is lossless unless a pack says otherwise")
		void lossShipsAtZero()
		{
			assertEquals(0.0, new GridPolicy().getLossPct(), 0.0);
		}

		@Test
		@DisplayName("a new policy follows a changed config")
		void newPolicyFollowsConfig()
		{
			GridConfig.maxSegmentIO = 512;
			GridConfig.defaultLossPct = 0.25;
			GridConfig.failoverTopUpDefault = false;
			GridPolicy policy = new GridPolicy();
			assertEquals(512, policy.getMaxInput());
			assertEquals(512, policy.getMaxOutput());
			assertEquals(0.25, policy.getLossPct(), 1e-9);
			assertFalse(policy.isFailoverTopUp());
		}

		@Test
		@DisplayName("default buffer is bufferTicks worth of output")
		void defaultBufferIsTwoTicks()
		{
			GridConfig.bufferTicks = 2;
			assertEquals(2000, GridPolicy.defaultBufferCap(1000));
			GridConfig.bufferTicks = 5;
			assertEquals(5000, GridPolicy.defaultBufferCap(1000));
		}

		@Test
		@DisplayName("default buffer never exceeds the anti-battery clamp")
		void defaultBufferRespectsCeiling()
		{
			GridConfig.bufferCapMax = 100;
			assertEquals(100, GridPolicy.defaultBufferCap(1000));
		}

		@Test
		@DisplayName("default buffer of a zero-output segment is zero")
		void defaultBufferOfZeroOutput()
		{
			assertEquals(0, GridPolicy.defaultBufferCap(0));
		}

		@Test
		@DisplayName("default buffer treats a negative output as zero")
		void defaultBufferOfNegativeOutput()
		{
			assertEquals(0, GridPolicy.defaultBufferCap(-500));
		}

		@Test
		@DisplayName("default buffer cannot overflow int for a huge output")
		void defaultBufferDoesNotOverflow()
		{
			GridConfig.bufferCapMax = Integer.MAX_VALUE;
			GridConfig.bufferTicks = 20;
			assertTrue(GridPolicy.defaultBufferCap(Integer.MAX_VALUE) > 0);
		}
	}

	@Nested
	@DisplayName("clamping")
	class Clamping
	{
		@Test
		@DisplayName("input is clamped to the configured ceiling")
		void inputClampedToCeiling()
		{
			GridConfig.maxSegmentIO = 1000;
			GridPolicy policy = new GridPolicy();
			policy.setMaxInput(999999);
			assertEquals(1000, policy.getMaxInput());
		}

		@Test
		@DisplayName("output is clamped to the configured ceiling")
		void outputClampedToCeiling()
		{
			GridConfig.maxSegmentIO = 1000;
			GridPolicy policy = new GridPolicy();
			policy.setMaxOutput(999999);
			assertEquals(1000, policy.getMaxOutput());
		}

		@Test
		@DisplayName("negative caps become zero rather than reversing the flow")
		void negativeCapsBecomeZero()
		{
			GridPolicy policy = new GridPolicy();
			policy.setMaxInput(-1);
			policy.setMaxOutput(Integer.MIN_VALUE);
			assertEquals(0, policy.getMaxInput());
			assertEquals(0, policy.getMaxOutput());
		}

		@Test
		@DisplayName("loss is clamped into 0..1")
		void lossClampedToUnitInterval()
		{
			GridPolicy policy = new GridPolicy();
			policy.setLossPct(-5);
			assertEquals(0.0, policy.getLossPct(), 0.0);
			policy.setLossPct(37);
			assertEquals(1.0, policy.getLossPct(), 0.0);
			policy.setLossPct(0.5);
			assertEquals(0.5, policy.getLossPct(), 1e-9);
		}

		@Test
		@DisplayName("loss accepts the exact endpoints")
		void lossAcceptsEndpoints()
		{
			GridPolicy policy = new GridPolicy();
			policy.setLossPct(0.0);
			assertEquals(0.0, policy.getLossPct(), 0.0);
			policy.setLossPct(1.0);
			assertEquals(1.0, policy.getLossPct(), 0.0);
		}

		@Test
		@DisplayName("buffer is clamped to the anti-battery ceiling")
		void bufferClampedToCeiling()
		{
			GridConfig.bufferCapMax = 4096;
			GridPolicy policy = new GridPolicy();
			policy.setBufferCap(Integer.MAX_VALUE);
			assertEquals(4096, policy.getBufferCap());
		}

		@Test
		@DisplayName("a negative buffer becomes zero")
		void negativeBufferBecomesZero()
		{
			GridPolicy policy = new GridPolicy();
			policy.setBufferCap(-9999);
			assertEquals(0, policy.getBufferCap());
		}

		@Test
		@DisplayName("clamp() pulls an existing policy back inside a lowered ceiling")
		void clampReappliesLoweredCeiling()
		{
			GridConfig.maxSegmentIO = 100000;
			GridConfig.bufferCapMax = 100000;
			GridPolicy policy = new GridPolicy();
			policy.setMaxInput(50000);
			policy.setMaxOutput(50000);
			policy.setBufferCap(50000);

			//Server owner lowers the limits and reloads the config.
			GridConfig.maxSegmentIO = 1024;
			GridConfig.bufferCapMax = 2048;
			policy.clamp();

			assertEquals(1024, policy.getMaxInput());
			assertEquals(1024, policy.getMaxOutput());
			assertEquals(2048, policy.getBufferCap());
		}

		@Test
		@DisplayName("clamp() leaves an in-range policy alone")
		void clampIsIdempotentInRange()
		{
			GridPolicy policy = new GridPolicy();
			policy.setMaxInput(500);
			policy.setMaxOutput(600);
			policy.setBufferCap(700);
			policy.setLossPct(0.1);
			policy.clamp();
			policy.clamp();
			assertEquals(500, policy.getMaxInput());
			assertEquals(600, policy.getMaxOutput());
			assertEquals(700, policy.getBufferCap());
			assertEquals(0.1, policy.getLossPct(), 1e-9);
		}
	}

	@Nested
	@DisplayName("persistence")
	class Persistence
	{
		@Test
		@DisplayName("round-trips every field")
		void roundTripsAllFields()
		{
			GridPolicy original = new GridPolicy();
			original.setMaxInput(1234);
			original.setMaxOutput(2345);
			original.setLossPct(0.125);
			original.setBufferCap(3456);
			original.setFailoverTopUp(false);

			GridPolicy loaded = GridPolicy.readFromNBT(original.writeToNBT(new NBTTagCompound()));

			assertEquals(1234, loaded.getMaxInput());
			assertEquals(2345, loaded.getMaxOutput());
			assertEquals(0.125, loaded.getLossPct(), 1e-9);
			assertEquals(3456, loaded.getBufferCap());
			assertFalse(loaded.isFailoverTopUp());
		}

		@Test
		@DisplayName("round-trips failoverTopUp when true as well")
		void roundTripsTopUpTrue()
		{
			GridPolicy original = new GridPolicy();
			original.setFailoverTopUp(true);
			assertTrue(GridPolicy.readFromNBT(original.writeToNBT(new NBTTagCompound())).isFailoverTopUp());
		}

		@Test
		@DisplayName("null NBT yields a default policy rather than throwing")
		void nullNbtYieldsDefaults()
		{
			GridPolicy policy = GridPolicy.readFromNBT(null);
			assertNotNull(policy);
			assertEquals(GridConfig.maxSegmentIO, policy.getMaxInput());
		}

		@Test
		@DisplayName("an empty tag yields a default policy")
		void emptyNbtYieldsDefaults()
		{
			GridPolicy policy = GridPolicy.readFromNBT(new NBTTagCompound());
			assertEquals(GridConfig.maxSegmentIO, policy.getMaxOutput());
			assertEquals(GridConfig.defaultLossPct, policy.getLossPct(), 0.0);
		}

		@Test
		@DisplayName("a partial tag keeps defaults for the absent keys")
		void partialNbtKeepsDefaults()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("maxInput", 77);
			GridPolicy policy = GridPolicy.readFromNBT(nbt);
			assertEquals(77, policy.getMaxInput());
			assertEquals(GridConfig.maxSegmentIO, policy.getMaxOutput());
		}

		@Test
		@DisplayName("values loaded from NBT are clamped to the current config")
		void loadedValuesAreClamped()
		{
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setInteger("maxInput", 999999);
			nbt.setInteger("bufferCap", 999999);
			nbt.setDouble("lossPct", 4.0);
			GridConfig.maxSegmentIO = 256;
			GridConfig.bufferCapMax = 512;

			GridPolicy policy = GridPolicy.readFromNBT(nbt);
			assertEquals(256, policy.getMaxInput());
			assertEquals(512, policy.getBufferCap());
			assertEquals(1.0, policy.getLossPct(), 0.0);
		}
	}

	@Nested
	@DisplayName("copy")
	class Copy
	{
		@Test
		@DisplayName("copies every field")
		void copiesEveryField()
		{
			GridPolicy original = new GridPolicy();
			original.setMaxInput(11);
			original.setMaxOutput(22);
			original.setLossPct(0.33);
			original.setBufferCap(44);
			original.setFailoverTopUp(false);

			GridPolicy copy = original.copy();
			assertEquals(11, copy.getMaxInput());
			assertEquals(22, copy.getMaxOutput());
			assertEquals(0.33, copy.getLossPct(), 1e-9);
			assertEquals(44, copy.getBufferCap());
			assertFalse(copy.isFailoverTopUp());
		}

		@Test
		@DisplayName("the copy is independent of the original")
		void copyIsIndependent()
		{
			GridPolicy original = new GridPolicy();
			original.setMaxInput(11);
			GridPolicy copy = original.copy();
			copy.setMaxInput(99);
			assertEquals(11, original.getMaxInput());
			assertEquals(99, copy.getMaxInput());
		}
	}
}
