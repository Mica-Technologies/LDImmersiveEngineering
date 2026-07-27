/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.chickenbones;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 4x4 transform matrix.
 * <p>
 * Conventions this class pins down, because they are easy to get backwards:
 * <ul>
 * <li>vectors are columns, so {@code apply(v)} computes {@code M * v};</li>
 * <li>{@code multiply(b)} makes {@code this = this * b} -- b is applied to a vector first;</li>
 * <li>{@code leftMultiply(b)} makes {@code this = b * this};</li>
 * <li>{@code translate}/{@code scale}/{@code rotate} all post-multiply, so calls read in the
 * order the transforms are applied to a vector, last call first.</li>
 * </ul>
 */
class Matrix4Test
{
	private static final double D = 1e-9;
	/** invert() round-trips through a float matrix, so it only carries float precision */
	private static final double FLOAT_D = 1e-5;

	private static void assertMatrix(Matrix4 m,
									 double m00, double m01, double m02, double m03,
									 double m10, double m11, double m12, double m13,
									 double m20, double m21, double m22, double m23,
									 double m30, double m31, double m32, double m33,
									 double delta)
	{
		assertAll(
				() -> assertEquals(m00, m.m00, delta, "m00"),
				() -> assertEquals(m01, m.m01, delta, "m01"),
				() -> assertEquals(m02, m.m02, delta, "m02"),
				() -> assertEquals(m03, m.m03, delta, "m03"),
				() -> assertEquals(m10, m.m10, delta, "m10"),
				() -> assertEquals(m11, m.m11, delta, "m11"),
				() -> assertEquals(m12, m.m12, delta, "m12"),
				() -> assertEquals(m13, m.m13, delta, "m13"),
				() -> assertEquals(m20, m.m20, delta, "m20"),
				() -> assertEquals(m21, m.m21, delta, "m21"),
				() -> assertEquals(m22, m.m22, delta, "m22"),
				() -> assertEquals(m23, m.m23, delta, "m23"),
				() -> assertEquals(m30, m.m30, delta, "m30"),
				() -> assertEquals(m31, m.m31, delta, "m31"),
				() -> assertEquals(m32, m.m32, delta, "m32"),
				() -> assertEquals(m33, m.m33, delta, "m33")
		);
	}

	private static void assertIdentity(Matrix4 m)
	{
		assertMatrix(m, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, D);
	}

	private static void assertVec(Vec3d expected, Vec3d actual, double delta)
	{
		assertAll(
				() -> assertEquals(expected.x, actual.x, delta, "x"),
				() -> assertEquals(expected.y, actual.y, delta, "y"),
				() -> assertEquals(expected.z, actual.z, delta, "z")
		);
	}

	// ---------------------------------------------------------------- construction

	@Test
	@DisplayName("the no-arg constructor produces the identity")
	void defaultConstructorIsIdentity()
	{
		assertIdentity(new Matrix4());
	}

	@Test
	@DisplayName("the shared IDENTITY constant is the identity")
	void identityConstant()
	{
		assertIdentity(Matrix4.IDENTITY);
	}

	@Test
	@DisplayName("the 16-argument constructor stores values in row-major order")
	void explicitConstructorIsRowMajor()
	{
		Matrix4 m = new Matrix4(
				1, 2, 3, 4,
				5, 6, 7, 8,
				9, 10, 11, 12,
				13, 14, 15, 16);
		assertMatrix(m, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, D);
	}

	@Test
	@DisplayName("the copy constructor copies every cell")
	void copyConstructor()
	{
		Matrix4 src = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4 copy = new Matrix4(src);
		assertEquals(src, copy);
	}

	@Test
	@DisplayName("the copy constructor makes an independent matrix")
	void copyConstructorIsDeep()
	{
		Matrix4 src = new Matrix4();
		Matrix4 copy = new Matrix4(src);
		src.m00 = 99;
		assertEquals(1, copy.m00, D);
	}

	@Test
	@DisplayName("copy() equals the original but is a different object")
	void copyMethod()
	{
		Matrix4 src = new Matrix4().translate(1, 2, 3);
		Matrix4 copy = src.copy();
		assertEquals(src, copy);
		assertNotSame(src, copy);
	}

	@Test
	@DisplayName("set() overwrites every cell and returns this")
	void setCopiesEverything()
	{
		Matrix4 target = new Matrix4().scale(7, 7, 7);
		Matrix4 src = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		assertSame(target, target.set(src));
		assertEquals(src, target);
	}

	@Test
	@DisplayName("setIdentity() clears an arbitrary matrix back to the identity")
	void setIdentityClearsEverything()
	{
		Matrix4 m = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		assertSame(m, m.setIdentity());
		assertIdentity(m);
	}

	// ---------------------------------------------------------------- translate

	@Test
	@DisplayName("translate() on the identity fills the last column")
	void translateOnIdentity()
	{
		Matrix4 m = new Matrix4().translate(1, 2, 3);
		assertMatrix(m, 1, 0, 0, 1, 0, 1, 0, 2, 0, 0, 1, 3, 0, 0, 0, 1, D);
	}

	@Test
	@DisplayName("translate() returns this for chaining")
	void translateReturnsThis()
	{
		Matrix4 m = new Matrix4();
		assertSame(m, m.translate(1, 1, 1));
	}

	@Test
	@DisplayName("two translations accumulate")
	void translationsAccumulate()
	{
		Matrix4 m = new Matrix4().translate(1, 2, 3).translate(10, 20, 30);
		assertVec(new Vec3d(11, 22, 33), m.apply(Vec3d.ZERO), D);
	}

	@Test
	@DisplayName("translating by zero is a no-op")
	void translateZero()
	{
		Matrix4 m = new Matrix4().scale(2, 3, 4).translate(0, 0, 0);
		assertMatrix(m, 2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 0, 0, 0, 1, D);
	}

	@Test
	@DisplayName("translate(Vector3f) matches translate(x, y, z)")
	void translateVectorOverload()
	{
		Matrix4 a = new Matrix4().translate(new Vector3f(1, 2, 3));
		Matrix4 b = new Matrix4().translate(1, 2, 3);
		assertEquals(b, a);
	}

	@Test
	@DisplayName("translate post-multiplies: a later scale does not rescale the earlier offset")
	void translateThenScaleOrdering()
	{
		// M = T * S, so a vector is scaled first and then offset
		Matrix4 m = new Matrix4().translate(1, 1, 1).scale(2, 2, 2);
		assertVec(new Vec3d(3, 3, 3), m.apply(new Vec3d(1, 1, 1)), D);
	}

	@Test
	@DisplayName("scale before translate scales the offset too")
	void scaleThenTranslateOrdering()
	{
		// M = S * T, so a vector is offset first and then scaled
		Matrix4 m = new Matrix4().scale(2, 2, 2).translate(1, 1, 1);
		assertVec(new Vec3d(4, 4, 4), m.apply(new Vec3d(1, 1, 1)), D);
	}

	// ---------------------------------------------------------------- scale

	@Test
	@DisplayName("scale() on the identity fills the diagonal")
	void scaleOnIdentity()
	{
		Matrix4 m = new Matrix4().scale(2, 3, 4);
		assertMatrix(m, 2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 0, 0, 0, 1, D);
	}

	@Test
	@DisplayName("scale() returns this for chaining")
	void scaleReturnsThis()
	{
		Matrix4 m = new Matrix4();
		assertSame(m, m.scale(2, 2, 2));
	}

	@Test
	@DisplayName("scaling by one is a no-op")
	void scaleByOne()
	{
		Matrix4 m = new Matrix4().translate(1, 2, 3).scale(1, 1, 1);
		assertMatrix(m, 1, 0, 0, 1, 0, 1, 0, 2, 0, 0, 1, 3, 0, 0, 0, 1, D);
	}

	@Test
	@DisplayName("scales multiply together")
	void scalesCompose()
	{
		Matrix4 m = new Matrix4().scale(2, 2, 2).scale(3, 3, 3);
		assertVec(new Vec3d(6, 6, 6), m.apply(new Vec3d(1, 1, 1)), D);
	}

	@Test
	@DisplayName("scale(Vector3f) matches scale(x, y, z)")
	void scaleVectorOverload()
	{
		Matrix4 a = new Matrix4().scale(new Vector3f(2, 3, 4));
		Matrix4 b = new Matrix4().scale(2, 3, 4);
		assertEquals(b, a);
	}

	@Test
	@DisplayName("a negative scale mirrors")
	void negativeScaleMirrors()
	{
		Matrix4 m = new Matrix4().scale(-1, 1, 1);
		assertVec(new Vec3d(-5, 5, 5), m.apply(new Vec3d(5, 5, 5)), D);
	}

	// ---------------------------------------------------------------- rotate

	@Test
	@DisplayName("rotating by zero is a no-op")
	void rotateZeroAngle()
	{
		Matrix4 m = new Matrix4().rotate(0, 0, 1, 0);
		assertIdentity(m);
	}

	@Test
	@DisplayName("90 degrees about Y maps +X to -Z")
	void rotateY90()
	{
		Matrix4 m = new Matrix4().rotate(Math.PI/2, 0, 1, 0);
		assertVec(new Vec3d(0, 0, -1), m.apply(new Vec3d(1, 0, 0)), 1e-12);
	}

	@Test
	@DisplayName("90 degrees about X maps +Z to -Y")
	void rotateX90()
	{
		Matrix4 m = new Matrix4().rotate(Math.PI/2, 1, 0, 0);
		assertVec(new Vec3d(0, -1, 0), m.apply(new Vec3d(0, 0, 1)), 1e-12);
	}

	@Test
	@DisplayName("90 degrees about Z maps +X to +Y")
	void rotateZ90()
	{
		Matrix4 m = new Matrix4().rotate(Math.PI/2, 0, 0, 1);
		assertVec(new Vec3d(0, 1, 0), m.apply(new Vec3d(1, 0, 0)), 1e-12);
	}

	@Test
	@DisplayName("rotating about an axis leaves that axis fixed")
	void rotationAxisIsFixed()
	{
		Matrix4 m = new Matrix4().rotate(1.2345, 0, 1, 0);
		assertVec(new Vec3d(0, 3, 0), m.apply(new Vec3d(0, 3, 0)), 1e-12);
	}

	@Test
	@DisplayName("four 90 degree rotations come back to the identity")
	void fourQuarterTurnsIsIdentity()
	{
		Matrix4 m = new Matrix4();
		for(int i = 0; i < 4; i++)
			m.rotate(Math.PI/2, 0, 1, 0);
		assertMatrix(m, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1e-12);
	}

	@Test
	@DisplayName("rotation preserves length")
	void rotationPreservesLength()
	{
		Matrix4 m = new Matrix4().rotate(0.777, 1/Math.sqrt(3), 1/Math.sqrt(3), 1/Math.sqrt(3));
		Vec3d v = new Vec3d(1, 2, 3);
		assertEquals(v.length(), m.apply(v).length(), 1e-9);
	}

	@Test
	@DisplayName("rotate(angle, Vector3f) matches rotate(angle, x, y, z)")
	void rotateVectorOverload()
	{
		Matrix4 a = new Matrix4().rotate(0.4, new Vector3f(0, 1, 0));
		Matrix4 b = new Matrix4().rotate(0.4, 0, 1, 0);
		assertMatrix(a, b.m00, b.m01, b.m02, b.m03, b.m10, b.m11, b.m12, b.m13,
				b.m20, b.m21, b.m22, b.m23, b.m30, b.m31, b.m32, b.m33, 1e-7);
	}

	@Test
	@DisplayName("rotate() returns this for chaining")
	void rotateReturnsThis()
	{
		Matrix4 m = new Matrix4();
		assertSame(m, m.rotate(1, 0, 1, 0));
	}

	@Test
	@DisplayName("opposite rotations cancel")
	void oppositeRotationsCancel()
	{
		Matrix4 m = new Matrix4().rotate(0.9, 0, 0, 1).rotate(-0.9, 0, 0, 1);
		assertMatrix(m, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1e-12);
	}

	// ---------------------------------------------------------------- multiplication

	@Test
	@DisplayName("multiplying by the identity changes nothing")
	void multiplyByIdentity()
	{
		Matrix4 m = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4 expected = m.copy();
		m.multiply(new Matrix4());
		assertEquals(expected, m);
	}

	@Test
	@DisplayName("left-multiplying by the identity changes nothing")
	void leftMultiplyByIdentity()
	{
		Matrix4 m = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4 expected = m.copy();
		m.leftMultiply(new Matrix4());
		assertEquals(expected, m);
	}

	@Test
	@DisplayName("multiply(b) means this = this * b: b is applied to a vector first")
	void multiplyIsRightMultiplication()
	{
		Matrix4 translate = new Matrix4().translate(1, 0, 0);
		Matrix4 scale = new Matrix4().scale(2, 2, 2);
		translate.multiply(scale);
		// T * S: scale, then translate
		assertVec(new Vec3d(3, 2, 2), translate.apply(new Vec3d(1, 1, 1)), D);
	}

	@Test
	@DisplayName("leftMultiply(b) means this = b * this: this is applied to a vector first")
	void leftMultiplyIsLeftMultiplication()
	{
		Matrix4 translate = new Matrix4().translate(1, 0, 0);
		Matrix4 scale = new Matrix4().scale(2, 2, 2);
		translate.leftMultiply(scale);
		// S * T: translate, then scale
		assertVec(new Vec3d(4, 2, 2), translate.apply(new Vec3d(1, 1, 1)), D);
	}

	@Test
	@DisplayName("multiply and leftMultiply are mirror images of each other")
	void multiplyAndLeftMultiplyAgree()
	{
		Matrix4 a = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4 b = new Matrix4().translate(3, 1, 4).rotate(0.5, 0, 1, 0);

		Matrix4 viaMultiply = a.copy().multiply(b);   // a * b
		Matrix4 viaLeft = b.copy().leftMultiply(a);   // a * b
		assertMatrix(viaLeft,
				viaMultiply.m00, viaMultiply.m01, viaMultiply.m02, viaMultiply.m03,
				viaMultiply.m10, viaMultiply.m11, viaMultiply.m12, viaMultiply.m13,
				viaMultiply.m20, viaMultiply.m21, viaMultiply.m22, viaMultiply.m23,
				viaMultiply.m30, viaMultiply.m31, viaMultiply.m32, viaMultiply.m33, 1e-12);
	}

	@Test
	@DisplayName("multiplication is not commutative")
	void multiplicationIsNotCommutative()
	{
		Matrix4 a = new Matrix4().translate(1, 0, 0);
		Matrix4 b = new Matrix4().scale(2, 2, 2);
		assertNotEquals(a.copy().multiply(b), b.copy().multiply(a));
	}

	@Test
	@DisplayName("multiply() returns this for chaining")
	void multiplyReturnsThis()
	{
		Matrix4 m = new Matrix4();
		assertSame(m, m.multiply(new Matrix4()));
	}

	@Test
	@DisplayName("a.apply(b) mutates b into b * a and leaves a alone")
	void applyMatrixMutatesTheArgument()
	{
		Matrix4 a = new Matrix4().translate(1, 0, 0);
		Matrix4 aBefore = a.copy();
		Matrix4 b = new Matrix4().scale(2, 2, 2);

		a.apply(b);

		assertEquals(aBefore, a, "apply(Matrix4) must not touch the receiver");
		assertEquals(new Matrix4().scale(2, 2, 2).multiply(aBefore), b);
	}

	// ---------------------------------------------------------------- transpose

	@Test
	@DisplayName("transpose() swaps rows and columns")
	void transposeSwaps()
	{
		Matrix4 m = new Matrix4(
				1, 2, 3, 4,
				5, 6, 7, 8,
				9, 10, 11, 12,
				13, 14, 15, 16).transpose();
		assertMatrix(m,
				1, 5, 9, 13,
				2, 6, 10, 14,
				3, 7, 11, 15,
				4, 8, 12, 16, D);
	}

	@Test
	@DisplayName("transposing twice returns the original")
	void transposeIsAnInvolution()
	{
		Matrix4 original = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4 m = original.copy().transpose().transpose();
		assertEquals(original, m);
	}

	@Test
	@DisplayName("the identity is its own transpose")
	void identityTransposesToItself()
	{
		assertIdentity(new Matrix4().transpose());
	}

	@Test
	@DisplayName("transpose() returns this for chaining")
	void transposeReturnsThis()
	{
		Matrix4 m = new Matrix4();
		assertSame(m, m.transpose());
	}

	// ---------------------------------------------------------------- invert

	@Test
	@DisplayName("inverting the identity gives the identity")
	void invertIdentity()
	{
		Matrix4 m = new Matrix4();
		m.invert();
		assertMatrix(m, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, FLOAT_D);
	}

	@Test
	@DisplayName("inverting a scale reciprocates the diagonal")
	void invertScale()
	{
		Matrix4 m = new Matrix4().scale(2, 4, 8);
		m.invert();
		assertMatrix(m, 0.5, 0, 0, 0, 0, 0.25, 0, 0, 0, 0, 0.125, 0, 0, 0, 0, 1, FLOAT_D);
	}

	@Test
	@DisplayName("inverting a translation negates the offset")
	void invertTranslation()
	{
		Matrix4 m = new Matrix4().translate(1, 2, 3);
		m.invert();
		assertVec(new Vec3d(-1, -2, -3), m.apply(Vec3d.ZERO), FLOAT_D);
	}

	@Test
	@DisplayName("a matrix times its inverse is the identity")
	void inverseUndoesTransform()
	{
		Matrix4 m = new Matrix4().translate(3, 1, 4).rotate(0.6, 0, 1, 0).scale(2, 2, 2);
		Matrix4 inv = m.copy();
		inv.invert();
		Matrix4 product = m.copy().multiply(inv);
		assertMatrix(product, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, FLOAT_D);
	}

	@Test
	@DisplayName("applying a transform then its inverse round-trips a point")
	void inverseRoundTripsAPoint()
	{
		Matrix4 m = new Matrix4().translate(3, 1, 4).rotate(0.6, 0, 1, 0);
		Matrix4 inv = m.copy();
		inv.invert();
		Vec3d v = new Vec3d(1, 2, 3);
		assertVec(v, inv.apply(m.apply(v)), FLOAT_D);
	}

	// ---------------------------------------------------------------- apply

	@Test
	@DisplayName("the identity leaves a Vec3d untouched")
	void applyIdentityToVec3d()
	{
		Vec3d v = new Vec3d(1, 2, 3);
		assertVec(v, new Matrix4().apply(v), D);
	}

	@Test
	@DisplayName("apply(Vec3d) returns a new vector and does not alias the input")
	void applyVec3dReturnsNewVector()
	{
		Vec3d v = new Vec3d(1, 2, 3);
		Vec3d out = new Matrix4().translate(1, 0, 0).apply(v);
		assertEquals(1, v.x, D, "the input vector must be untouched");
		assertEquals(2, out.x, D);
	}

	@Test
	@DisplayName("apply(Vec3d) includes the translation column")
	void applyVec3dIncludesTranslation()
	{
		Matrix4 m = new Matrix4().translate(10, 20, 30);
		assertVec(new Vec3d(11, 22, 33), m.apply(new Vec3d(1, 2, 3)), D);
	}

	@Test
	@DisplayName("apply(Vector3f) transforms in place")
	void applyVecmathVectorInPlace()
	{
		Vector3f v = new Vector3f(1, 2, 3);
		new Matrix4().translate(1, 1, 1).scale(2, 2, 2).apply(v);
		assertEquals(3, v.x, 1e-6);
		assertEquals(5, v.y, 1e-6);
		assertEquals(7, v.z, 1e-6);
	}

	@Test
	@DisplayName("apply(lwjgl Vector3f) returns a new transformed vector")
	void applyLwjglVector()
	{
		org.lwjgl.util.vector.Vector3f v = new org.lwjgl.util.vector.Vector3f(1, 2, 3);
		org.lwjgl.util.vector.Vector3f out = new Matrix4().translate(1, 1, 1).apply(v);
		assertEquals(1f, v.x, 1e-6f, "the input vector must be untouched");
		assertEquals(2f, out.x, 1e-6f);
		assertEquals(3f, out.y, 1e-6f);
		assertEquals(4f, out.z, 1e-6f);
	}

	@Test
	@DisplayName("apply() on the zero vector returns the translation column")
	void applyZeroVectorReturnsTranslation()
	{
		Matrix4 m = new Matrix4().translate(7, 8, 9).rotate(0.3, 0, 1, 0);
		assertVec(new Vec3d(7, 8, 9), m.apply(Vec3d.ZERO), 1e-12);
	}

	// ---------------------------------------------------------------- EnumFacing constructor

	@Test
	@DisplayName("Matrix4(NORTH) is the identity -- north is the reference orientation")
	void facingNorthIsIdentity()
	{
		assertMatrix(new Matrix4(EnumFacing.NORTH), 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 1e-12);
	}

	@Test
	@DisplayName("Matrix4(facing) rotates the north face onto that facing, for all six sides")
	void facingConstructorMapsNorthOntoFacing()
	{
		// the north face of a block centred on (.5,.5,.5)
		Vec3d northFace = new Vec3d(0.5, 0.5, 0);
		for(EnumFacing facing : EnumFacing.VALUES)
		{
			Vec3d expected = new Vec3d(0.5, 0.5, 0.5)
					.add(new Vec3d(facing.getDirectionVec().getX()*0.5,
							facing.getDirectionVec().getY()*0.5,
							facing.getDirectionVec().getZ()*0.5));
			assertVec(expected, new Matrix4(facing).apply(northFace), 1e-12);
		}
	}

	@Test
	@DisplayName("Matrix4(facing) keeps the block centre fixed, for all six sides")
	void facingConstructorFixesTheCentre()
	{
		Vec3d centre = new Vec3d(0.5, 0.5, 0.5);
		for(EnumFacing facing : EnumFacing.VALUES)
			assertVec(centre, new Matrix4(facing).apply(centre), 1e-12);
	}

	@Test
	@DisplayName("Matrix4(SOUTH) is a half turn about the block centre")
	void facingSouthIsHalfTurn()
	{
		assertVec(new Vec3d(0, 0.5, 1), new Matrix4(EnumFacing.SOUTH).apply(new Vec3d(1, 0.5, 0)), 1e-12);
	}

	@Test
	@DisplayName("Matrix4(facing) is length preserving about the centre, for all six sides")
	void facingConstructorIsRigid()
	{
		Vec3d centre = new Vec3d(0.5, 0.5, 0.5);
		Vec3d point = new Vec3d(0.9, 0.1, 0.2);
		double before = point.subtract(centre).length();
		for(EnumFacing facing : EnumFacing.VALUES)
			assertEquals(before, new Matrix4(facing).apply(point).subtract(centre).length(), 1e-12,
					facing.toString());
	}

	// ---------------------------------------------------------------- vecmath interop

	@Test
	@DisplayName("toMatrix4f() preserves every cell")
	void toMatrix4f()
	{
		Matrix4 m = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4f f = m.toMatrix4f();
		assertEquals(1f, f.m00, 1e-6f);
		assertEquals(4f, f.m03, 1e-6f);
		assertEquals(13f, f.m30, 1e-6f);
		assertEquals(16f, f.m33, 1e-6f);
	}

	@Test
	@DisplayName("Matrix4f round-trips through toMatrix4f/fromMatrix4f")
	void matrix4fRoundTrip()
	{
		Matrix4 original = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		Matrix4 restored = new Matrix4();
		restored.fromMatrix4f(original.toMatrix4f());
		assertEquals(original, restored);
	}

	@Test
	@DisplayName("the Matrix4f constructor copies every cell")
	void matrix4fConstructor()
	{
		Matrix4f f = new Matrix4f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		assertEquals(new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), new Matrix4(f));
	}

	@Test
	@DisplayName("toFloatBuffer() writes the matrix column-major, as OpenGL wants it")
	void toFloatBufferIsColumnMajor()
	{
		FloatBuffer buf = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		Matrix4 m = new Matrix4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		assertSame(buf, m.toFloatBuffer(buf));
		float[] out = new float[16];
		buf.get(out);
		assertArrayEquals(new float[]{1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15, 4, 8, 12, 16}, out, 1e-6f);
	}

	// ---------------------------------------------------------------- equals/hashCode/toString

	@Test
	@DisplayName("equals() is true for identical matrices")
	void equalsIdentical()
	{
		assertEquals(new Matrix4().translate(1, 2, 3), new Matrix4().translate(1, 2, 3));
	}

	@Test
	@DisplayName("equals() is false when a single cell differs")
	void equalsDetectsOneCell()
	{
		Matrix4 a = new Matrix4();
		Matrix4 b = new Matrix4();
		b.m23 = 1e-9;
		assertNotEquals(a, b);
	}

	@Test
	@DisplayName("equals() rejects null and foreign types")
	void equalsRejectsOthers()
	{
		Matrix4 m = new Matrix4();
		assertNotEquals(m, null);
		assertNotEquals(m, "not a matrix");
		assertEquals(m, m);
	}

	@Test
	@DisplayName("equal matrices have equal hash codes")
	void hashCodeAgreesWithEquals()
	{
		assertEquals(new Matrix4().scale(2, 3, 4).hashCode(), new Matrix4().scale(2, 3, 4).hashCode());
	}

	@Test
	@DisplayName("toString() renders four bracketed rows")
	void toStringShape()
	{
		String s = new Matrix4().toString();
		assertEquals(4, s.split("\n").length);
		assertTrue(s.startsWith("["), s);
	}
}
