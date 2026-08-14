/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models;

import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A wavefront OBJ, read once, kept as one array of vertices per named group, drawn a group at
 * a time under whatever transform the caller has set up.
 * <p>
 * <strong>Written rather than borrowed, because Forge's OBJ support is for blocks.</strong>
 * {@code OBJLoader} bakes a model into quads whose UVs are baked against the block atlas and
 * hands it to a block renderer; an entity is drawn with its own texture bound, from its own
 * matrix, with parts moving relative to one another. Going through the block pipeline for that
 * would mean stitching an entity sheet into the block atlas and unpicking the baked quads
 * again. Reading the file is forty lines and leaves the animation in the caller's hands, which
 * is the whole point of using an OBJ for a machine that moves.
 * <p>
 * <strong>Groups are the animation.</strong> Everything the renderer wants to move separately
 * is its own {@code o} in the file, authored about its own pivot, and the caller does the
 * {@code push, translate, rotate, draw, pop} for each. That is the same shape as a
 * {@link net.minecraft.client.model.ModelRenderer} hierarchy and it is what a hand-built model
 * was being used for in the first place.
 * <p>
 * <strong>Nothing is parsed per frame.</strong> The file is read on first use -- lazily, since
 * an entity renderer is constructed while the resource manager may still be starting up -- and
 * what is kept is a flat float array per group, in the order a {@code BufferBuilder} wants to
 * eat it. A failed load leaves an empty model and one line in the log rather than an exception
 * every frame from inside the render loop, which is a client that cannot be played rather than
 * a machine that is invisible.
 * <p>
 * Read once and not re-read on a resource reload, deliberately: the geometry is generated at
 * build time and shipped in the jar, so the only thing F3+T could pick up is an edit made while
 * the game was running. The texture, which a resource pack really might replace, is bound by the
 * renderer from its own {@code ResourceLocation} and reloads as any entity texture does.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
@SideOnly(Side.CLIENT)
public class EntityOBJModel
{
	/** Position, texture, normal: eight floats a vertex, four vertices a quad. */
	private static final int STRIDE = 8;

	private final ResourceLocation location;
	private final Map<String, float[]> groups = new HashMap<>();
	private boolean loaded;

	public EntityOBJModel(ResourceLocation location)
	{
		this.location = location;
	}

	/**
	 * Draw one group, exactly as it was authored.
	 */
	public void render(String group)
	{
		render(group, 0, 0);
	}

	/**
	 * Draw one group with every texture coordinate shifted.
	 * <p>
	 * Which is how the tracks move: the belt's geometry is fixed and its tread scrolls along
	 * it. The offsets are in texture units -- pixels over the sheet's size -- and it is the
	 * caller's job to keep them inside whatever region the group is painted from, because a
	 * UV that leaves its region does not fail, it quietly samples the part next door.
	 */
	public void render(String group, float uOffset, float vOffset)
	{
		float[] data = get(group);
		if(data==null||data.length==0)
			return;
		BufferBuilder buffer = Tessellator.getInstance().getBuffer();
		//The format vanilla's own ModelRenderer uses, so the fixed-function pipeline lights
		//these quads exactly as it lights every mob in the game.
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
		for(int i = 0; i < data.length; i += STRIDE)
			buffer.pos(data[i], data[i+1], data[i+2])
					.tex(data[i+3]+uOffset, data[i+4]+vOffset)
					.normal(data[i+5], data[i+6], data[i+7])
					.endVertex();
		Tessellator.getInstance().draw();
	}

	/**
	 * @return the group's vertex data, loading the file if this is the first call
	 */
	private float[] get(String group)
	{
		if(!loaded)
			load();
		return groups.get(group);
	}

	/**
	 * @return whether a group of that name exists -- for a test, and for a renderer choosing
	 * between the attachments
	 */
	public boolean has(String group)
	{
		if(!loaded)
			load();
		return groups.containsKey(group);
	}

	private void load()
	{
		//Set first, so a file that cannot be read is not re-read once a frame forever.
		loaded = true;
		List<float[]> positions = new ArrayList<>();
		List<float[]> uvs = new ArrayList<>();
		Map<String, List<Float>> built = new HashMap<>();
		List<Float> current = null;
		try(BufferedReader reader = new BufferedReader(new InputStreamReader(
				Minecraft.getMinecraft().getResourceManager().getResource(location).getInputStream(),
				StandardCharsets.UTF_8)))
		{
			String line;
			while((line = reader.readLine())!=null)
			{
				if(line.isEmpty()||line.charAt(0)=='#')
					continue;
				String[] parts = line.trim().split("\\s+");
				switch(parts[0])
				{
					case "v":
						positions.add(new float[]{Float.parseFloat(parts[1]),
								Float.parseFloat(parts[2]), Float.parseFloat(parts[3])});
						break;
					case "vt":
						//OBJ measures v up from the bottom of the image and Minecraft measures it
						//down from the top, so this is where that is undone -- once, here, rather
						//than in every generator that ever writes one of these files.
						uvs.add(new float[]{Float.parseFloat(parts[1]),
								1F-Float.parseFloat(parts[2])});
						break;
					case "o":
					case "g":
						current = built.computeIfAbsent(parts[1], key -> new ArrayList<>());
						break;
					case "f":
						if(current==null)
							throw new IOException("a face before any group");
						if(parts.length!=5)
							//Quads only, deliberately: one primitive means one draw path and no
							//triangle case to get wrong. The generator emits nothing else.
							throw new IOException("a face with "+(parts.length-1)+" corners");
						face(current, positions, uvs, parts);
						break;
					default:
						//mtllib, usemtl, vn: the material is the entity's bound texture and the
						//normals are computed from the winding, so there is nothing to read.
						break;
				}
			}
		} catch(Exception e)
		{
			IELogger.error("Could not read the entity model "+location+": "+e);
			return;
		}
		for(Map.Entry<String, List<Float>> entry : built.entrySet())
		{
			float[] data = new float[entry.getValue().size()];
			for(int i = 0; i < data.length; i++)
				data[i] = entry.getValue().get(i);
			groups.put(entry.getKey(), data);
		}
	}

	/**
	 * One quad, with the normal taken from its winding.
	 * <p>
	 * Taken rather than read: a face wound counter-clockwise seen from outside is the file's
	 * only statement about which way it faces, the generator checks every face against that,
	 * and a {@code vn} in the file would be a second copy of the same fact with its own way of
	 * being wrong.
	 */
	private void face(List<Float> into, List<float[]> positions, List<float[]> uvs,
					  String[] parts) throws IOException
	{
		float[][] corner = new float[4][];
		float[][] uv = new float[4][];
		for(int i = 0; i < 4; i++)
		{
			String[] indices = parts[i+1].split("/");
			corner[i] = positions.get(index(indices[0], positions.size()));
			if(indices.length < 2||indices[1].isEmpty())
				throw new IOException("a face corner with no texture coordinate");
			uv[i] = uvs.get(index(indices[1], uvs.size()));
		}
		float[] normal = normal(corner);
		for(int i = 0; i < 4; i++)
		{
			into.add(corner[i][0]);
			into.add(corner[i][1]);
			into.add(corner[i][2]);
			into.add(uv[i][0]);
			into.add(uv[i][1]);
			into.add(normal[0]);
			into.add(normal[1]);
			into.add(normal[2]);
		}
	}

	private int index(String token, int size) throws IOException
	{
		int value = Integer.parseInt(token);
		//OBJ indices count from one, and a negative one counts back from the end.
		int resolved = value < 0?size+value: value-1;
		if(resolved < 0||resolved >= size)
			throw new IOException("index "+value+" is outside the file's own tables");
		return resolved;
	}

	/** Newell's method, which is right for a quad where a cross product of two edges is not. */
	private static float[] normal(float[][] points)
	{
		float x = 0, y = 0, z = 0;
		for(int i = 0; i < points.length; i++)
		{
			float[] a = points[i], b = points[(i+1)%points.length];
			x += (a[1]-b[1])*(a[2]+b[2]);
			y += (a[2]-b[2])*(a[0]+b[0]);
			z += (a[0]-b[0])*(a[1]+b[1]);
		}
		float length = (float)Math.sqrt(x*x+y*y+z*z);
		if(length==0)
			return new float[]{0, 1, 0};
		return new float[]{x/length, y/length, z/length};
	}
}
