/**
 * JNI-compatible shim for GoPro's Quik engine (libnative-lib.so).
 *
 * <p>The engine's JNI_OnLoad caches classes from this package by name and calls back into them
 * (asset resolution, MediaCodec encoding, listeners). Every class, member and native method here
 * keeps the exact name, signature and static-ness of GoPro Quik 13.43's own Kotlin wrapper, as
 * recovered from its bytecode. Do not rename anything in this package; the behavior around the
 * native calls is a clean re-implementation.
 */
package com.gopro.quikengine;
