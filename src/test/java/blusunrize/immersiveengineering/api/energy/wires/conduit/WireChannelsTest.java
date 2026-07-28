/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import blusunrize.immersiveengineering.api.energy.wires.TestWireType;
import blusunrize.immersiveengineering.api.energy.wires.WireType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seam between the wire registry and the world-free channel model.
 * <p>
 * Small surface, but it is the only place the two meet, and the reason it exists is to stay that
 * way: a registry lookup that leaked into the transfer loop would cost sixteen of them per
 * connection per tick.
 */
class WireChannelsTest
{
	private WireType lv;
	private WireType hv;
	private WireType rope;

	@BeforeEach
	void setUp()
	{
		TestWireType.resetRegistries();
		TestWireType.installConfigArrays();
		lv = new TestWireType("CH_LV", .05, 256, 16, WireType.LV_CATEGORY, true, 0xb36c3f);
		hv = new TestWireType("CH_HV", .2, 4096, 32, WireType.HV_CATEGORY, true, 0x6e6e6e);
		rope = new TestWireType("CH_ROPE", 0, 0, 16, WireType.STRUCTURE_CATEGORY, false, 0x9c8d6b);
	}

	@Test
	@DisplayName("a wire's spec carries its name, rate and loss")
	void specTakesTheWiresNumbers()
	{
		ChannelSpec spec = WireChannels.specOf(lv);
		assertNotNull(spec);
		assertEquals("CH_LV", spec.getName());
		assertEquals(256, spec.getTransferRate());
		assertEquals(.05, spec.getLossRatio());
	}

	@Test
	@DisplayName("each tier keeps its own numbers")
	void tiersAreDistinct()
	{
		//Decision 6: the tier belongs to the channel, not to the conduit, so an LV and an HV
		//channel can share a run and neither is flattened to the other.
		assertEquals(256, WireChannels.specOf(lv).getTransferRate());
		assertEquals(4096, WireChannels.specOf(hv).getTransferRate());
	}

	@Test
	@DisplayName("structural cable cannot be patched to a channel")
	void ropeIsRefused()
	{
		//A channel patched to rope would read as a live circuit and move nothing. Refusing it here
		//is cheaper than explaining it later.
		assertNull(WireChannels.specOf(rope));
	}

	@Test
	@DisplayName("nothing resolves to nothing")
	void nullsAreRefused()
	{
		assertNull(WireChannels.specOf(null));
		assertNull(WireChannels.resolve(null));
		assertNull(WireChannels.resolve(""));
		assertNull(WireChannels.resolve("A_WIRE_FROM_A_MOD_THAT_LEFT"));
	}

	@Test
	@DisplayName("resolving by name matches resolving by type")
	void resolveMatchesSpecOf()
	{
		assertEquals(WireChannels.specOf(lv), WireChannels.resolve("CH_LV"));
		assertEquals(WireChannels.specOf(hv), WireChannels.resolve("CH_HV"));
	}

	@Test
	@DisplayName("a refresh through the real resolver updates a stale snapshot")
	void refreshThroughTheRealResolver()
	{
		//The whole point of the seam, end to end: a set built with out-of-date numbers picks up
		//the registry's current ones without the transfer path ever touching the registry.
		ChannelSet set = new ChannelSet();
		set.patch(WireChannel.BLUE, new ChannelSpec("CH_LV", 1, 0.99));
		set.patch(WireChannel.RED, new ChannelSpec("A_WIRE_FROM_A_MOD_THAT_LEFT", 500, 0.1));

		assertEquals(1, set.refresh(WireChannels::resolve));
		assertEquals(256, set.getSpec(WireChannel.BLUE).getTransferRate());
		assertEquals(.05, set.getSpec(WireChannel.BLUE).getLossRatio());
		assertFalse(set.isPatched(WireChannel.RED));
	}
}
