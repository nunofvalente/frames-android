package com.checkout.tokenization.repository

import androidx.annotation.VisibleForTesting
import com.checkout.BuildConfig
import com.checkout.base.mapper.Mapper
import com.checkout.base.usecase.UseCase
import com.checkout.network.response.NetworkApiResponse
import com.checkout.tokenization.NetworkApiClient
import com.checkout.tokenization.entity.GooglePayEntity
import com.checkout.tokenization.error.TokenizationError
import com.checkout.tokenization.logging.TokenizationLogger
import com.checkout.tokenization.mapper.TokenizationNetworkDataMapper
import com.checkout.tokenization.model.GooglePayTokenRequest
import com.checkout.tokenization.model.CardTokenRequest
import com.checkout.tokenization.model.TokenDetails
import com.checkout.tokenization.model.TokenResult
import com.checkout.tokenization.model.Card
import com.checkout.tokenization.request.GooglePayTokenNetworkRequest
import com.checkout.tokenization.request.TokenRequest
import com.checkout.tokenization.response.TokenDetailsResponse
import com.checkout.tokenization.utils.TokenizationConstants
import com.checkout.validation.model.ValidationResult
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import org.json.JSONException
import org.json.JSONObject

internal class TokenRepositoryImpl(
    private val networkApiClient: NetworkApiClient,
    private val cardToTokenRequestMapper: Mapper<Card, TokenRequest>,
    private val cardTokenizationNetworkDataMapper: TokenizationNetworkDataMapper<TokenDetails>,
    private val validateTokenizationDataUseCase: UseCase<Card, ValidationResult<Unit>>,
    private val logger: TokenizationLogger,
    private val publicKey: String
) : TokenRepository {

    @VisibleForTesting
    var networkCoroutineScope = CoroutineScope(
        CoroutineName(BuildConfig.PRODUCT_IDENTIFIER) +
                Dispatchers.IO +
                NonCancellable
    )

    @Suppress("TooGenericExceptionCaught")
    override fun sendCardTokenRequest(cardTokenRequest: CardTokenRequest) {
        var response: NetworkApiResponse<TokenDetailsResponse>

        networkCoroutineScope.launch {
            val validationTokenizationDataResult = validateTokenizationDataUseCase.execute(cardTokenRequest.card)

            when (validationTokenizationDataResult) {
                is ValidationResult.Failure -> {
                    response = NetworkApiResponse.InternalError(validationTokenizationDataResult.error)
                }

                is ValidationResult.Success -> {
                    logger.logTokenRequestEvent(TokenizationConstants.CARD, publicKey)

                    response = networkApiClient.sendCardTokenRequest(
                        cardToTokenRequestMapper.map(cardTokenRequest.card)
                    )

                    logResponse(response, TokenizationConstants.CARD)
                    logger.resetSession()
                }
            }

            val tokenResult = cardTokenizationNetworkDataMapper.toTokenResult(response)

            launch(Dispatchers.Main) {
                handleResponse(tokenResult, cardTokenRequest.onSuccess, cardTokenRequest.onFailure)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun sendGooglePayTokenRequest(googlePayTokenRequest: GooglePayTokenRequest) {
        var response: NetworkApiResponse<TokenDetailsResponse>

        networkCoroutineScope.launch {
            try {
                val request = GooglePayTokenNetworkRequest(
                    TokenizationConstants.GOOGLE_PAY,
                    creatingTokenData(googlePayTokenRequest.tokenJsonPayload)
                )

                logger.logTokenRequestEvent(TokenizationConstants.GOOGLE_PAY, publicKey)

                response = networkApiClient.sendGooglePayTokenRequest(request)

                logResponse(response, TokenizationConstants.GOOGLE_PAY)
                logger.resetSession()
            } catch (exception: Exception) {
                val error = TokenizationError(
                    TokenizationError.GOOGLE_PAY_REQUEST_PARSING_ERROR,
                    exception.message,
                    exception.cause
                )
                response = NetworkApiResponse.InternalError(error)
                logger.logErrorOnTokenRequestedEvent(TokenizationConstants.GOOGLE_PAY, publicKey, error)
            }

            val tokenResult = cardTokenizationNetworkDataMapper.toTokenResult(
                response
            )

            launch(Dispatchers.Main) {
                handleResponse(tokenResult, googlePayTokenRequest.onSuccess, googlePayTokenRequest.onFailure)
            }
        }
    }

    @VisibleForTesting
    @Throws(JSONException::class)
    fun creatingTokenData(tokenJsonPayload: String): GooglePayEntity {
        val tokenDataJsonObject = JSONObject(tokenJsonPayload)
        return GooglePayEntity(
            tokenDataJsonObject.getString("signature"),
            tokenDataJsonObject.getString("protocolVersion"),
            tokenDataJsonObject.getString("signedMessage")
        )
    }

    private fun handleResponse(
        tokenResult: TokenResult<TokenDetails>,
        success: (tokenDetails: TokenDetails) -> Unit,
        failure: (errorMessage: String) -> Unit
    ) {
        when (tokenResult) {
            is TokenResult.Success -> {
                success(tokenResult.result)
            }

            is TokenResult.Failure -> {
                tokenResult.error.message?.let { failure(it) }
            }
        }
    }

    private fun logResponse(response: NetworkApiResponse<TokenDetailsResponse>, tokenType: String) {
        when (response) {
            is NetworkApiResponse.ServerError -> logger.logTokenResponseEvent(
                tokenType,
                publicKey,
                null,
                response.code,
                response.body
            )

            is NetworkApiResponse.Success -> logger.logTokenResponseEvent(tokenType, publicKey, response.body)

            else -> {}
        }
    }
}
