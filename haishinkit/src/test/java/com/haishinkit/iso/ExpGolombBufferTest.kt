package com.haishinkit.iso

import com.haishinkit.lang.decodeHex
import junit.framework.TestCase
import java.nio.ByteBuffer
import java.util.BitSet

class ExpGolombBufferTest : TestCase() {
    fun test0() {
        val bitSet = BitSet()
        bitSet.set(0, true)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(0, expGolombBuffer.int)
    }

    fun test00() {
        val bitSet = BitSet()
        bitSet.set(0, true)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(0, expGolombBuffer.int)
    }

    fun test000() {
        val bitSet = BitSet()
        bitSet.set(0, true)
        bitSet.set(0, true)
        bitSet.set(0, true)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(0, expGolombBuffer.int)
        assertEquals(0, expGolombBuffer.int)
        assertEquals(0, expGolombBuffer.int)
    }

    fun test01() {
        val bitSet = BitSet()
        bitSet.set(0, true)
        bitSet.set(1, false)
        bitSet.set(2, true)
        bitSet.set(3, false)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(0, expGolombBuffer.int)
        assertEquals(1, expGolombBuffer.int)
    }

    fun test1() {
        val bitSet = BitSet()
        bitSet.set(0, false)
        bitSet.set(1, true)
        bitSet.set(2, false)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(1, expGolombBuffer.int)
    }

    fun test5() {
        val bitSet = BitSet()
        bitSet.set(0, false)
        bitSet.set(1, false)
        bitSet.set(2, true)
        bitSet.set(3, true)
        bitSet.set(4, false)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(5, expGolombBuffer.int)
    }

    fun test8() {
        val bitSet = BitSet()
        bitSet.set(0, false)
        bitSet.set(1, false)
        bitSet.set(2, false)
        bitSet.set(3, true)
        bitSet.set(4, false)
        bitSet.set(5, false)
        bitSet.set(6, true)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(8, expGolombBuffer.int)
    }

    fun test81() {
        val bitSet = BitSet()
        bitSet.set(0, false)
        bitSet.set(1, false)
        bitSet.set(2, false)
        bitSet.set(3, true)
        bitSet.set(4, false)
        bitSet.set(5, false)
        bitSet.set(6, true)
        bitSet.set(7, false)
        bitSet.set(8, true)
        bitSet.set(9, false)
        val expGolombBuffer = ExpGolombBuffer(bitSet)
        assertEquals(8, expGolombBuffer.int)
        assertEquals(1, expGolombBuffer.int)
    }

    fun testData() {
        val byteBuffer = ByteBuffer.wrap("f841a2".decodeHex())

        val expGolombBuffer = ExpGolombBuffer.wrap(byteBuffer)
        assertEquals(0, expGolombBuffer.int) // seq_parameter_set_id
        assertEquals(0, expGolombBuffer.int) // log2_max_frame_num_minus4
        assertEquals(0, expGolombBuffer.int) // pic_order_cnt_type
        assertEquals(0, expGolombBuffer.int) // log2_max_pic_order_cnt_lsb_minus4
        assertEquals(0, expGolombBuffer.int) // num_ref_frames
        assertEquals(false, expGolombBuffer.boolean) // gaps_in_frame_num_value_allowed_flag
        assertEquals(7, expGolombBuffer.int) //  pic_width_in_mbs_minus_1
        assertEquals(5, expGolombBuffer.int) // pic_height_in_map_units_minus_1
    }
}
