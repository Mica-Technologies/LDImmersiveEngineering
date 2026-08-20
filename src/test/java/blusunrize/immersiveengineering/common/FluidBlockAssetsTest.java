/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every fluid that gets a world block must also have a variant in {@code fluid_block.json}.
 * <p>
 * Forge resolves a fluid block's model by looking its fluid's name up as a variant key, and a
 * missing key is not a fallback -- it is a model-loading exception at boot and the missing-model
 * block wherever the fluid stands in the world. Steam shipped exactly that way: seventeen fluids
 * were listed and the eighteenth was not, and the one line it cost in the log was easy to lose
 * among the errors of the day. This walks the registrations out of {@link IEContent}'s source the
 * way the crawler tests walk their generator, so the nineteenth fluid cannot repeat it.
 */
class FluidBlockAssetsTest
{
	private static final Path IE_CONTENT = Paths.get("src/main/java/blusunrize/immersiveengineering/common/IEContent.java");
	private static final Path FLUID_BLOCK_JSON = Paths.get("src/main/resources/assets/immersiveengineering/blockstates/fluid_block.json");

	@Test
	@DisplayName("every fluid with a block has a fluid_block variant")
	void everyFluidBlockHasAVariant() throws IOException
	{
		//Line endings normalised: core.autocrlf is on, so a fresh checkout hands these files CRLF,
		//and every pattern below is written with the bare newline the repository stores.
		String source = new String(Files.readAllBytes(IE_CONTENT), "UTF-8").replace("\r\n", "\n");

		//fluidX = setupFluid(new Fluid("name", ...   -- the variable each fluid lives in.
		Map<String, String> fluidNamesByVariable = new HashMap<>();
		Matcher fluids = Pattern.compile("(\\w+)\\s*=\\s*setupFluid\\(new Fluid\\(\"([^\"]+)\"").matcher(source);
		while(fluids.find())
			fluidNamesByVariable.put(fluids.group(1), fluids.group(2));

		//new BlockIEFluid("blockName", fluidX, ...   -- the fluids that actually stand in the world.
		Set<String> fluidsWithBlocks = new HashSet<>();
		Matcher blocks = Pattern.compile("new BlockIEFluid\\(\"[^\"]+\",\\s*(\\w+)").matcher(source);
		while(blocks.find())
		{
			String variable = blocks.group(1);
			assertTrue(fluidNamesByVariable.containsKey(variable),
					"a BlockIEFluid is built from '"+variable+"', which no setupFluid call in IEContent fills");
			fluidsWithBlocks.add(fluidNamesByVariable.get(variable));
		}
		assertTrue(fluidsWithBlocks.size() >= 15,
				"only "+fluidsWithBlocks.size()+" fluid blocks parsed out of IEContent -- the registration pattern moved and this test is reading nothing");

		//Line endings normalised: core.autocrlf is on, so a fresh checkout hands these files CRLF,
		//and every pattern below is written with the bare newline the repository stores.
		String json = new String(Files.readAllBytes(FLUID_BLOCK_JSON), "UTF-8").replace("\r\n", "\n");
		for(String fluidName : fluidsWithBlocks)
			assertTrue(json.contains("\""+fluidName+"\""),
					"fluid '"+fluidName+"' has a world block but no variant in fluid_block.json -- "
							+"it will fail model loading at boot and render as the missing model");
	}
}
