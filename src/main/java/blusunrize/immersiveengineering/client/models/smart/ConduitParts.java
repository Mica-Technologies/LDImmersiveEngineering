/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.model.IModelState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The pieces a conduit is assembled from, and the assembling.
 * <p>
 * A conduit block is one of seventy-eight little axis-aligned boxes for a run, or a housing plus up
 * to six plates and six stubs for a junction box -- all of them ordinary generated JSON models (see
 * {@code docs/tools/make_conduit_assets.py}). What changed is only <em>who</em> picks them: a
 * multipart blockstate used to, on twelve boolean block properties, which cost the block 73,728
 * states and a model reference for each. {@link ConduitRunModel} and {@link ConduitJunctionModel}
 * pick them from the tile entity instead, and this is the shared machinery for baking a part once
 * and gluing a set of them into one quad list.
 * <p>
 * The parts carry no {@code cullface}, so everything they have comes back under {@code side == null}
 * -- but the composition here keeps a list per side anyway rather than assuming that. A generated
 * model that grew a cullface later would silently lose its quads under the assumption, and the cost
 * of not making it is one array of seven references.
 *
 * @author LDImmersiveEngineering -- conduits
 */
class ConduitParts
{
	private ConduitParts()
	{
	}

	/** Where the generated conduit models live, as a blockstate would name them. */
	static final String FOLDER = "block/conduit/";

	/** Six faces and the sideless bucket vanilla uses for quads it will never cull. */
	private static final int SIDES = EnumFacing.VALUES.length+1;

	static ResourceLocation location(String name)
	{
		return new ResourceLocation(ImmersiveEngineering.MODID, FOLDER+name);
	}

	/**
	 * Bakes one generated part.
	 * <p>
	 * The missing model rather than a crash if a part cannot be loaded, for the reason
	 * {@link ConduitDisguiseLoader} gives: a purple elbow is a bad afternoon and a client that will
	 * not start is a worse one. It also cannot happen quietly -- {@code ConduitAssetsTest} names
	 * every one of these files and fails the build if one is missing.
	 */
	static IBakedModel bake(String name, IModelState state, VertexFormat format,
							Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
	{
		IModel model;
		try
		{
			model = ModelLoaderRegistry.getModel(location(name));
		} catch(Exception e)
		{
			e.printStackTrace();
			model = ModelLoaderRegistry.getMissingModel();
		}
		return model.bake(state, format, bakedTextureGetter);
	}

	/**
	 * Glues a set of baked parts into one quad list per side.
	 * <p>
	 * Done once per distinct shape and then cached by the caller, which is the whole point of doing
	 * it here rather than concatenating lists inside {@code getQuads}: a corridor of conduit is
	 * hundreds of blocks with a handful of shapes between them.
	 */
	static List<BakedQuad>[] compose(List<IBakedModel> parts)
	{
		@SuppressWarnings("unchecked")
		List<BakedQuad>[] lists = new List[SIDES];
		for(int i = 0; i < SIDES; i++)
		{
			List<BakedQuad> quads = new ArrayList<>();
			EnumFacing side = i < EnumFacing.VALUES.length?EnumFacing.VALUES[i]: null;
			for(IBakedModel part : parts)
				//A null state and a zero seed: these are plain generated cuboids with no variants and
				//no properties of their own, so neither says anything to them.
				quads.addAll(part.getQuads(null, side, 0));
			lists[i] = ImmutableList.copyOf(quads);
		}
		return lists;
	}

	static List<BakedQuad> forSide(List<BakedQuad>[] lists, @Nullable EnumFacing side)
	{
		return lists[side==null?EnumFacing.VALUES.length: side.ordinal()];
	}
}
