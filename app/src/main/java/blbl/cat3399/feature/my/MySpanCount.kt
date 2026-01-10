package blbl.cat3399.feature.my

import android.content.res.Resources
import blbl.cat3399.core.net.BiliClient

fun spanCountForWidth(resources: Resources): Int {
    val prefs = BiliClient.prefs
    val override = prefs.gridSpanCount
    if (override > 0) return override.coerceIn(1, 6)
    val dm = resources.displayMetrics
    val widthDp = dm.widthPixels / dm.density
    return when {
        widthDp >= 1100 -> 4
        widthDp >= 800 -> 3
        else -> 2
    }
}

