package blbl.cat3399.feature.my

interface MyNavigator {
    fun openFavFolder(mediaId: Long, title: String)

    fun openBangumiDetail(seasonId: Long, isDrama: Boolean)
}

