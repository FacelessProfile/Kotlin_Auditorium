package com.example.kotlinroomdatabase.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.*
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object CellTowerHelper {

    data class CellTowerData(
        val type: String,
        val registered: Boolean,
        val mcc: String?,
        val mnc: String?,
        val cellId: Long?,
        val lacTac: Int?,
        val pciPsc: Int?,
        val signalDbm: Int?,
        val signalLevel: Int?
    ) {
        fun toJsonObject(): JSONObject = JSONObject().apply {
            put("type", type)
            put("registered", registered)
            mcc?.let { put("mcc", it) }
            mnc?.let { put("mnc", it) }
            cellId?.let { put("cell_id", it) }
            lacTac?.let { put("lac_tac", it) }
            pciPsc?.let { put("pci_psc", it) }
            signalDbm?.let { put("signal_dbm", it) }
            signalLevel?.let { put("level", it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun getCellTowers(context: Context): List<CellTowerData> {
        val list = mutableListOf<CellTowerData>()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return list
            val cellInfos = tm.allCellInfo ?: return list

            for (info in cellInfos) {
                when (info) {
                    is CellInfoLte -> {
                        val id = info.cellIdentity
                        val ss = info.cellSignalStrength
                        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.mccString else id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.mncString else id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                        val ci = id.ci.toLong().takeIf { it in 1..268435455 }
                        val tac = id.tac.takeIf { it in 1..65535 }
                        val pci = id.pci.takeIf { it in 0..503 }
                        list.add(
                            CellTowerData(
                                type = "LTE",
                                registered = info.isRegistered,
                                mcc = mcc,
                                mnc = mnc,
                                cellId = ci,
                                lacTac = tac,
                                pciPsc = pci,
                                signalDbm = ss.dbm.takeIf { it != Int.MAX_VALUE && it < 0 },
                                signalLevel = ss.level
                            )
                        )
                    }
                    is CellInfoGsm -> {
                        val id = info.cellIdentity
                        val ss = info.cellSignalStrength
                        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.mccString else id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.mncString else id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                        val cid = id.cid.toLong().takeIf { it in 1..65535 }
                        val lac = id.lac.takeIf { it in 1..65535 }
                        list.add(
                            CellTowerData(
                                type = "GSM",
                                registered = info.isRegistered,
                                mcc = mcc,
                                mnc = mnc,
                                cellId = cid,
                                lacTac = lac,
                                pciPsc = null,
                                signalDbm = ss.dbm.takeIf { it != Int.MAX_VALUE && it < 0 },
                                signalLevel = ss.level
                            )
                        )
                    }
                    is CellInfoWcdma -> {
                        val id = info.cellIdentity
                        val ss = info.cellSignalStrength
                        val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.mccString else id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
                        val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) id.mncString else id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
                        val cid = id.cid.toLong().takeIf { it in 1..268435455 }
                        val lac = id.lac.takeIf { it in 1..65535 }
                        val psc = id.psc.takeIf { it in 0..511 }
                        list.add(
                            CellTowerData(
                                type = "WCDMA",
                                registered = info.isRegistered,
                                mcc = mcc,
                                mnc = mnc,
                                cellId = cid,
                                lacTac = lac,
                                pciPsc = psc,
                                signalDbm = ss.dbm.takeIf { it != Int.MAX_VALUE && it < 0 },
                                signalLevel = ss.level
                            )
                        )
                    }
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                            val id = info.cellIdentity as? CellIdentityNr
                            val ss = info.cellSignalStrength as? CellSignalStrengthNr
                            val mcc = id?.mccString
                            val mnc = id?.mncString
                            val nci = id?.nci?.takeIf { it != CellInfo.UNAVAILABLE_LONG }
                            val tac = id?.tac?.takeIf { it != CellInfo.UNAVAILABLE }
                            val pci = id?.pci?.takeIf { it != CellInfo.UNAVAILABLE }
                            list.add(
                                CellTowerData(
                                    type = "NR_5G",
                                    registered = info.isRegistered,
                                    mcc = mcc,
                                    mnc = mnc,
                                    cellId = nci,
                                    lacTac = tac,
                                    pciPsc = pci,
                                    signalDbm = ss?.dbm?.takeIf { it != Int.MAX_VALUE && it < 0 },
                                    signalLevel = ss?.level
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CellTowerHelper", "Failed to retrieve cell tower info", e)
        }
        return list
    }

    fun getCellTowersAsJsonArray(context: Context): JSONArray {
        val array = JSONArray()
        for (tower in getCellTowers(context)) {
            array.put(tower.toJsonObject())
        }
        return array
    }

    fun getCellTowersSummary(context: Context): String {
        val towers = getCellTowers(context)
        if (towers.isEmpty()) return "Нет данных о сотовых вышках"
        val reg = towers.filter { it.registered }
        val primary = reg.firstOrNull() ?: towers.first()
        return "${primary.type} (MCC:${primary.mcc ?: "?"}, MNC:${primary.mnc ?: "?"}, CID:${primary.cellId ?: "?"}, LAC:${primary.lacTac ?: "?"}, ${primary.signalDbm ?: 0}dBm) [Всего вышек: ${towers.size}]"
    }
}
