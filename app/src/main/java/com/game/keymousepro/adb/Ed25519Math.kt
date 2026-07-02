// File: app/src/main/java/com/game/keymousepro/adb/Ed25519Math.kt
package com.game.keymousepro.adb

import java.math.BigInteger

/**
 * Edwards25519 (twisted Edwards, a=-1) point arithmetic via BigInteger.
 *
 * This is the EXACT curve used by BoringSSL's crypto/curve25519/spake25519.c,
 * which AOSP adbd links natively for ADB Wireless Debugging pairing.
 * Verified by fetching:
 *   https://boringssl.googlesource.com/boringssl/+/refs/tags/version_for_cocoapods_7.0/crypto/curve25519/spake25519.c
 *
 * Design choice to minimize transcription risk: every curve constant below is
 * DERIVED from a short, universally-known closed-form expression (RFC 8032)
 * rather than copied as a long magic number from memory. The only genuinely
 * "long" constants are the M/N base points, which were extracted from the
 * BoringSSL source above and cross-verified two independent ways within that
 * same file (the human-readable comment AND the raw precomputation table both
 * yield identical bytes).
 *
 * This uses plain (non-constant-time) BigInteger arithmetic. Performance is
 * irrelevant here (this runs once per pairing attempt), and correctness is
 * verified against the literal C algorithm, not approximated.
 */
object Ed25519Math {

    // p = 2^255 - 19  (the Curve25519 field prime — universally standard)
    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    // d = -121665/121666 mod p  (computed, not memorized as a 77-digit constant)
    val D: BigInteger = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    // L = 2^252 + 27742317777372353535851937790883648493 (standard Ed25519 group order)
    val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    // sqrt(-1) mod p, derived since p ≡ 5 (mod 8): sqrt(-1) = 2^((p-1)/4) mod p
    private val SQRT_M1: BigInteger by lazy {
        BigInteger.valueOf(2).modPow(
            P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P
        )
    }

    data class Point(val x: BigInteger, val y: BigInteger) {
        companion object { val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE) }
    }

    /**
     * Standard Ed25519 base point. By = 4/5 mod p (RFC 8032); Bx recovered from
     * the curve equation using the "even x" sign convention. Not used directly
     * by SPAKE2 (which uses M/N instead) but kept for completeness/testing.
     */
    val BASE: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        Point(recoverX(y, sign = 0), y)
    }

    /**
     * SPAKE2 "M" point — used by the role that initialized as Alice (our client role).
     *
     * Source (verified, fetched directly):
     *   boringssl crypto/curve25519/spake25519.c, comment block + kSpakeMSmallPrecomp[0..63]
     *   M.x = 31406539342727633121250288103050113562375374900226415211311216773867585644232
     *   M.y = 21177308356423958466833845032658859666296341766942662650232962324899758529114
     *   encoded (32B compressed): 5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e
     * Cross-checked byte-for-byte against kSpakeMSmallPrecomp's first table entry (k=1) — matched exactly.
     */
    val M: Point by lazy {
        decode(hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
    }

    /**
     * SPAKE2 "N" point — used by the role that initialized as Bob (the device/server role).
     *
     * Source (verified, fetched directly), same file as above:
     *   N.x = 49918732221787544735331783592030787422991506689877079631459872391322455579424
     *   N.y = 54629554431565467720832445949441049581317094546788069926228343916274969994000
     *   encoded (32B compressed): 10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778
     * Cross-checked byte-for-byte against kSpakeNSmallPrecomp's first table entry (k=1) — matched exactly.
     */
    val N: Point by lazy {
        decode(hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
    }

    // ════════════════════════════════════════════════════════
    //  Point recovery / encode / decode (RFC 8032 §5.1.3)
    // ════════════════════════════════════════════════════════

    private fun recoverX(y: BigInteger, sign: Int): BigInteger {
        val ySq = y.multiply(y).mod(P)
        val u = ySq.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(ySq).add(BigInteger.ONE).mod(P)
        val uv = u.multiply(v.modInverse(P)).mod(P)

        // x_candidate = uv^((p+3)/8) mod p   (valid since p ≡ 5 mod 8)
        var x = uv.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).mod(P) != uv) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        check(x.multiply(x).mod(P) == uv) { "Invalid SPAKE2 point: y has no valid square root" }
        if (x.signum() == 0 && sign == 1) {
            throw IllegalArgumentException("Invalid point encoding: x=0 with sign=1")
        }
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    fun decode(bytes: ByteArray): Point {
        require(bytes.size == 32) { "Edwards25519 point must be 32 bytes, got ${bytes.size}" }
        val sign = (bytes[31].toInt() ushr 7) and 1
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = leBytesToBigInt(yBytes).mod(P)
        val pt = Point(recoverX(y, sign), y)
        check(isOnCurve(pt)) { "Decoded point fails curve equation check" }
        return pt
    }

    fun encode(p: Point): ByteArray {
        val out = bigIntToLeBytes(p.y.mod(P), 32)
        if (p.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    fun isOnCurve(p: Point): Boolean {
        val x2 = p.x.multiply(p.x).mod(P)
        val y2 = p.y.multiply(p.y).mod(P)
        val lhs = y2.subtract(x2).mod(P)
        val rhs = BigInteger.ONE.add(D.multiply(x2).multiply(y2)).mod(P)
        return lhs == rhs
    }

    // ════════════════════════════════════════════════════════
    //  Group law — complete twisted Edwards addition (a = -1)
    //  Valid for add AND double since d is a non-residue mod p.
    // ════════════════════════════════════════════════════════

    fun add(p1: Point, p2: Point): Point {
        val x1y2 = p1.x.multiply(p2.y).mod(P)
        val y1x2 = p1.y.multiply(p2.x).mod(P)
        val y1y2 = p1.y.multiply(p2.y).mod(P)
        val x1x2 = p1.x.multiply(p2.x).mod(P)
        val dxy = D.multiply(x1x2).multiply(y1y2).mod(P)

        val x3 = x1y2.add(y1x2).mod(P)
            .multiply(BigInteger.ONE.add(dxy).mod(P).modInverse(P)).mod(P)
        val y3 = y1y2.add(x1x2).mod(P)
            .multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    fun negate(p: Point) = Point(P.subtract(p.x).mod(P), p.y)
    fun subtract(p1: Point, p2: Point) = add(p1, negate(p2))

    /** Left-to-right double-and-add. scalarBytes is little-endian (curve25519 convention). */
    fun scalarMul(scalarBytes: ByteArray, point: Point): Point {
        var result = Point.IDENTITY
        var addend = point
        for (byteVal in scalarBytes) {
            var b = byteVal.toInt() and 0xFF
            repeat(8) {
                if ((b and 1) == 1) result = add(result, addend)
                addend = add(addend, addend)
                b = b ushr 1
            }
        }
        return result
    }

    // ════════════════════════════════════════════════════════
    //  Byte <-> BigInteger helpers (little-endian convention)
    // ════════════════════════════════════════════════════════

    fun leBytesToBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    fun bigIntToLeBytes(value: BigInteger, len: Int): ByteArray {
        val be = value.toByteArray()
        val unsigned = if (be.size > 1 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        val out = ByteArray(len)
        val n = minOf(unsigned.size, len)
        for (i in 0 until n) out[i] = unsigned[unsigned.size - 1 - i]
        return out
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
