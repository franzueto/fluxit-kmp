package com.fluxit.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.fluxit.domain.ListIcon

fun ListIcon.toImageVector(): ImageVector = when (this) {
    ListIcon.CART -> Icons.Outlined.ShoppingCart
    ListIcon.TRAVEL -> Icons.Outlined.Flight
    ListIcon.WORK -> Icons.Outlined.Work
    ListIcon.HOME -> Icons.Outlined.Home
    ListIcon.GIFT -> Icons.Outlined.CardGiftcard
    ListIcon.FOOD -> Icons.Outlined.Restaurant
    ListIcon.FITNESS -> Icons.Outlined.FitnessCenter
    ListIcon.STAR -> Icons.Outlined.StarOutline
}
