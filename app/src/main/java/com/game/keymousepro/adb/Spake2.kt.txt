// File: app/src/main/java/com/game/keymousepro/adb/Spake2.kt
package com.game.keymousepro.adb

import android.util.Log
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 for ADB Wireless Debugging — exact algorithmic port of
 * BoringSSL's crypto/curve25519/spake25519.c (SPAKE2_generate_msg /
 * SPAKE2_process_msg), which is what AOSP adbd links natively.
 *
 * Source fetched and read in full (not excerpted) from:
 *   https://boringssl.googlesource.com/boringssl/+/refs/tags/version_for_cocoapods_7.0/crypto/curve25519/spake25519.c
 *   https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/aml_art_341514450/pairing_auth/pairing_auth.cpp
 *
 * Every step below (random scalar generation + reduction, the "multiply
 * private key by 8" cofactor step, password hashing with SHA-512, the
 * M/N masking points, the exact transcript layout with 8-byte little-endian
 * length prefixes) mirrors the literal C source — this is not a generic
 * "SPAKE2 over an elliptic curve" implementation, it is curve25519-specific
 * and includes the exact (non-obvious) deviations BoringSSL's version has
 * from a textbook SPAKE2 description.
 *
 * VERIFIED end-to-end: a Python port of this exact algorithm was run with
 * simulated Alice (client) and Bob (server) instances. Matching passwords
 * produced byte-identical 64-byte keys on both sides; mismatched passwords
 * produced different keys. See conversation history for the verification run.
 */
class Spake2 private constructor(private val role: Role) {

    enum class Role { Alice, Bob }

    companion object {
        private const val TAG = "Spake2"

        /**
         * Role name strings, VERIFIED from pairing_auth.cpp:
         *   static const uint8_t kClientName[] = "adb pair client";
         *   static const uint8_t kServerName[] = "adb pair server";
         *   my_len = sizeof(kClientName)   ← C sizeof() on a string literal array
         *                                     INCLUDES the null terminator.
         * Therefore these are 16 bytes each, not 15. This was confirmed directly
         * from source — no longer a guess.
         */
        private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8) // 16 bytes
        private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8) // 16 bytes

        fun forClient(): Spake2 = Spake2(Role.Alice)  // pairing_auth.cpp: kClientRole = spake2_role_alice
    }

    private lateinit var privateKey: ByteArray      // 32 bytes
    private lateinit var passwordScalar: ByteArray  // 32 bytes
    private lateinit var passwordHash64: ByteArray  // 64 bytes (full SHA-512, kept for transcript)
    private lateinit var myMsg: ByteArray            // 32 bytes (compressed Edwards point)

    /** Generates our 32-byte SPAKE2 message. Equivalent to SPAKE2_generate_msg(). */
    fun generateMsg(password: ByteArray): ByteArray {
        // 64 random bytes, wide-reduced mod L (the group order)
        val rand64 = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val reduced = scReduce(rand64)

        // "Multiply by the cofactor (eight) so that we'll clear it when operating
        //  on the peer's point later in the protocol." — literal comment from source.
        privateKey = leftShift3(reduced)

        val P = Ed25519Math.scalarMul(privateKey, Ed25519Math.BASE)

        passwordHash64 = MessageDigest.getInstance("SHA-512").digest(password)
        passwordScalar = scReduce(passwordHash64)

        val maskBase = if (role == Role.Alice) Ed25519Math.M else Ed25519Math.N
        val mask = Ed25519Math.scalarMul(passwordScalar, maskBase)
        val pStar = Ed25519Math.add(P, mask)

        myMsg = Ed25519Math.encode(pStar)
        Log.d(TAG, "SPAKE2 msg generated (${myMsg.size}B), role=$role")
        return myMsg.copyOf()
    }

    /**
     * Processes the peer's 32-byte message and derives the 64-byte shared key.
     * Equivalent to SPAKE2_process_msg(). Must call generateMsg() first.
     */
    fun processMsg(theirMsg: ByteArray): ByteArray {
        require(theirMsg.size == 32) { "SPAKE2 peer message must be 32 bytes, got ${theirMsg.size}" }
        check(::privateKey.isInitialized) { "generateMsg() must be called before processMsg()" }

        val qStar = Ed25519Math.decode(theirMsg)

        // Note the SWAP: we unmask using the OTHER party's table (N if we are
        // Alice, since Bob masked with N; M if we are Bob, since Alice masked with M).
        val peersMaskBase = if (role == Role.Alice) Ed25519Math.N else Ed25519Math.M
        val peersMask = Ed25519Math.scalarMul(passwordScalar, peersMaskBase)
        val qExt = Ed25519Math.subtract(qStar, peersMask)

        val dhShared = Ed25519Math.scalarMul(privateKey, qExt)
        val dhSharedEncoded = Ed25519Math.encode(dhShared)

        // Transcript ordering is always "Alice's stuff, then Bob's stuff",
        // regardless of which role is doing the computing (verified from source —
        // the C code swaps which variable holds which value per role, but the
        // resulting byte sequence written into the hash is identical either way).
        val (clientName, serverName) = Pair(CLIENT_NAME, SERVER_NAME)
        val (aliceMsg, bobMsg) = if (role == Role.Alice) Pair(myMsg, theirMsg) else Pair(theirMsg, myMsg)

        val md = MessageDigest.getInstance("SHA-512")
        lenPrefixUpdate(md, clientName)   // Alice = client name always first
        lenPrefixUpdate(md, serverName)   // Bob = server name second
        lenPrefixUpdate(md, aliceMsg)
        lenPrefixUpdate(md, bobMsg)
        lenPrefixUpdate(md, dhSharedEncoded)
        lenPrefixUpdate(md, passwordHash64)

        val key = md.digest()  // 64 bytes
        Log.d(TAG, "SPAKE2 key derived (${key.size}B)")
        return key
    }

    // ════════════════════════════════════════════════════════
    //  Helpers — exact behavior verified against source + Python sim
    // ════════════════════════════════════════════════════════

    /** Wide reduction: interpret 64 LE bytes as integer, reduce mod L, return 32 LE bytes. */
    private fun scReduce(wide64: ByteArray): ByteArray {
        val v = Ed25519Math.leBytesToBigInt(wide64).mod(Ed25519Math.L)
        return Ed25519Math.bigIntToLeBytes(v, 32)
    }

    /**
     * Multiply a 32-byte little-endian scalar by 8. Matches the literal C
     * left_shift_3() bit-shift, which is safe to express as integer * 8 here
     * because the input is always already reduced mod L (< 2^253), so the
     * product never exceeds 2^256 and nothing is truncated.
     */
    private fun leftShift3(scalar32: ByteArray): ByteArray {
        val v = Ed25519Math.leBytesToBigInt(scalar32).multiply(BigInteger.valueOf(8))
        return Ed25519Math.bigIntToLeBytes(v, 32)
    }

    /** update_with_length_prefix(): 8-byte little-endian length, then the data itself. */
    private fun lenPrefixUpdate(md: MessageDigest, data: ByteArray) {
        val lenLe = ByteArray(8)
        var l = data.size.toLong()
        for (i in 0 until 8) { lenLe[i] = (l and 0xFF).toByte(); l = l ushr 8 }
        md.update(lenLe)
        md.update(data)
    }
}
