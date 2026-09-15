/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GhostConnectionAuditTest
{
	private static final BlockPos A = new BlockPos(0, 64, 0), B = new BlockPos(10, 64, 0), C = new BlockPos(20, 64, 0);

	/**
	 * Both directions, as the net handler stores them. WireType is never touched by the audit, so null is fine.
	 */
	private static List<Connection> wire(BlockPos a, BlockPos b)
	{
		return Arrays.asList(new Connection(a, b, null, 10), new Connection(b, a, null, 10));
	}

	private static GhostConnectionAudit.Result run(List<Connection> cons, Set<BlockPos> loaded, Set<BlockPos> present,
												   Set<BlockPos> proxied)
	{
		return GhostConnectionAudit.audit(cons, loaded::contains, present::contains, proxied::contains);
	}

	@Test
	@DisplayName("a wire between two present, loaded connectors is fine, and counted once for both directions")
	void healthy()
	{
		GhostConnectionAudit.Result r = run(wire(A, B), set(A, B), set(A, B), set());
		assertEquals(1, r.inspected);
		assertTrue(r.missing.isEmpty());
		assertTrue(r.unproxied.isEmpty());
	}

	@Test
	@DisplayName("a loaded endpoint with no connectable block is missing")
	void missing()
	{
		GhostConnectionAudit.Result r = run(wire(A, B), set(A, B), set(A), set());
		assertEquals(1, r.missing.size());
		assertEquals(set(B), r.missingNodes);
	}

	@Test
	@DisplayName("an unloaded endpoint with a proxy is trusted")
	void proxiedTrusted()
	{
		GhostConnectionAudit.Result r = run(wire(A, B), set(A), set(A), set(B));
		assertTrue(r.missing.isEmpty());
		assertTrue(r.unproxied.isEmpty());
	}

	@Test
	@DisplayName("an unloaded endpoint without a proxy is unproxied, not missing")
	void unproxied()
	{
		GhostConnectionAudit.Result r = run(wire(A, B), set(A), set(A), set());
		assertTrue(r.missing.isEmpty());
		assertEquals(1, r.unproxied.size());
		assertEquals(set(B), r.unproxiedNodes);
	}

	@Test
	@DisplayName("certain beats probable: a wire with one missing and one unproxied end is reported as missing only")
	void missingWins()
	{
		GhostConnectionAudit.Result r = run(wire(A, B), set(A), set(), set());
		assertEquals(1, r.missing.size());
		assertTrue(r.unproxied.isEmpty());
	}

	@Test
	@DisplayName("nothing is ever asked about a position's block unless its chunk is loaded")
	void neverInspectsUnloaded()
	{
		Set<BlockPos> asked = new HashSet<>();
		GhostConnectionAudit.audit(wire(A, B), p -> p.equals(A), p -> {
			asked.add(p);
			return true;
		}, p -> true);
		assertEquals(set(A), asked);
	}

	@Test
	@DisplayName("a chain reports each broken wire separately")
	void chain()
	{
		List<Connection> cons = new ArrayList<>(wire(A, B));
		cons.addAll(wire(B, C));
		GhostConnectionAudit.Result r = run(cons, set(A, B, C), set(A, C), set());
		assertEquals(2, r.inspected);
		assertEquals(2, r.missing.size());
		assertEquals(set(B), r.missingNodes);
	}

	private static Set<BlockPos> set(BlockPos... ps)
	{
		return new HashSet<>(Arrays.asList(ps));
	}
}
