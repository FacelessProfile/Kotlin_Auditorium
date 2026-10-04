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
     *
     * SECURITY NOTE (AUD-06):
     * This derivation is used strictly for local operational write-protection to prevent
     * accidental corruption of NDEF memory by third-party NFC apps. It is NOT an authentication
     * credential for identity proof. Identity validation is strictly enforced on the server
     * via cryptographic EJ1 signatures.
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
     * Checks if tag responds with 32 bytes to NXP Originality Signature command (0x3C 0x00).
     *
     * SECURITY NOTE (AUD-07):
     * This method confirms the physical chip returns an originality payload (32 bytes).
     * Full ECDSA cryptographic verification against NXP root public keys is performed on
     * the backend server during smart validation.
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

    data class NtagConfig(
        val tagType: String,
        val totalPages: Int,
        val auth0Page: Int,
        val accessPage: Int,
        val pwdPage: Int,
        val packPage: Int,
        val ccSizeByte: Byte
    )

    val NTAG_213 = NtagConfig(
        tagType = "NTAG 213 (144 B)",
        totalPages = 45,
        auth0Page = 0x29,
        accessPage = 0x2A,
        pwdPage = 0x2B,
        packPage = 0x2C,
        ccSizeByte = 0x12.toByte()
    )

    val NTAG_215 = NtagConfig(
        tagType = "NTAG 215 (504 B)",
        totalPages = 135,
        auth0Page = 0x83,
        accessPage = 0x84,
        pwdPage = 0x85,
        packPage = 0x86,
        ccSizeByte = 0x3E.toByte()
    )

    val NTAG_216 = NtagConfig(
        tagType = "NTAG 216 (888 B)",
        totalPages = 231,
        auth0Page = 0xE3,
        accessPage = 0xE4,
        pwdPage = 0xE5,
        packPage = 0xE6,
        ccSizeByte = 0x6D.toByte()
    )

    /**
     * Detects NTAG 213/215/216 model via GET_VERSION (0x60) or Capability Container probing.
     */
    fun getTagConfig(nfca: NfcA): NtagConfig {
        // 1. Try NXP GET_VERSION command (0x60)
        try {
            val ver = nfca.transceive(byteArrayOf(0x60.toByte()))
            if (ver != null && ver.size >= 8) {
                when (ver[6].toInt() and 0xFF) {
                    0x0F -> return NTAG_213
                    0x11 -> return NTAG_215
                    0x13 -> return NTAG_216
                }
            }
        } catch (_: Exception) {}

        // 2. Try reading Capability Container (Page 3)
        try {
            val cc = nfca.transceive(byteArrayOf(0x30.toByte(), 0x03.toByte()))
            if (cc != null && cc.size >= 4 && cc[0] == 0xE1.toByte()) {
                val size = cc[2].toInt() and 0xFF
                when {
                    size in 0x10..0x15 -> return NTAG_213
                    size in 0x30..0x45 -> return NTAG_215
                    size in 0x60..0x75 -> return NTAG_216
                }
            }
        } catch (_: Exception) {}

        // 3. Project default is NTAG 216
        return NTAG_216
    }

    /**
     * Authenticates with NTAG Type 2 chip using PWD_AUTH command (0x1B).
     * Supports both dynamic UID password and factory default (0xFF 0xFF 0xFF 0xFF).
     */
    fun authenticateTag(nfca: NfcA, uid: String): Boolean {
        val (pwd, pack) = generateTagPassword(uid)
        try {
            val cmd = byteArrayOf(0x1B.toByte(), pwd[0], pwd[1], pwd[2], pwd[3])
            val resp = nfca.transceive(cmd)
            if (resp != null && resp.size >= 2 && resp[0] == pack[0] && resp[1] == pack[1]) {
                return true
            }
        } catch (_: Exception) {}

        try {
            val cmdDefault = byteArrayOf(0x1B.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            val respDefault = nfca.transceive(cmdDefault)
            if (respDefault != null && respDefault.size >= 2) {
                return true
            }
        } catch (_: Exception) {}

        return false
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
                if (!nfca.isConnected) nfca.connect()
                val authed = authenticateTag(nfca, uid)
                if (authed) {
                    val readPages = ByteArray(256)
                    var offset = 0
                    // Read pages 4..36 (each 0x30 command reads 4 pages = 16 bytes, total 9*16 = 144 bytes)
                    for (p in 4..36 step 4) {
                        try {
                            val chunk = nfca.transceive(byteArrayOf(0x30.toByte(), p.toByte()))
                            if (chunk != null && chunk.isNotEmpty()) {
                                val toCopy = Math.min(chunk.size, readPages.size - offset)
                                System.arraycopy(chunk, 0, readPages, offset, toCopy)
                                offset += toCopy
                            }
                        } catch (_: Exception) {
                            break
                        }
                    }

                    // 1. Try parsing NDEF TLV directly if present at page 4
                    if (offset >= 4 && readPages[0] == 0x03.toByte()) {
                        val tlvLen = readPages[1].toInt() and 0xFF
                        if (tlvLen in 1..(offset - 2)) {
                            try {
                                val msgBytes = readPages.copyOfRange(2, 2 + tlvLen)
                                val msg = NdefMessage(msgBytes)
                                val extracted = extractStringFromMessage(msg)
                                if (!extracted.isNullOrBlank()) return extracted
                            } catch (_: Exception) {}
                        }
                    }

                    // 2. Fallback text scanning for EJ1
                    val rawStr = String(readPages, 0, offset, Charset.forName("UTF-8"))
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

        // If NDEF payload wasn't retrieved via standard Ndef, try authenticated read
        if (ndefPayload.isNullOrBlank()) {
            val authRead = readNdefPayload(tag)
            if (!authRead.isNullOrBlank()) {
                ndefPayload = authRead
                currentSize = authRead.toByteArray(Charset.forName("UTF-8")).size
            }
        }

        val hasNfcA = NfcA.get(tag) != null
        if (hasNfcA) {
            // Chip is an NFC Forum Type 2 tag (NTAG 213/215/216)
            isNdef = true
            isWritable = true
            if (maxSize == 0) maxSize = 888
        }

        val hasNxpOriginality = detectNxpOriginality(tag)
        val isPwdProtected = hasNfcA && (ndef == null || ndefPayload?.startsWith("EJ1:") == true)

        if (maxSize in 840..900 || uid.startsWith("53") || uid.length == 14) {
            tagType = if (isPwdProtected && ndef == null) "NTAG 216 (Защищён паролем)" else "NTAG 216 Compatible (888 B)"
        }

        val parsedPass = parsePassPayload(ndefPayload, uid)

        return TagDiagnostics(
            uid = uid,
            uidFormatted = uidFormatted,
            techList = techList,
            isNdefSupported = isNdef || NdefFormatable.get(tag) != null,
            isWritable = isWritable,
            maxSize = if (maxSize > 0) maxSize else 888,
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
            val config = getTagConfig(nfca)

            // In case tag was already authenticated or protected, try auth first
            authenticateTag(nfca, clean)

            // 1. Write PWD (4 bytes)
            val writePwd = byteArrayOf(0xA2.toByte(), config.pwdPage.toByte(), pwd[0], pwd[1], pwd[2], pwd[3])
            nfca.transceive(writePwd)

            // 2. Write PACK (2 bytes + 2 zero bytes)
            val writePack = byteArrayOf(0xA2.toByte(), config.packPage.toByte(), pack[0], pack[1], 0x00, 0x00)
            nfca.transceive(writePack)

            // 3. Write ACCESS with PROT=1 (bit 7 = 1 -> 0x80)
            val writeAccess = byteArrayOf(0xA2.toByte(), config.accessPage.toByte(), 0x80.toByte(), 0x00, 0x00, 0x00)
            nfca.transceive(writeAccess)

            // 4. Write AUTH0 = 0x04 (protect from page 4 to end)
            val writeAuth0 = byteArrayOf(0xA2.toByte(), config.auth0Page.toByte(), 0x00, 0x00, 0x00, 0x04.toByte())
            nfca.transceive(writeAuth0)

            Log.i(TAG, "Successfully applied hardware Read-Protection (PROT=1, AUTH0=0x04) to tag $clean (${config.tagType})")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply read protection to tag $clean: ${e.message}")
            Result.failure(e)
        } finally {
            try { nfca.close() } catch (_: Exception) {}
        }
    }

    /**
     * Completely resets and formats NTAG chip over low-level NfcA:
     * - Authenticates with password (or default)
     * - Disables password protection (AUTH0 = 0xFF)
     * - Resets ACCESS to 0x00 (unlocked, PROT=0, CFG_LOCKED=0)
     * - Resets PWD/PACK to defaults
     * - Writes valid Capability Container (CC) to Page 3
     * - Writes standard empty NDEF TLV (03 00 FE 00) to Page 4
     * - Wipes student data remnants on pages 5..16
     * ZERO-BRICK GUARANTEE: Never touches lock bits, 100% recoverable.
     */
    fun directNfcAResetAndFormat(nfca: NfcA, uid: String): Result<Unit> {
        return try {
            if (!nfca.isConnected) nfca.connect()
            val clean = cleanUid(uid)
            val config = getTagConfig(nfca)

            // 1. Authenticate with chip password (or default)
            authenticateTag(nfca, clean)

            // 2. Disable password protection (AUTH0 = 0xFF: no pages protected)
            val disableAuth0 = byteArrayOf(0xA2.toByte(), config.auth0Page.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte())
            try { nfca.transceive(disableAuth0) } catch (e: Exception) { Log.w(TAG, "Write AUTH0: ${e.message}") }

            // 3. Reset ACCESS to 0x00 (PROT=0, CFG_LOCKED=0, AUTHLIM=000)
            val resetAccess = byteArrayOf(0xA2.toByte(), config.accessPage.toByte(), 0x00, 0x00, 0x00, 0x00)
            try { nfca.transceive(resetAccess) } catch (e: Exception) { Log.w(TAG, "Write ACCESS: ${e.message}") }

            // 4. Reset PWD to default 0xFF 0xFF 0xFF 0xFF
            val defaultPwd = byteArrayOf(0xA2.toByte(), config.pwdPage.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            try { nfca.transceive(defaultPwd) } catch (e: Exception) { Log.w(TAG, "Write PWD: ${e.message}") }

            // 5. Reset PACK to default 0x00 0x00 0x00 0x00
            val defaultPack = byteArrayOf(0xA2.toByte(), config.packPage.toByte(), 0x00, 0x00, 0x00, 0x00)
            try { nfca.transceive(defaultPack) } catch (e: Exception) { Log.w(TAG, "Write PACK: ${e.message}") }

            // 6. Write standard Capability Container (CC) at Page 3
            val writeCc = byteArrayOf(0xA2.toByte(), 0x03.toByte(), 0xE1.toByte(), 0x10.toByte(), config.ccSizeByte, 0x00.toByte())
            try { nfca.transceive(writeCc) } catch (e: Exception) { Log.w(TAG, "Write CC: ${e.message}") }

            // 7. Write standard Empty NDEF TLV at Page 4: 03 (NDEF Message) 00 (Length 0) FE (Terminator) 00
            val emptyNdefTlv = byteArrayOf(0xA2.toByte(), 0x04.toByte(), 0x03.toByte(), 0x00.toByte(), 0xFE.toByte(), 0x00.toByte())
            nfca.transceive(emptyNdefTlv)

            // 8. Zero out pages 5..16 to wipe any remnants of student cryptogram
            val zeroPage = byteArrayOf(0x00, 0x00, 0x00, 0x00)
            for (p in 5..16) {
                if (p < config.auth0Page) {
                    try {
                        nfca.transceive(byteArrayOf(0xA2.toByte(), p.toByte(), zeroPage[0], zeroPage[1], zeroPage[2], zeroPage[3]))
                    } catch (_: Exception) {}
                }
            }

            Log.i(TAG, "Successfully wiped and restored tag $clean (${config.tagType}) to factory-clean unlocked NDEF")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "directNfcAResetAndFormat failed", e)
            Result.failure(e)
        }
    }

    /**
     * Unlocks and safely wipes a tag:
     * - Removes hardware read protection via NfcA
     * - Restores standard NDEF empty container
     */
    fun removeProtectionAndWipe(tag: Tag, uid: String): Result<Unit> {
        val clean = cleanUid(uid)
        val nfca = NfcA.get(tag)
        if (nfca != null) {
            return try {
                directNfcAResetAndFormat(nfca, clean)
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }
        return wipeTagToEmpty(tag)
    }

    /**
     * Writes NDEF Text payload directly to user pages starting at page 4 via NfcA.
     */
    fun writeNdefDirectNfcA(nfca: NfcA, uid: String, payload: String): Result<Unit> {
        return try {
            if (!nfca.isConnected) nfca.connect()
            val clean = cleanUid(uid)
            val config = getTagConfig(nfca)

            // Unlock if currently protected
            authenticateTag(nfca, clean)

            // Temporarily disable AUTH0 to allow writing user pages
            val disableAuth0 = byteArrayOf(0xA2.toByte(), config.auth0Page.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte())
            try { nfca.transceive(disableAuth0) } catch (_: Exception) {}

            // Ensure Page 3 Capability Container is valid NDEF CC
            val writeCc = byteArrayOf(0xA2.toByte(), 0x03.toByte(), 0xE1.toByte(), 0x10.toByte(), config.ccSizeByte, 0x00.toByte())
            try { nfca.transceive(writeCc) } catch (_: Exception) {}

            // Build NDEF Text Record
            val cleanPayload = payload.trim()
            val textRecord = NdefRecord.createTextRecord("en", cleanPayload)
            val ndefMessage = NdefMessage(arrayOf(textRecord))
            val msgBytes = ndefMessage.toByteArray()

            // Construct TLV: 0x03 + length + msgBytes + 0xFE (Terminator)
            val tlv = if (msgBytes.size < 255) {
                byteArrayOf(0x03.toByte(), msgBytes.size.toByte()) + msgBytes + byteArrayOf(0xFE.toByte())
            } else {
                byteArrayOf(0x03.toByte(), 0xFF.toByte(), (msgBytes.size shr 8).toByte(), (msgBytes.size and 0xFF).toByte()) + msgBytes + byteArrayOf(0xFE.toByte())
            }

            // Pad TLV to multiple of 4 bytes (page size)
            val rem = tlv.size % 4
            val paddedTlv = if (rem != 0) tlv + ByteArray(4 - rem) else tlv

            var page = 4
            for (i in paddedTlv.indices step 4) {
                val cmd = byteArrayOf(
                    0xA2.toByte(),
                    page.toByte(),
                    paddedTlv[i],
                    paddedTlv[i + 1],
                    paddedTlv[i + 2],
                    paddedTlv[i + 3]
                )
                nfca.transceive(cmd)
                page++
            }
            // Clear subsequent page
            try {
                nfca.transceive(byteArrayOf(0xA2.toByte(), page.toByte(), 0x00, 0x00, 0x00, 0x00))
            } catch (_: Exception) {}

            Log.i(TAG, "Successfully written NDEF payload via NfcA: $cleanPayload")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "writeNdefDirectNfcA failed", e)
            Result.failure(e)
        }
    }

    /**
     * Safely writes an NDEF cryptogram pass to the tag.
     * Uses standard Ndef, or direct NfcA if tag is read-protected or fresh.
     * ZERO-BRICK GUARANTEE: Never modifies OTP lock bits or locks the tag.
     */
    fun writeSafePass(tag: Tag, payload: String): Result<Unit> {
        val cleanPayload = payload.trim()
        val textRecord = NdefRecord.createTextRecord("en", cleanPayload)
        val ndefMessage = NdefMessage(arrayOf(textRecord))

        // 1. Try standard Ndef if available and writable
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (ndef.isWritable && ndef.maxSize >= ndefMessage.byteArrayLength) {
                    ndef.writeNdefMessage(ndefMessage)
                    ndef.close()
                    Log.i(TAG, "Successfully written NDEF pass via standard Ndef: $cleanPayload")
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Standard Ndef write failed (${e.message}), trying NfcA fallback...")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        }

        // 2. Direct NfcA fallback (handles password-protected or unformatted chips)
        val nfca = NfcA.get(tag)
        if (nfca != null) {
            try {
                val res = writeNdefDirectNfcA(nfca, cleanUid(tag.id), cleanPayload)
                if (res.isSuccess) return res
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }

        // 3. Fallback to NdefFormatable if present
        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return try {
                formatable.connect()
                formatable.format(ndefMessage)
                formatable.close()
                Log.i(TAG, "Successfully formatted & written NDEF pass: $cleanPayload")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try { formatable.close() } catch (_: Exception) {}
            }
        }

        return Result.failure(IllegalStateException("Метка не поддерживает NDEF или запись NfcA"))
    }

    /**
     * Safely wipes student pass data from the tag back to clean NDEF.
     * ZERO-BRICK GUARANTEE: Writes empty NDEF, leaving the chip re-writable.
     */
    fun wipeTagToEmpty(tag: Tag): Result<Unit> {
        val nfca = NfcA.get(tag)
        // If Ndef is null or NfcA is present on a Type 2 tag, direct NfcA wipe is fastest and most reliable
        if (nfca != null && Ndef.get(tag) == null) {
            return try {
                directNfcAResetAndFormat(nfca, cleanUid(tag.id))
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }

        val emptyRecord = NdefRecord.createTextRecord("en", "")
        val emptyMessage = NdefMessage(arrayOf(emptyRecord))

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (ndef.isWritable) {
                    ndef.writeNdefMessage(emptyMessage)
                    ndef.close()
                    Log.i(TAG, "Successfully wiped tag to empty NDEF")
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ndef wipe failed, trying direct NfcA wipe...")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        }

        if (nfca != null) {
            return try {
                directNfcAResetAndFormat(nfca, cleanUid(tag.id))
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return try {
                formatable.connect()
                formatable.format(emptyMessage)
                formatable.close()
                Log.i(TAG, "Successfully formatted clean empty tag")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try { formatable.close() } catch (_: Exception) {}
            }
        }

        return Result.failure(IllegalStateException("Метка не поддерживает NDEF"))
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
