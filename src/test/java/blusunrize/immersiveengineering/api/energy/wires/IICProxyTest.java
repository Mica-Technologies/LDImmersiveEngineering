/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IICProxy} stands in for an unloaded connector: a dimension, a position and whether energy
 * may pass. Everything else about it is a deliberately inert stub.
 */
class IICProxyTest
{
	@Nested
	@DisplayName("construction and accessors")
	class Accessors
	{
		@Test
		@DisplayName("keeps the position and dimension it was built with")
		void keepsPositionAndDimension()
		{
			IICProxy p = new IICProxy(true, 7, new BlockPos(1, 2, 3));
			assertEquals(new BlockPos(1, 2, 3), p.getPos());
			assertEquals(7, p.getDimension());
		}

		@Test
		@DisplayName("reports the energy-pass flag it was built with")
		void reportsEnergyPassFlag()
		{
			assertTrue(new IICProxy(true, 0, BlockPos.ORIGIN).allowEnergyToPass(null));
			assertFalse(new IICProxy(false, 0, BlockPos.ORIGIN).allowEnergyToPass(null));
		}

		@Test
		@DisplayName("the energy-pass answer does not depend on the connection asked about")
		void energyPassIgnoresConnection()
		{
			IICProxy p = new IICProxy(true, 0, BlockPos.ORIGIN);
			Connection c = new Connection(BlockPos.ORIGIN, new BlockPos(4, 0, 0), null, 4);
			assertEquals(p.allowEnergyToPass(null), p.allowEnergyToPass(c));
		}

		@Test
		@DisplayName("handles a negative dimension id")
		void negativeDimension()
		{
			assertEquals(-1, new IICProxy(false, -1, BlockPos.ORIGIN).getDimension());
		}

		@Test
		@DisplayName("handles far-out and negative coordinates")
		void extremeCoordinates()
		{
			BlockPos pos = new BlockPos(-29999999, 255, 29999999);
			assertEquals(pos, new IICProxy(false, 0, pos).getPos());
		}

		@Test
		@DisplayName("the TileEntity constructor rejects a null tile rather than storing it")
		void tileEntityConstructorRejectsNull()
		{
			assertThrows(IllegalArgumentException.class, () -> new IICProxy((TileEntity)null));
		}
	}

	@Nested
	@DisplayName("inert connectable behaviour")
	class Inert
	{
		private final IICProxy proxy = new IICProxy(true, 3, new BlockPos(5, 6, 7));

		@Test
		@DisplayName("never accepts new connections")
		void neverConnects()
		{
			assertFalse(proxy.canConnect());
			assertFalse(proxy.canConnectCable(null, null, new Vec3i(0, 0, 0)));
			assertFalse(proxy.canConnectCable(null, null));
		}

		@Test
		@DisplayName("is never an energy output and never moves energy")
		void neverOutputsEnergy()
		{
			assertFalse(proxy.isEnergyOutput());
			assertEquals(0, proxy.outputEnergy(1000, false, 0));
			assertEquals(0, proxy.outputEnergy(1000, true, 1));
			assertEquals(0, proxy.outputEnergy(Integer.MAX_VALUE, false, 0));
		}

		@Test
		@DisplayName("has no cable limiter and no attachment offset")
		void noLimiterOrOffset()
		{
			assertNull(proxy.getCableLimiter(null));
			assertNull(proxy.getConnectionOffset(null));
		}

		@Test
		@DisplayName("the connection master is its own position")
		void connectionMasterIsSelf()
		{
			assertEquals(new BlockPos(5, 6, 7), proxy.getConnectionMaster(null, null));
			assertSame(proxy.getPos(), proxy.getConnectionMaster(null, null));
		}

		@Test
		@DisplayName("the no-op hooks do not throw")
		void noOpsAreSafe()
		{
			assertDoesNotThrow(() -> proxy.connectCable(null, null, null));
			assertDoesNotThrow(() -> proxy.onEnergyPassthrough(500));
			assertDoesNotThrow(() -> proxy.onEnergyPassthrough(500d));
			assertDoesNotThrow(() -> proxy.addAvailableEnergy(1f, f -> {
			}));
		}

		@Test
		@DisplayName("reports no available energy and no damage")
		void noEnergyNoDamage()
		{
			assertNull(proxy.getAvailableEnergy(null));
			assertEquals(0f, proxy.getDamageAmount(null, null), 1e-9f);
			assertFalse(proxy.moveConnectionTo(null, null));
		}

		@Test
		@DisplayName("ignores its own position when raytracing")
		void ignoresOwnPosition()
		{
			Set<BlockPos> ignored = proxy.getIgnored(null);
			assertEquals(1, ignored.size());
			assertTrue(ignored.contains(new BlockPos(5, 6, 7)));
		}

		@Test
		@DisplayName("the deprecated raytrace offset is the block centre")
		void raytraceOffsetIsCentre()
		{
			assertEquals(.5, proxy.getRaytraceOffset(null).x, 1e-9);
			assertEquals(.5, proxy.getRaytraceOffset(null).y, 1e-9);
			assertEquals(.5, proxy.getRaytraceOffset(null).z, 1e-9);
		}
	}

	@Nested
	@DisplayName("NBT")
	class Nbt
	{
		@Test
		@DisplayName("writeToNBT stores the dimension, the coordinates and the pass flag")
		void writeStoresEverything()
		{
			NBTTagCompound tag = new IICProxy(true, -1, new BlockPos(10, 20, 30)).writeToNBT();
			assertEquals(-1, tag.getInteger("dim"));
			assertEquals(10, tag.getInteger("x"));
			assertEquals(20, tag.getInteger("y"));
			assertEquals(30, tag.getInteger("z"));
			assertTrue(tag.getBoolean("pass"));
			assertEquals(5, tag.getSize());
		}

		@Test
		@DisplayName("a write/read round-trip preserves everything")
		void roundTrip()
		{
			IICProxy src = new IICProxy(true, 42, new BlockPos(-7, 128, 9));
			IICProxy dst = IICProxy.readFromNBT(src.writeToNBT());
			assertEquals(src.getDimension(), dst.getDimension());
			assertEquals(src.getPos(), dst.getPos());
			assertEquals(src.allowEnergyToPass(null), dst.allowEnergyToPass(null));
		}

		@Test
		@DisplayName("a false pass flag round-trips as false")
		void roundTripFalseFlag()
		{
			IICProxy dst = IICProxy.readFromNBT(new IICProxy(false, 0, BlockPos.ORIGIN).writeToNBT());
			assertFalse(dst.allowEnergyToPass(null));
		}

		@Test
		@DisplayName("extreme values round-trip")
		void roundTripExtremes()
		{
			IICProxy src = new IICProxy(true, Integer.MIN_VALUE,
					new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, 0));
			IICProxy dst = IICProxy.readFromNBT(src.writeToNBT());
			assertEquals(Integer.MIN_VALUE, dst.getDimension());
			assertEquals(src.getPos(), dst.getPos());
		}

		@Test
		@DisplayName("an empty tag reads as a non-passing proxy at the origin of dimension 0")
		void readEmptyTag()
		{
			IICProxy p = IICProxy.readFromNBT(new NBTTagCompound());
			assertNotNull(p, "the reader has no validation, so it always produces a proxy");
			assertEquals(0, p.getDimension());
			assertEquals(BlockPos.ORIGIN, p.getPos());
			assertFalse(p.allowEnergyToPass(null));
		}

		@Test
		@DisplayName("reading twice from the same tag yields independent but equivalent proxies")
		void readTwice()
		{
			NBTTagCompound tag = new IICProxy(true, 2, new BlockPos(1, 1, 1)).writeToNBT();
			IICProxy a = IICProxy.readFromNBT(tag);
			IICProxy b = IICProxy.readFromNBT(tag);
			assertNotSame(a, b);
			assertEquals(a.getPos(), b.getPos());
			assertEquals(a.getDimension(), b.getDimension());
		}

		@Test
		@DisplayName("writeToNBT returns a fresh tag each time")
		void writeReturnsFreshTag()
		{
			IICProxy p = new IICProxy(true, 1, BlockPos.ORIGIN);
			assertNotSame(p.writeToNBT(), p.writeToNBT());
			assertEquals(p.writeToNBT(), p.writeToNBT(), "but the contents are identical");
		}
	}
}
