/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.tool.ConveyorHandler.ConveyorDirection;
import blusunrize.immersiveengineering.api.tool.ConveyorHandler.IConveyorBelt;
import blusunrize.immersiveengineering.api.tool.ConveyorHandler.IConveyorTile;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the pure registry bookkeeping in {@link ConveyorHandler} plus the default methods
 * of {@link IConveyorBelt} that do not need a live world.
 * <p>
 * Everything that reaches for a {@code World}, a {@code TileEntity} or an {@code Entity}
 * ({@code isConveyor}, {@code getDirection}, {@code onEntityCollision}, ...) is out of reach
 * without a Forge bootstrap and is not covered here.
 */
class ConveyorHandlerTest
{
	private static final ResourceLocation KEY_A = new ResourceLocation("immersiveengineering:conveyor_a");
	private static final ResourceLocation KEY_B = new ResourceLocation("immersiveengineering:conveyor_b");

	private HashMap<ResourceLocation, Class<? extends IConveyorBelt>> savedClassRegistry;
	private HashMap<ResourceLocation, Set<ResourceLocation>> savedSubstitutes;
	private HashMap<ResourceLocation, java.util.function.Function<TileEntity, ? extends IConveyorBelt>> savedFunctions;
	private HashMap<Class<? extends IConveyorBelt>, ResourceLocation> savedReverse;
	private Set<BiConsumer<Entity, IConveyorTile>> savedMagnet;
	private Set<BiConsumer<Entity, IConveyorTile>> savedMagnetReverse;

	@BeforeEach
	void isolateRegistries()
	{
		savedClassRegistry = ConveyorHandler.classRegistry;
		savedSubstitutes = ConveyorHandler.substituteRegistry;
		savedFunctions = ConveyorHandler.functionRegistry;
		savedReverse = ConveyorHandler.reverseClassRegistry;
		savedMagnet = ConveyorHandler.magnetSupressionFunctions;
		savedMagnetReverse = ConveyorHandler.magnetSupressionReverse;

		ConveyorHandler.classRegistry = new LinkedHashMap<>();
		ConveyorHandler.substituteRegistry = new HashMap<>();
		ConveyorHandler.functionRegistry = new LinkedHashMap<>();
		ConveyorHandler.reverseClassRegistry = new LinkedHashMap<>();
		ConveyorHandler.magnetSupressionFunctions = new HashSet<>();
		ConveyorHandler.magnetSupressionReverse = new HashSet<>();
	}

	@AfterEach
	void restoreRegistries()
	{
		ConveyorHandler.classRegistry = savedClassRegistry;
		ConveyorHandler.substituteRegistry = savedSubstitutes;
		ConveyorHandler.functionRegistry = savedFunctions;
		ConveyorHandler.reverseClassRegistry = savedReverse;
		ConveyorHandler.magnetSupressionFunctions = savedMagnet;
		ConveyorHandler.magnetSupressionReverse = savedMagnetReverse;
	}

	/** A conveyor that answers everything from plain fields, so no world is ever touched. */
	private static class TestConveyor implements IConveyorBelt
	{
		ConveyorDirection direction = ConveyorDirection.HORIZONTAL;
		boolean active = true;
		int colour = 0;
		boolean wall0 = true;
		boolean wall1 = true;

		@Override
		public ConveyorDirection getConveyorDirection()
		{
			return direction;
		}

		@Override
		public boolean changeConveyorDirection()
		{
			direction = ConveyorDirection.values()[(direction.ordinal()+1)%ConveyorDirection.values().length];
			return true;
		}

		@Override
		public boolean setConveyorDirection(ConveyorDirection dir)
		{
			this.direction = dir;
			return true;
		}

		@Override
		public boolean isActive(TileEntity tile)
		{
			return active;
		}

		@Override
		public boolean canBeDyed()
		{
			return true;
		}

		@Override
		public boolean setDyeColour(int colour)
		{
			this.colour = colour;
			return true;
		}

		@Override
		public int getDyeColour()
		{
			return colour;
		}

		@Override
		public boolean renderWall(TileEntity tile, EnumFacing facing, int wall)
		{
			return wall==0?wall0: wall1;
		}

		@Override
		public NBTTagCompound writeConveyorNBT()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger("colour", colour);
			return tag;
		}

		@Override
		public void readConveyorNBT(NBTTagCompound nbt)
		{
			this.colour = nbt.getInteger("colour");
		}

		@Override
		public ResourceLocation getActiveTexture()
		{
			return null;
		}

		@Override
		public ResourceLocation getInactiveTexture()
		{
			return null;
		}
	}

	private static class OtherConveyor extends TestConveyor
	{
	}

	@Nested
	@DisplayName("registration")
	class Registration
	{
		@Test
		@DisplayName("registering fills the forward, reverse and factory registries")
		void registrationFillsAllThreeRegistries()
		{
			assertTrue(ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> new TestConveyor()));

			assertSame(TestConveyor.class, ConveyorHandler.classRegistry.get(KEY_A));
			assertEquals(KEY_A, ConveyorHandler.reverseClassRegistry.get(TestConveyor.class));
			assertNotNull(ConveyorHandler.functionRegistry.get(KEY_A));
		}

		@Test
		@DisplayName("a duplicate key is rejected and leaves the original registration alone")
		void duplicateKeyIsRejected()
		{
			ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> new TestConveyor());

			assertFalse(ConveyorHandler.registerConveyorHandler(KEY_A, OtherConveyor.class, t -> new OtherConveyor()));
			assertSame(TestConveyor.class, ConveyorHandler.classRegistry.get(KEY_A));
			assertNull(ConveyorHandler.reverseClassRegistry.get(OtherConveyor.class));
		}

		@Test
		@DisplayName("two different keys can be registered side by side")
		void twoKeysCoexist()
		{
			assertTrue(ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> new TestConveyor()));
			assertTrue(ConveyorHandler.registerConveyorHandler(KEY_B, OtherConveyor.class, t -> new OtherConveyor()));

			assertEquals(2, ConveyorHandler.classRegistry.size());
			assertEquals(KEY_A, ConveyorHandler.reverseClassRegistry.get(TestConveyor.class));
			assertEquals(KEY_B, ConveyorHandler.reverseClassRegistry.get(OtherConveyor.class));
		}

		@Test
		@DisplayName("getConveyor builds a fresh instance from the registered factory")
		void getConveyorBuildsFreshInstances()
		{
			ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> new TestConveyor());

			IConveyorBelt first = ConveyorHandler.getConveyor(KEY_A, null);
			IConveyorBelt second = ConveyorHandler.getConveyor(KEY_A, null);

			assertNotNull(first);
			assertNotNull(second);
			assertNotSame(first, second);
			assertTrue(first instanceof TestConveyor);
		}

		@Test
		@DisplayName("getConveyor returns null for an unregistered key")
		void getConveyorReturnsNullForUnknownKey()
		{
			assertNull(ConveyorHandler.getConveyor(KEY_A, null));
		}

		@Test
		@DisplayName("getConveyor hands the tile entity to the factory")
		void getConveyorPassesTheTileThrough()
		{
			AtomicInteger calls = new AtomicInteger();
			ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> {
				calls.incrementAndGet();
				assertNull(t, "the inventory model passes a null tile");
				return new TestConveyor();
			});

			ConveyorHandler.getConveyor(KEY_A, null);
			assertEquals(1, calls.get());
		}
	}

	@Nested
	@DisplayName("substitutes")
	class Substitutes
	{
		@Test
		@DisplayName("the substitute set is created on first use")
		void substituteSetIsCreatedLazily()
		{
			assertTrue(ConveyorHandler.substituteRegistry.isEmpty());

			ConveyorHandler.registerSubstitute(KEY_A, KEY_B);

			assertEquals(1, ConveyorHandler.substituteRegistry.size());
			assertTrue(ConveyorHandler.substituteRegistry.get(KEY_A).contains(KEY_B));
		}

		@Test
		@DisplayName("several substitutes accumulate under the same key")
		void substitutesAccumulate()
		{
			ResourceLocation third = new ResourceLocation("immersiveengineering:conveyor_c");
			ConveyorHandler.registerSubstitute(KEY_A, KEY_B);
			ConveyorHandler.registerSubstitute(KEY_A, third);

			Set<ResourceLocation> subs = ConveyorHandler.substituteRegistry.get(KEY_A);
			assertEquals(2, subs.size());
			assertTrue(subs.contains(KEY_B));
			assertTrue(subs.contains(third));
		}

		@Test
		@DisplayName("registering the same substitute twice is a no-op")
		void duplicateSubstituteIsIgnored()
		{
			ConveyorHandler.registerSubstitute(KEY_A, KEY_B);
			ConveyorHandler.registerSubstitute(KEY_A, KEY_B);

			assertEquals(1, ConveyorHandler.substituteRegistry.get(KEY_A).size());
		}

		@Test
		@DisplayName("substitutes are one-directional")
		void substitutesAreOneDirectional()
		{
			ConveyorHandler.registerSubstitute(KEY_A, KEY_B);

			assertTrue(ConveyorHandler.substituteRegistry.containsKey(KEY_A));
			assertFalse(ConveyorHandler.substituteRegistry.containsKey(KEY_B));
		}
	}

	@Nested
	@DisplayName("magnet suppression")
	class MagnetSuppression
	{
		@Test
		@DisplayName("a suppressor with a reversal fills both sets")
		void suppressorWithReversalFillsBothSets()
		{
			ConveyorHandler.registerMagnetSupression((e, t) -> {
			}, (e, t) -> {
			});

			assertEquals(1, ConveyorHandler.magnetSupressionFunctions.size());
			assertEquals(1, ConveyorHandler.magnetSupressionReverse.size());
		}

		@Test
		@DisplayName("a suppressor without a reversal only fills the forward set")
		void suppressorWithoutReversal()
		{
			ConveyorHandler.registerMagnetSupression((e, t) -> {
			}, null);

			assertEquals(1, ConveyorHandler.magnetSupressionFunctions.size());
			assertTrue(ConveyorHandler.magnetSupressionReverse.isEmpty());
		}

		@Test
		@DisplayName("applying to a null entity runs nothing")
		void applyIgnoresNullEntity()
		{
			AtomicInteger calls = new AtomicInteger();
			ConveyorHandler.registerMagnetSupression((e, t) -> calls.incrementAndGet(), null);

			ConveyorHandler.applyMagnetSupression(null, null);

			assertEquals(0, calls.get());
		}

		@Test
		@DisplayName("reverting a null entity runs nothing")
		void revertIgnoresNullEntity()
		{
			AtomicInteger calls = new AtomicInteger();
			ConveyorHandler.registerMagnetSupression((e, t) -> {
			}, (e, t) -> calls.incrementAndGet());

			ConveyorHandler.revertMagnetSupression(null, null);

			assertEquals(0, calls.get());
		}
	}

	@Nested
	@DisplayName("IConveyorBelt defaults")
	class BeltDefaults
	{
		@Test
		@DisplayName("there are exactly three conveyor directions and HORIZONTAL comes first")
		void directionEnum()
		{
			assertEquals(3, ConveyorDirection.values().length);
			assertEquals(0, ConveyorDirection.HORIZONTAL.ordinal());
			assertEquals(ConveyorDirection.HORIZONTAL, ConveyorDirection.valueOf("HORIZONTAL"));
		}

		@Test
		@DisplayName("a flat conveyor only transports along its facing")
		void horizontalTransportDirection()
		{
			TestConveyor c = new TestConveyor();

			assertArrayEquals(new EnumFacing[]{EnumFacing.NORTH},
					c.sigTransportDirections(null, EnumFacing.NORTH));
		}

		@Test
		@DisplayName("an upward conveyor also transports up")
		void upwardTransportDirection()
		{
			TestConveyor c = new TestConveyor();
			c.direction = ConveyorDirection.UP;

			assertArrayEquals(new EnumFacing[]{EnumFacing.EAST, EnumFacing.UP},
					c.sigTransportDirections(null, EnumFacing.EAST));
		}

		@Test
		@DisplayName("a downward conveyor also transports down")
		void downwardTransportDirection()
		{
			TestConveyor c = new TestConveyor();
			c.direction = ConveyorDirection.DOWN;

			assertArrayEquals(new EnumFacing[]{EnumFacing.WEST, EnumFacing.DOWN},
					c.sigTransportDirections(null, EnumFacing.WEST));
		}

		@Test
		@DisplayName("a flat conveyor uses the flat selection box, a sloped one the tall box")
		void selectionBoxesDependOnDirection()
		{
			TestConveyor c = new TestConveyor();

			List<AxisAlignedBB> flat = c.getSelectionBoxes(null, EnumFacing.NORTH);
			assertEquals(1, flat.size());
			assertEquals(.125, flat.get(0).maxY, 1e-6);

			c.direction = ConveyorDirection.UP;
			List<AxisAlignedBB> tall = c.getSelectionBoxes(null, EnumFacing.NORTH);
			assertEquals(1, tall.size());
			assertEquals(1.125, tall.get(0).maxY, 1e-6);
		}

		@Test
		@DisplayName("the collision box stays flat even for a sloped conveyor")
		void collisionBoxIsAlwaysFlat()
		{
			TestConveyor c = new TestConveyor();
			c.direction = ConveyorDirection.UP;

			List<AxisAlignedBB> boxes = c.getColisionBoxes(null, EnumFacing.NORTH);
			assertEquals(1, boxes.size());
			assertEquals(.125, boxes.get(0).maxY, 1e-6);
		}

		@Test
		@DisplayName("the model cache key encodes facing, direction, activity, walls and colour")
		void modelCacheKey()
		{
			ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> new TestConveyor());
			TestConveyor c = new TestConveyor();
			c.direction = ConveyorDirection.UP;
			c.active = false;
			c.wall0 = true;
			c.wall1 = false;
			c.colour = 0xff0000;

			String key = c.getModelCacheKey(null, EnumFacing.SOUTH);

			assertEquals(KEY_A+"f"+EnumFacing.SOUTH.ordinal()+"d"+ConveyorDirection.UP.ordinal()
					+"a0w01w10c"+0xff0000, key);
		}

		@Test
		@DisplayName("cache keys differ when anything visible about the conveyor differs")
		void modelCacheKeysAreDistinct()
		{
			ConveyorHandler.registerConveyorHandler(KEY_A, TestConveyor.class, t -> new TestConveyor());
			TestConveyor a = new TestConveyor();
			TestConveyor b = new TestConveyor();
			b.colour = 0x00ff00;

			assertEquals(a.getModelCacheKey(null, EnumFacing.NORTH), a.getModelCacheKey(null, EnumFacing.NORTH));
			assertNotEquals(a.getModelCacheKey(null, EnumFacing.NORTH), b.getModelCacheKey(null, EnumFacing.NORTH));
			assertNotEquals(a.getModelCacheKey(null, EnumFacing.NORTH), a.getModelCacheKey(null, EnumFacing.SOUTH));
		}

		@Test
		@DisplayName("the coloured stripe texture defaults to IE's own")
		void colouredStripeTextureDefault()
		{
			assertEquals(ConveyorHandler.textureConveyorColour, new TestConveyor().getColouredStripesTexture());
		}

		@Test
		@DisplayName("player interaction is a no-op by default")
		void playerInteractionDefaultsToFalse()
		{
			assertFalse(new TestConveyor().playerInteraction(null, null, null, null, 0, 0, 0, null));
		}

		@Test
		@DisplayName("a conveyor does not tick by default")
		void tickingDefaultsToFalse()
		{
			assertFalse(new TestConveyor().isTicking(null));
		}
	}
}
