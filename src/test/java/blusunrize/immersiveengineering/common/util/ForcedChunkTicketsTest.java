/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ForcedChunkTickets#assign} decides which Forge ticket holds which chunk. It exists because
 * one ticket per dimension silently lost every chunk past Forge's per-ticket cap: forceChunk evicts
 * the oldest chunk rather than refusing the new one.
 */
class ForcedChunkTicketsTest
{
	private static Set<ChunkPos> chunks(int count)
	{
		Set<ChunkPos> set = new LinkedHashSet<>();
		for(int i = 0; i < count; i++)
			set.add(new ChunkPos(i, -i));
		return set;
	}

	private static Set<ChunkPos> union(List<Set<ChunkPos>> tickets)
	{
		Set<ChunkPos> all = new HashSet<>();
		for(Set<ChunkPos> t : tickets)
			all.addAll(t);
		return all;
	}

	private static int total(List<Set<ChunkPos>> tickets)
	{
		int n = 0;
		for(Set<ChunkPos> t : tickets)
			n += t.size();
		return n;
	}

	@Test
	@DisplayName("a budget at the cap fits in one ticket")
	void atCapOneTicket()
	{
		List<Set<ChunkPos>> out = ForcedChunkTickets.assign(new ArrayList<>(), chunks(25), 25);
		assertEquals(1, out.size());
		assertEquals(25, out.get(0).size());
	}

	@Test
	@DisplayName("one chunk past the cap opens a second ticket instead of evicting")
	void pastCapSecondTicket()
	{
		Set<ChunkPos> wanted = chunks(26);
		List<Set<ChunkPos>> out = ForcedChunkTickets.assign(new ArrayList<>(), wanted, 25);
		assertEquals(2, out.size());
		assertEquals(wanted, union(out));
		assertEquals(26, total(out), "no chunk is held twice");
		for(Set<ChunkPos> t : out)
			assertTrue(t.size() <= 25);
	}

	@Test
	@DisplayName("a large budget is spread over as many tickets as the cap requires")
	void manyTickets()
	{
		Set<ChunkPos> wanted = chunks(100);
		List<Set<ChunkPos>> out = ForcedChunkTickets.assign(new ArrayList<>(), wanted, 25);
		assertEquals(4, out.size());
		assertEquals(wanted, union(out));
	}

	@Test
	@DisplayName("a depth of zero means unlimited, as in Forge")
	void unlimitedDepth()
	{
		List<Set<ChunkPos>> out = ForcedChunkTickets.assign(new ArrayList<>(), chunks(300), 0);
		assertEquals(1, out.size());
		assertEquals(300, out.get(0).size());
	}

	@Test
	@DisplayName("chunks still wanted stay in the ticket that already holds them")
	void noChurn()
	{
		List<Set<ChunkPos>> first = ForcedChunkTickets.assign(new ArrayList<>(), chunks(30), 25);
		Set<ChunkPos> wanted = new HashSet<>(chunks(30));
		wanted.add(new ChunkPos(99, 99));
		List<Set<ChunkPos>> second = ForcedChunkTickets.assign(first, wanted, 25);
		for(int i = 0; i < first.size(); i++)
			assertTrue(second.get(i).containsAll(first.get(i)), "ticket "+i+" kept its chunks");
		assertEquals(wanted, union(second));
	}

	@Test
	@DisplayName("a removed chunk frees room that the next new chunk fills")
	void reuseRoom()
	{
		List<Set<ChunkPos>> first = ForcedChunkTickets.assign(new ArrayList<>(), chunks(25), 25);
		Set<ChunkPos> wanted = new HashSet<>(chunks(25));
		wanted.remove(new ChunkPos(3, -3));
		wanted.add(new ChunkPos(50, 50));
		List<Set<ChunkPos>> second = ForcedChunkTickets.assign(first, wanted, 25);
		assertEquals(1, second.size(), "no second ticket for a swap");
		assertEquals(wanted, second.get(0));
	}

	@Test
	@DisplayName("indices line up with the current tickets, and emptied tickets stay as empty entries")
	void emptiedTicketsKeepTheirIndex()
	{
		List<Set<ChunkPos>> first = ForcedChunkTickets.assign(new ArrayList<>(), chunks(50), 25);
		Set<ChunkPos> wanted = new HashSet<>(first.get(1));
		List<Set<ChunkPos>> second = ForcedChunkTickets.assign(first, wanted, 25);
		assertEquals(2, second.size());
		assertTrue(second.get(0).isEmpty());
		assertEquals(wanted, second.get(1));
	}

	@Test
	@DisplayName("a ticket over a lowered cap sheds the excess into other tickets")
	void loweredCap()
	{
		List<Set<ChunkPos>> current = new ArrayList<>();
		current.add(new LinkedHashSet<>(chunks(30)));
		List<Set<ChunkPos>> out = ForcedChunkTickets.assign(current, chunks(30), 10);
		assertEquals(3, out.size());
		assertEquals(chunks(30), union(out));
		assertEquals(30, total(out));
		for(Set<ChunkPos> t : out)
			assertTrue(t.size() <= 10);
	}

	@Test
	@DisplayName("nothing wanted empties every ticket")
	void nothingWanted()
	{
		List<Set<ChunkPos>> first = ForcedChunkTickets.assign(new ArrayList<>(), chunks(40), 25);
		List<Set<ChunkPos>> out = ForcedChunkTickets.assign(first, Collections.emptySet(), 25);
		assertEquals(first.size(), out.size());
		assertEquals(0, total(out));
	}

	@Test
	@DisplayName("the same wanted set lands the same way whatever order it arrives in")
	void deterministic()
	{
		List<ChunkPos> list = new ArrayList<>(chunks(60));
		List<Set<ChunkPos>> a = ForcedChunkTickets.assign(new ArrayList<>(), new LinkedHashSet<>(list), 25);
		Collections.reverse(list);
		List<Set<ChunkPos>> b = ForcedChunkTickets.assign(new ArrayList<>(), new LinkedHashSet<>(list), 25);
		assertEquals(a, b);
	}
}
