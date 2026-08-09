package com.atnip.seizuretracker.util

/** Generates short, human-typeable join codes (avoids visually ambiguous chars like 0/O, 1/I). */
object HouseholdCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(length: Int = 6): String =
        (1..length).map { ALPHABET.random() }.joinToString("")

    fun normalize(input: String): String = input.trim().uppercase()
}
