package blbl.cat3399.feature.my

interface MyTabContentSwitchFocusHost {
    fun requestFocusCurrentPageFirstItemFromContentSwitch(): Boolean
}

interface MyTabSwitchFocusTarget {
    fun requestFocusFirstItemFromTabSwitch(): Boolean
}

