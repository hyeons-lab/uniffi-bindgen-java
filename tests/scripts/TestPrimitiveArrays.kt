import uniffi.primitive_arrays.PrimitiveArrays

fun main() {
    testFloat32Arrays()
    testFloat64Arrays()
    testInt16Arrays()
    testInt32Arrays()
    testInt64Arrays()
    testBooleanArrays()
    testUnsignedArrays()
    testEmptyArrays()
    testLargeArrays()
}

private fun testFloat32Arrays() {
    val floats = floatArrayOf(1.0f, 2.5f, 3.14159f, -0.5f, Float.MAX_VALUE, Float.MIN_VALUE)
    check(PrimitiveArrays.roundtripFloat32(floats).contentEquals(floats))

    check(PrimitiveArrays.sumFloat32(floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)) == 10.0f)
}

private fun testFloat64Arrays() {
    val doubles = doubleArrayOf(1.0, 2.5, 3.14159265359, -0.5, Double.MAX_VALUE, Double.MIN_VALUE)
    check(PrimitiveArrays.roundtripFloat64(doubles).contentEquals(doubles))

    check(PrimitiveArrays.sumFloat64(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)) == 15.0)
}

private fun testInt16Arrays() {
    val shorts = shortArrayOf(-1, 0, 1, Short.MAX_VALUE, Short.MIN_VALUE, 12345)
    check(PrimitiveArrays.roundtripInt16(shorts).contentEquals(shorts))

    check(PrimitiveArrays.sumInt16(shortArrayOf(1, 2, 3, 4, 5)) == 15.toShort())
}

private fun testInt32Arrays() {
    val ints = intArrayOf(-1, 0, 1, Int.MAX_VALUE, Int.MIN_VALUE, 123456789)
    check(PrimitiveArrays.roundtripInt32(ints).contentEquals(ints))

    check(PrimitiveArrays.sumInt32(intArrayOf(1, 2, 3, 4, 5)) == 15)
}

private fun testInt64Arrays() {
    val longs = longArrayOf(-1L, 0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, 123456789012345L)
    check(PrimitiveArrays.roundtripInt64(longs).contentEquals(longs))

    check(PrimitiveArrays.sumInt64(longArrayOf(1L, 2L, 3L, 4L, 5L)) == 15L)
}

private fun testBooleanArrays() {
    val bools = booleanArrayOf(true, false, true, true, false, false, true)
    check(PrimitiveArrays.roundtripBool(bools).contentEquals(bools))
    check(PrimitiveArrays.countTrue(bools) == 4)

    check(PrimitiveArrays.countTrue(booleanArrayOf(false, false, false)) == 0)
    check(PrimitiveArrays.countTrue(booleanArrayOf(true, true, true)) == 3)
}

private fun testUnsignedArrays() {
    // u16 carried in ShortArray; values that would be negative as signed.
    val u16 = shortArrayOf(0, 1, 32767, (-32768).toShort(), (-1).toShort())
    check(PrimitiveArrays.roundtripUint16(u16).contentEquals(u16))

    // u32 in IntArray.
    val u32 = intArrayOf(0, 1, Int.MAX_VALUE, Int.MIN_VALUE, -1)
    check(PrimitiveArrays.roundtripUint32(u32).contentEquals(u32))

    // u64 in LongArray.
    val u64 = longArrayOf(0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)
    check(PrimitiveArrays.roundtripUint64(u64).contentEquals(u64))
}

private fun testEmptyArrays() {
    check(PrimitiveArrays.roundtripFloat32(FloatArray(0)).isEmpty())
    check(PrimitiveArrays.roundtripFloat64(DoubleArray(0)).isEmpty())
    check(PrimitiveArrays.roundtripInt16(ShortArray(0)).isEmpty())
    check(PrimitiveArrays.roundtripInt32(IntArray(0)).isEmpty())
    check(PrimitiveArrays.roundtripInt64(LongArray(0)).isEmpty())
    check(PrimitiveArrays.roundtripBool(BooleanArray(0)).isEmpty())

    check(PrimitiveArrays.sumFloat32(FloatArray(0)) == 0.0f)
    check(PrimitiveArrays.sumFloat64(DoubleArray(0)) == 0.0)
    check(PrimitiveArrays.sumInt32(IntArray(0)) == 0)
    check(PrimitiveArrays.countTrue(BooleanArray(0)) == 0)
}

private fun testLargeArrays() {
    val size = 10_000

    val largeFloats = FloatArray(size) { i -> i.toFloat() * 0.1f }
    check(PrimitiveArrays.roundtripFloat32(largeFloats).contentEquals(largeFloats))

    val largeInts = IntArray(size) { it }
    check(PrimitiveArrays.roundtripInt32(largeInts).contentEquals(largeInts))

    val largeBools = BooleanArray(size) { it % 2 == 0 }
    check(PrimitiveArrays.roundtripBool(largeBools).contentEquals(largeBools))
    check(PrimitiveArrays.countTrue(largeBools) == size / 2)
}
