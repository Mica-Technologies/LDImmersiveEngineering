/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.common.model.IModelState;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Puts {@link ConduitJunctionModel} behind a model location the wire connector registry can name.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitJunctionLoader implements ICustomModelLoader
{
	/**
	 * What {@code ClientProxy} registers as the base model behind the connector key
	 * {@code conduit_junction_box}.
	 * <p>
	 * <strong>The {@code block/} is written out here and must not be, in a blockstate.</strong>
	 * {@code ModelLoaderRegistry.getActualLocation} prepends {@code models/} to everything; the extra
	 * {@code block/} is added on top of that only for a model named inside a vanilla {@code variants}
	 * or {@code multipart} blockstate. This location is named through {@code WireApi}, which passes
	 * it to {@code ModelLoaderRegistry} untouched, so it carries its own -- the same shape of
	 * constant {@link FeedthroughLoader} has, and for the same reason.
	 * <p>
	 * The blockstate names {@code smartmodel/conn_conduit_junction_box} instead, which is
	 * {@link ConnLoader}'s, and that is what wraps this one so a wire strung to the box is drawn.
	 */
	public static final String RESOURCE_LOCATION = "models/block/smartmodel/conduit_junction_box_parts";

	/** What {@code WireApi.registerConnectorForRender} is handed for the location above. */
	public static final ResourceLocation LOCATION = new ResourceLocation(
			ImmersiveEngineering.MODID, "block/smartmodel/conduit_junction_box_parts");

	/** The connector key the blockstate's {@code conn_} reference resolves through. */
	public static final String CONNECTOR_KEY = "conduit_junction_box";

	@Override
	public void onResourceManagerReload(@Nonnull IResourceManager resourceManager)
	{
		//The assembled quad lists hold sprites from an atlas that has just been re-stitched.
		ConduitJunctionModel.modelCache.clear();
	}

	@Override
	public boolean accepts(@Nonnull ResourceLocation modelLocation)
	{
		return modelLocation.getPath().equals(RESOURCE_LOCATION);
	}

	@Nonnull
	@Override
	public IModel loadModel(@Nonnull ResourceLocation modelLocation)
	{
		return new RawJunctionModel();
	}

	/**
	 * @return the model a box mounted that way grows toward that face, or null on the face it is
	 * already flush against -- where there is no gap to bridge and, deliberately, no file
	 */
	private static String stubName(EnumFacing mount, EnumFacing face)
	{
		return face==mount?null: ConduitGeometry.junctionRunModelName(mount, face);
	}

	private static class RawJunctionModel implements IModel
	{
		@Nonnull
		@Override
		public Collection<ResourceLocation> getDependencies()
		{
			//Six housings, thirty-six plates and thirty stubs, declared. Nothing else references
			//these files any more -- the blockstate names one smart model and no more -- so without
			//this they would never be loaded and their textures would never reach the block atlas.
			List<ResourceLocation> out = new ArrayList<>();
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				out.add(ConduitParts.location(ConduitGeometry.junctionBoxModelName(mount)));
				for(EnumFacing face : EnumFacing.VALUES)
				{
					out.add(ConduitParts.location(ConduitGeometry.junctionPatchModelName(mount, face)));
					String stub = stubName(mount, face);
					if(stub!=null)
						out.add(ConduitParts.location(stub));
				}
			}
			return out;
		}

		@Nonnull
		@Override
		public Collection<ResourceLocation> getTextures()
		{
			//The parts' own textures come with the parts, through the dependencies above.
			return Collections.emptyList();
		}

		@Nonnull
		@Override
		public IBakedModel bake(@Nonnull IModelState state, @Nonnull VertexFormat format,
								@Nonnull Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
		{
			int faces = EnumFacing.VALUES.length;
			IBakedModel[] housings = new IBakedModel[faces];
			IBakedModel[][] plates = new IBakedModel[faces][faces];
			IBakedModel[][] stubs = new IBakedModel[faces][faces];
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				housings[mount.ordinal()] = ConduitParts.bake(
						ConduitGeometry.junctionBoxModelName(mount), state, format, bakedTextureGetter);
				for(EnumFacing face : EnumFacing.VALUES)
				{
					plates[mount.ordinal()][face.ordinal()] = ConduitParts.bake(
							ConduitGeometry.junctionPatchModelName(mount, face), state, format,
							bakedTextureGetter);
					String stub = stubName(mount, face);
					stubs[mount.ordinal()][face.ordinal()] = stub==null?null
							:ConduitParts.bake(stub, state, format, bakedTextureGetter);
				}
			}
			//A rebake means new sprites behind every one of those parts.
			ConduitJunctionModel.modelCache.clear();
			return new ConduitJunctionModel(housings, plates, stubs);
		}
	}
}
