package com.kickbase.app.presentation.views.billing.contract

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kickbase.app.R
import com.kickbase.app.business.services.billing.CardState
import com.kickbase.app.business.services.whitelabel.WhiteLabelTheme
import com.kickbase.app.presentation.extensions.getBackgroundColor
import com.kickbase.app.presentation.extensions.getColor
import com.kickbase.app.presentation.extensions.getTextColor
import com.kickbase.app.presentation.extensions.stringRes
import com.kickbase.app.presentation.utils.whitelabeltheme.DefaultWhiteLabelThemeProvider
import com.google.android.material.composethemeadapter.MdcTheme

@Composable
fun CardStateContainer(
    cardState: CardState?,
    cardNumber: String?,
    whiteLabelTheme: WhiteLabelTheme,
    onCardNumberLongClickListener: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val statusTextColor =
        cardState?.getTextColor(whiteLabelTheme) ?: CardState.UNKNOWN.getTextColor(whiteLabelTheme)
    val statusSurfaceColor =
        cardState?.getBackgroundColor(whiteLabelTheme) ?: CardState.UNKNOWN.getBackgroundColor(
            whiteLabelTheme
        )
    val formattedCardNumber =
        cardNumber ?: stringResource(id = R.string.contracts_contract_rfid_fallback)

    MdcTheme {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_rfid_card),
                contentDescription = null,
                tint = Color(whiteLabelTheme.iconPrimaryColor.getColor())
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.charging_card),
                fontSize = dimensionResource(id = R.dimen.text_caption).value.sp,
                fontStyle = FontStyle.Normal,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = Color(whiteLabelTheme.textSecondaryColor.getColor()),
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            if (cardState != null && cardState != CardState.UNKNOWN) {
                StatusLabel(
                    cardState.stringRes,
                    statusTextColor.getColor(),
                    statusSurfaceColor.getColor()
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                modifier = Modifier.pointerInput(formattedCardNumber) {
                    detectTapGestures(
                        onLongPress = {
                            onCardNumberLongClickListener?.invoke(formattedCardNumber)
                        }
                    )
                },
                text = formattedCardNumber,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color(whiteLabelTheme.textPrimaryColor.getColor())
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CardStateContainerPreview(
    @PreviewParameter(CardStateProvider::class) cardState: CardState
) {
    val whiteLabelTheme =
        DefaultWhiteLabelThemeProvider(LocalContext.current).defaultWhiteLabelTheme
    CardStateContainer(cardState, "GB*1234*56789", whiteLabelTheme, null)
}

@Preview(showBackground = true)
@Composable
fun CardStateContainerDefaultPreview() {
    val whiteLabelTheme =
        DefaultWhiteLabelThemeProvider(LocalContext.current).defaultWhiteLabelTheme
    CardStateContainer(CardState.ACTIVE, "GB*1234*56789", whiteLabelTheme, null)
}

class CardStateProvider : PreviewParameterProvider<CardState> {
    override val values: Sequence<CardState>
        get() = CardState.values().asSequence()
}
