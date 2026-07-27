/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api;

import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.IEProperties.PropertyBoolInverted;
import com.google.common.base.Optional;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for {@link IEProperties}.
 * <p>
 * These property objects name the keys that end up in blockstate JSON and in the model cache, so
 * renaming one silently breaks every model that references it. The array-valued properties are
 * indexed by {@link EnumFacing#ordinal()} throughout the codebase, which is asserted here too.
 */
class IEPropertiesTest
{
	@Test
	@DisplayName("FACING_ALL is named \"facing\" and accepts all six directions")
	void facingAllAcceptsEveryDirection()
	{
		assertEquals("facing", IEProperties.FACING_ALL.getName());
		Collection<EnumFacing> allowed = IEProperties.FACING_ALL.getAllowedValues();
		assertEquals(6, allowed.size());
		assertEquals(EnumSet.allOf(EnumFacing.class), EnumSet.copyOf(allowed));
	}

	@Test
	@DisplayName("FACING_HORIZONTAL is named \"facing\" and excludes UP and DOWN")
	void facingHorizontalExcludesTheVerticals()
	{
		assertEquals("facing", IEProperties.FACING_HORIZONTAL.getName());
		Collection<EnumFacing> allowed = IEProperties.FACING_HORIZONTAL.getAllowedValues();
		assertEquals(4, allowed.size());
		assertEquals(EnumSet.of(EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST),
				EnumSet.copyOf(allowed));
		assertFalse(allowed.contains(EnumFacing.UP));
		assertFalse(allowed.contains(EnumFacing.DOWN));
	}

	@Test
	@DisplayName("FACING_HORIZONTAL is a strict subset of FACING_ALL but a distinct object")
	void facingHorizontalIsASubsetOfFacingAll()
	{
		assertNotSame(IEProperties.FACING_ALL, IEProperties.FACING_HORIZONTAL);
		assertTrue(IEProperties.FACING_ALL.getAllowedValues()
				.containsAll(IEProperties.FACING_HORIZONTAL.getAllowedValues()));
		assertFalse(IEProperties.FACING_HORIZONTAL.getAllowedValues()
				.containsAll(IEProperties.FACING_ALL.getAllowedValues()));
	}

	@Test
	@DisplayName("the marker properties keep their sort-order prefixes")
	void markerPropertiesKeepTheirPrefixes()
	{
		// the leading _0/_1 exist purely to force the override order when models are resolved
		assertEquals("_0multiblockslave", IEProperties.MULTIBLOCKSLAVE.getName());
		assertEquals("_1dynamicrender", IEProperties.DYNAMICRENDER.getName());
		assertTrue(IEProperties.MULTIBLOCKSLAVE.getName().compareTo(IEProperties.DYNAMICRENDER.getName()) < 0,
				"multiblockslave must sort before dynamicrender");
	}

	@Test
	@DisplayName("PropertyBoolInverted lists false before true")
	void boolInvertedIsActuallyInverted()
	{
		// the whole point of this class is the reversed order compared to vanilla PropertyBool
		assertEquals(Arrays.asList(false, true),
				new ArrayList<>(IEProperties.MULTIBLOCKSLAVE.getAllowedValues()));
		assertEquals(2, IEProperties.MULTIBLOCKSLAVE.getAllowedValues().size());
	}

	@Test
	@DisplayName("PropertyBoolInverted parses and names its values")
	void boolInvertedParsesAndNames()
	{
		PropertyBoolInverted prop = PropertyBoolInverted.create("unit_test_bool");
		assertEquals("unit_test_bool", prop.getName());
		assertEquals(Optional.of(Boolean.TRUE), prop.parseValue("true"));
		assertEquals(Optional.of(Boolean.TRUE), prop.parseValue("TRUE"));
		assertEquals(Optional.of(Boolean.FALSE), prop.parseValue("false"));
		assertEquals(Optional.of(Boolean.FALSE), prop.parseValue("not a boolean at all"));
		assertEquals("true", prop.getName(Boolean.TRUE));
		assertEquals("false", prop.getName(Boolean.FALSE));
		assertEquals(Boolean.class, prop.getValueClass());
	}

	@Test
	@DisplayName("CONNECTIONS is an unlisted Set property named \"conns\"")
	void connectionsProperty()
	{
		assertEquals("conns", IEProperties.CONNECTIONS.getName());
		assertEquals(Set.class, IEProperties.CONNECTIONS.getType());
		assertFalse(IEProperties.CONNECTIONS.isValid(null), "a null connection set must be rejected");
		assertTrue(IEProperties.CONNECTIONS.isValid(new HashSet<>()));
	}

	@Test
	@DisplayName("SIDECONFIG has one property per side, indexed by EnumFacing.ordinal()")
	void sideConfigIsIndexedByFacing()
	{
		assertEquals(EnumFacing.VALUES.length, IEProperties.SIDECONFIG.length);
		for(EnumFacing facing : EnumFacing.VALUES)
			assertEquals("sideconfig_"+facing.getName(), IEProperties.SIDECONFIG[facing.ordinal()].getName(),
					"SIDECONFIG is not indexed by EnumFacing.ordinal() at "+facing);
	}

	@Test
	@DisplayName("SIDECONNECTION has one property per side, indexed by EnumFacing.ordinal()")
	void sideConnectionIsIndexedByFacing()
	{
		assertEquals(EnumFacing.VALUES.length, IEProperties.SIDECONNECTION.length);
		for(EnumFacing facing : EnumFacing.VALUES)
			assertEquals("sideconnection_"+facing.getName(), IEProperties.SIDECONNECTION[facing.ordinal()].getName(),
					"SIDECONNECTION is not indexed by EnumFacing.ordinal() at "+facing);
	}

	@Test
	@DisplayName("the ProperySideConfig wrapper accepts any SideConfig and stringifies it")
	void sideConfigPropertyBehaviour()
	{
		IEProperties.ProperySideConfig prop = IEProperties.SIDECONFIG[0];
		assertEquals(SideConfig.class, prop.getType());
		for(SideConfig config : SideConfig.values())
		{
			assertTrue(prop.isValid(config));
			assertEquals(config.toString(), prop.valueToString(config));
		}
	}

	@Test
	@DisplayName("BOOLEANS is a numbered array of three generic flags")
	void booleansArray()
	{
		assertEquals(3, IEProperties.BOOLEANS.length);
		for(int i = 0; i < IEProperties.BOOLEANS.length; i++)
			assertEquals("boolean"+i, IEProperties.BOOLEANS[i].getName());
	}

	@Test
	@DisplayName("INT_4 and INT_16 cover exactly their advertised ranges")
	void integerPropertiesCoverTheirRanges()
	{
		assertEquals("int_4", IEProperties.INT_4.getName());
		assertEquals(4, IEProperties.INT_4.getAllowedValues().size());
		for(int i = 0; i < 4; i++)
			assertTrue(IEProperties.INT_4.getAllowedValues().contains(i), "INT_4 is missing "+i);
		assertFalse(IEProperties.INT_4.getAllowedValues().contains(4));

		assertEquals("int_16", IEProperties.INT_16.getName());
		assertEquals(16, IEProperties.INT_16.getAllowedValues().size());
		for(int i = 0; i < 16; i++)
			assertTrue(IEProperties.INT_16.getAllowedValues().contains(i), "INT_16 is missing "+i);
		assertFalse(IEProperties.INT_16.getAllowedValues().contains(16));
	}

	@Test
	@DisplayName("the unlisted passthrough properties keep their names")
	void unlistedPassthroughProperties()
	{
		assertEquals("obj_texture_remap", IEProperties.OBJ_TEXTURE_REMAP.getName());
		assertEquals("tileentity_passthrough", IEProperties.TILEENTITY_PASSTHROUGH.getName());
	}

	@Test
	@DisplayName("no two IEProperties share a name")
	void propertyNamesDoNotCollide()
	{
		List<String> names = new ArrayList<>();
		names.add(IEProperties.MULTIBLOCKSLAVE.getName());
		names.add(IEProperties.DYNAMICRENDER.getName());
		names.add(IEProperties.CONNECTIONS.getName());
		names.add(IEProperties.INT_4.getName());
		names.add(IEProperties.INT_16.getName());
		names.add(IEProperties.OBJ_TEXTURE_REMAP.getName());
		names.add(IEProperties.TILEENTITY_PASSTHROUGH.getName());
		for(IEProperties.ProperySideConfig p : IEProperties.SIDECONFIG)
			names.add(p.getName());
		for(PropertyBoolInverted p : IEProperties.SIDECONNECTION)
			names.add(p.getName());
		for(PropertyBoolInverted p : IEProperties.BOOLEANS)
			names.add(p.getName());

		Set<String> seen = new HashSet<>();
		for(String name : names)
			assertTrue(seen.add(name), "duplicate property name: "+name);
	}
}
