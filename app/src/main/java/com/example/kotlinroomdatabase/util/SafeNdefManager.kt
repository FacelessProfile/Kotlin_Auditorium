package com.example.kotlinroomdatabase.util

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.util.Log
import java.nio.charset.Charset
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SafeNdefManager handles reading, writing, inspecting, and safely wiping
 * NFC Forum Type 2 tags (NTAG 216 / 888-byte chips) with hardware password
 * read-protection (PROT=1, AUTH0=0x04) and silicon UID verification.
 */
object SafeNdefManager {

    private const val TAG = "SafeNdefManager"

    data class ParsedPass(
        val rawPayload: String,
        val version: String,
        val studentId: Int,
        val tagUid: String,
        val issuedAt: Long,
        val signature: String,
        val isUidMatchingPhysical: Boolean,
        val isCloneDetected: Boolean = false
    )

    data class TagDiagnostics(
        val uid: String,
        val uidFormatted: String,
        val techList: List<String>,
        val isNdefSupported: Boolean,
        val isWritable: Boolean,
        val maxSize: Int,
        val currentSize: Int,
        val tagType: String,
        val ndefPayload: String?,
        val parsedPass: ParsedPass?,
        val hasNxpOriginalitySignature: Boolean = false,
        val isPasswordProtected: Boolean = false
    )

    /**
     * Normalizes a byte array UID to an uppercase hex string (e.g., "5327064E740001")
     */
    fun cleanUid(uidBytes: ByteArray?): String {
        if (uidBytes == null) return ""
        return uidBytes.joinToString("") { String.format(Locale.US, "%02X", it) }
    }

    /**
     * Normalizes any string UID to uppercase without separators
     */
    fun cleanUid(uidStr: String?): String {
        if (uidStr.isNullOrBlank()) return ""
        return uidStr.uppercase(Locale.US)
            .replace(":", "")
            .replace("-", "")
            .replace(" ", "")
            .trim()
    }

    /**
     * Formats a clean hex UID with colons (e.g., "53:27:06:4E:74:00:01")
     */
    fun formatUidWithColons(cleanUid: String): String {
        val clean = cleanUid(cleanUid)
        if (clean.isEmpty()) return ""
        return clean.chunked(2).joinToString(":")
    }

    /**
     * Parse EJ1 payload format: EJ1:<student_id>:<tag_uid>:<issued_at_timestamp>:<signature>
     */
    fun parsePassPayload(payload: String?, physicalUid: String = ""): ParsedPass? {
        if (payload.isNullOrBlank()) return null
        val trimmed = payload.trim()
        val parts = trimmed.split(":")
        if (parts.size == 5 && parts[0] == "EJ1") {
            val studentId = parts[1].toIntOrNull() ?: return null
            val tagUid = cleanUid(parts[2])
            val issuedAt = parts[3].toLongOrNull() ?: return null
            val signature = parts[4]
            val cleanPhysical = cleanUid(physicalUid)
            val isClone = cleanPhysical.isNotEmpty() && !cleanPhysical.equals(tagUid, ignoreCase = true)
            val isMatching = cleanPhysical.isNotEmpty() && cleanPhysical.equals(tagUid, ignoreCase = true)
            return ParsedPass(
                rawPayload = trimmed,
                version = "EJ1",
                studentId = studentId,
                tagUid = tagUid,
                issuedAt = issuedAt,
                signature = signature,
                isUidMatchingPhysical = if (cleanPhysical.isEmpty()) true else isMatching,
                isCloneDetected = isClone
            )
        }
        return null
    }

    /**
     * Derives deterministic 4-byte PWD and 2-byte PACK from hardware tag UID.
     */
    fun generateTagPassword(uid: String): Pair<ByteArray, ByteArray> {
        val clean = cleanUid(uid)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("EJOURNAL_NTAG_PWD_SEED_2026".toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("NTAG_PWD:$clean".toByteArray())
        val pwd = digest.copyOfRange(0, 4)
        val pack = digest.copyOfRange(4, 6)
        return Pair(pwd, pack)
    }

    /**
     * Checks if tag contains genuine NXP ECDSA Originality Signature (command 0x3C 0x00).
     */
    fun detectNxpOriginality(tag: Tag): Boolean {
        val nfca = NfcA.get(tag) ?: return false
        return try {
            if (!nfca.isConnected) nfca.connect()
            val resp = nfca.transceive(byteArrayOf(0x3C.toByte(), 0x00.toByte()))
            resp != null && resp.size == 32
        } catch (_: Exception) {
            false
        } finally {
            try { nfca.close() } catch (_: Exception) {}
        }
    }

    /**
     * Authenticates with NTAG Type 2 chip using PWD_AUTH command (0x1B).
     */
    fun authenticateTag(nfca: NfcA, uid: String): Boolean {
        val (pwd, pack) = generateTagPassword(uid)
        return try {
            val cmd = byteArrayOf(0x1B.toByte(), pwd[0], pwd[1], pwd[2], pwd[3])
            val resp = nfca.transceive(cmd)
            resp != null && resp.size >= 2 && resp[0] == pack[0] && resp[1] == pack[1]
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads the NDEF text/payload from a physical Tag safely.
     * If the tag has Read-Protection enabled, automatically authenticates with PWD.
     */
    fun readNdefPayload(tag: Tag): String? {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                val ndefMessage = ndef.ndefMessage
                if (ndefMessage != null) {
                    val str = extractStringFromMessage(ndefMessage)
                    if (!str.isNullOrBlank()) return str
                }
            } catch (e: Exception) {
                Log.d(TAG, "Standard NDEF read error (might be read-locked): ${e.message}")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        }

        // Fallback for Read-Protected NTAG (PROT=1): authenticate via NfcA and read
        val uid = cleanUid(tag.id)
        val nfca = NfcA.get(tag)
        if (nfca != null && uid.isNotEmpty()) {
            try {
                nfca.connect()
                val authed = authenticateTag(nfca, uid)
                if (authed) {
                    val readPages = ByteArray(128)
                    var offset = 0
                    for (p in 4..19 step 4) {
                        val chunk = nfca.transceive(byteArrayOf(0x30.toByte(), p.toByte()))
                        if (chunk != null && chunk.isNotEmpty()) {
                            val toCopy = Math.min(chunk.size, readPages.size - offset)
                            System.arraycopy(chunk, 0, readPages, offset, toCopy)
                            offset += toCopy
                        }
                    }
                    val rawStr = String(readPages, Charset.forName("UTF-8"))
                    val ejIdx = rawStr.indexOf("EJ1:")
                    if (ejIdx != -1) {
                        val sub = rawStr.substring(ejIdx)
                        val endIdx = sub.indexOfAny(charArrayOf('\u0000', '\u00fe', '\r', '\n'))
                        return if (endIdx != -1) sub.substring(0, endIdx) else sub
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Authenticated NDEF read failed: ${e.message}")
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Full inspection of tag hardware, NDEF memory, and student cryptogram.
     */
    fun inspectTag(tag: Tag): TagDiagnostics {
        val uid = cleanUid(tag.id)
        val uidFormatted = formatUidWithColons(uid)
        val techList = tag.techList.map { it.substringAfterLast(".") }
        var isNdef = false
        var isWritable = false
        var maxSize = 0
        var currentSize = 0
        var tagType = "Неизвестный чип"
        var ndefPayload: String? = null

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            isNdef = true
            isWritable = ndef.isWritable
            maxSize = ndef.maxSize
            tagType = ndef.type ?: "NFC Forum Type 2"
            try {
                ndef.connect()
                val msg = ndef.ndefMessage
                if (msg != null) {
                    currentSize = msg.byteArrayLength
                    ndefPayload = extractStringFromMessage(msg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ndef inspect connect failed: ${e.message}")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        } else {
            val formatable = NdefFormatable.get(tag)
            if (formatable != null) {
                isWritable = true
                tagType = "Type 2 Tag (Чистый / NdefFormatable)"
            }
        }

        if (maxSize in 840..900 || uid.startsWith("53")) {
            tagType = "NTAG 216 Compatible (888 B / Type 2)"
        }

        val hasNxpOriginality = detectNxpOriginality(tag)
        val isPwdProtected = ndefPayload == null && NfcA.get(tag) != null

        val parsedPass = parsePassPayload(ndefPayload, uid)

        return TagDiagnostics(
            uid = uid,
            uidFormatted = uidFormatted,
            techList = techList,
            isNdefSupported = isNdef || NdefFormatable.get(tag) != null,
            isWritable = isWritable,
            maxSize = if (maxSize > 0) maxSize else 868,
            currentSize = currentSize,
            tagType = tagType,
            ndefPayload = ndefPayload,
            parsedPass = parsedPass,
            hasNxpOriginalitySignature = hasNxpOriginality,
            isPasswordProtected = isPwdProtected
        )
    }

    /**
     * Applies hardware Read-Protection on NTAG 213/215/216 chips:
     * - Derives 32-bit PWD and 16-bit PACK from hardware UID
     * - Sets PROT = 1 (bit 7 in ACCESS page) so password is required for BOTH read and write
     * - Sets AUTH0 = 0x04 (protects all pages from page 4 onwards)
     * Result: NFC Tools and third-party devices cannot read the memory without password!
     */
    fun applyReadProtection(tag: Tag, uid: String): Result<Unit> {
        val nfca = NfcA.get(tag) ?: return Result.failure(IllegalStateException("Чип не поддерживает NfcA"))
        val clean = cleanUid(uid)
        val (pwd, pack) = generateTagPassword(clean)

        return try {
            if (!nfca.isConnected) nfca.connect()

            var auth0Page = 0xE3
            var accessPage = 0xE4
            var pwdPage = 0xE5
            var packPage = 0xE6

            val ndef = Ndef.get(tag)
            val maxSize = ndef?.maxSize ?: 868
            when {
                maxSize in 100..200 -> {
                    auth0Page = 0x29
                    accessPage = 0x2A
                    pwdPage = 0x2B
                    packPage = 0x2C
                }
                maxSize in 450..600 -> {
                    auth0Page = 0x83
                    accessPage = 0x84
                    pwdPage = 0x85
                    packPage = 0x86
                }
            }

            // In case tag was already authenticated or protected, try auth first
            authenticateTag(nfca, clean)

            // 1. Write PWD (4 bytes)
            val writePwd = byteArrayOf(0xA2.toByte(), pwdPage.toByte(), pwd[0], pwd[1], pwd[2], pwd[3])
            nfca.transceive(writePwd)

            // 2. Write PACK (2 bytes + 2 zero bytes)
            val writePack = byteArrayOf(0xA2.toByte(), packPage.toByte(), pack[0], pack[1], 0x00, 0x00)
            nfca.transceive(writePack)

            // 3. Write ACCESS with PROT=1 (bit 7 = 1 -> 0x80)
            val writeAccess = byteArrayOf(0xA2.toByte(), accessPage.toByte(), 0x80.toByte(), 0x00, 0x00, 0x00)
            nfca.transceive(writeAccess)

            // 4. Write AUTH0 = 0x04 (protect from page 4 to end)
            val writeAuth0 = byteArrayOf(0xA2.toByte(), auth0Page.toByte(), 0x00, 0x00, 0x00, 0x04.toByte())
            nfca.transceive(writeAuth0)

            Log.i(TAG, "Successfully applied hardware Read-Protection (PROT=1, AUTH0=0x04) to tag $clean")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply read protection to tag $clean: ${e.message}")
            Result.failure(e)
        } finally {
            try { nfca.close() } catch (_: Exception) {}
        }
    }

    /**
     * Unlocks and safely wipes a password-protected tag:
     * - Authenticates with PWD
     * - Resets AUTH0 = 0xFF (disables password protection)
     * - Wipes NDEF back to empty container
     */
    fun removeProtectionAndWipe(tag: Tag, uid: String): Result<Unit> {
        val clean = cleanUid(uid)
        val nfca = NfcA.get(tag)
        if (nfca != null) {
            try {
                if (!nfca.isConnected) nfca.connect()
                authenticateTag(nfca, clean)

                var auth0Page = 0xE3
                val ndef = Ndef.get(tag)
                val maxSize = ndef?.maxSize ?: 868
                when {
                    maxSize in 100..200 -> auth0Page = 0x29
                    maxSize in 450..600 -> auth0Page = 0x83
                }

                // Reset AUTH0 to 0xFF (unprotected)
                val disableAuth0 = byteArrayOf(0xA2.toByte(), auth0Page.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte())
                nfca.transceive(disableAuth0)
            } catch (e: Exception) {
                Log.w(TAG, "Reset AUTH0 attempt: ${e.message}")
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }
        return wipeTagToEmpty(tag)
    }

    /**
     * Safely writes an NDEF cryptogram pass to the tag.
     * ZERO-BRICK GUARANTEE: Never modifies OTP lock bits or locks the tag.
     */
    fun writeSafePass(tag: Tag, payload: String): Result<Unit> {
        return try {
            val cleanPayload = payload.trim()
            val textRecord = NdefRecord.createTextRecord("en", cleanPayload)
            val ndefMessage = NdefMessage(arrayOf(textRecord))

            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) {
                    return Result.failure(IllegalStateException("Метка защищена от записи (Read-Only)"))
                }
                if (ndef.maxSize < ndefMessage.byteArrayLength) {
                    return Result.failure(IllegalStateException("Размер данных (${ndefMessage.byteArrayLength} B) превышает память метки (${ndef.maxSize} B)"))
                }
                ndef.writeNdefMessage(ndefMessage)
                ndef.close()
                Log.i(TAG, "Successfully written NDEF pass: $cleanPayload")
                Result.success(Unit)
            } else {
                val formatable = NdefFormatable.get(tag)
                    ?: return Result.failure(IllegalStateException("Метка не поддерживает NDEF или форматирование"))
                formatable.connect()
                // IMPORTANT: format(ndefMessage) formats without making it read-only! Never call formatReadOnly!
                formatable.format(ndefMessage)
                formatable.close()
                Log.i(TAG, "Successfully formatted & written NDEF pass: $cleanPayload")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeSafePass error", e)
            Result.failure(e)
        }
    }

    /**
     * Safely wipes student pass data from the tag back to clean NDEF.
     * ZERO-BRICK GUARANTEE: Writes empty NDEF text message, leaving the chip re-writable.
     */
    fun wipeTagToEmpty(tag: Tag): Result<Unit> {
        return try {
            val emptyRecord = NdefRecord.createTextRecord("en", "")
            val emptyMessage = NdefMessage(arrayOf(emptyRecord))

            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) {
                    return Result.failure(IllegalStateException("Метка защищена от записи"))
                }
                ndef.writeNdefMessage(emptyMessage)
                ndef.close()
                Log.i(TAG, "Successfully wiped tag to empty NDEF")
                Result.success(Unit)
            } else {
                val formatable = NdefFormatable.get(tag)
                    ?: return Result.failure(IllegalStateException("Метка не поддерживает NDEF"))
                formatable.connect()
                formatable.format(emptyMessage)
                formatable.close()
                Log.i(TAG, "Successfully formatted clean empty tag")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "wipeTagToEmpty error", e)
            Result.failure(e)
        }
    }

    private fun extractStringFromMessage(ndefMessage: NdefMessage): String? {
        for (record in ndefMessage.records) {
            // Check Text record
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                val payload = record.payload ?: continue
                if (payload.isNotEmpty()) {
                    val statusByte = payload[0].toInt()
                    val languageCodeLength = statusByte and 0x3F
                    val isUtf16 = (statusByte and 0x80) != 0
                    val charset = if (isUtf16) Charset.forName("UTF-16") else Charset.forName("UTF-8")
                    val textOffset = 1 + languageCodeLength
                    if (payload.size >= textOffset) {
                        return String(payload, textOffset, payload.size - textOffset, charset).trim()
                    }
                }
            }
            // Check MIME / External record
            if (record.tnf == NdefRecord.TNF_MIME_MEDIA || record.tnf == NdefRecord.TNF_EXTERNAL_TYPE) {
                val payload = record.payload ?: continue
                val str = String(payload, Charset.forName("UTF-8")).trim()
                if (str.isNotEmpty()) return str
            }
            // Raw fallback
            val raw = record.payload
            if (raw != null && raw.isNotEmpty()) {
                val candidate = String(raw, Charset.forName("UTF-8")).trim()
                if (candidate.startsWith("EJ1:") || candidate.startsWith("STUDENT:")) {
                    return candidate
                }
            }
        }
        return null
    }
}
