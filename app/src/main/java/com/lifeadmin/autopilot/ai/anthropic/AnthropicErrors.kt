package com.lifeadmin.autopilot.ai.anthropic

import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.lifeadmin.autopilot.ai.PlanningError
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLException

/** Shared classification of SDK/HTTP failures for the planning and research adapters. Never includes the key. */
internal object AnthropicErrors {
    fun map(e: Throwable): PlanningError = when (e) {
        is UnauthorizedException -> PlanningError.Unauthorized
        is PermissionDeniedException -> PlanningError.Forbidden
        is RateLimitException -> PlanningError.RateLimited(retryAfter(e))
        is InternalServerException -> PlanningError.ServerError(e.statusCode())
        is BadRequestException, is NotFoundException, is UnprocessableEntityException -> PlanningError.BadRequest((e as AnthropicServiceException).statusCode())
        is AnthropicServiceException -> when (val status = e.statusCode()) {
            402 -> PlanningError.Forbidden
            408 -> PlanningError.Timeout
            429 -> PlanningError.RateLimited(retryAfter(e))
            in 500..599 -> PlanningError.ServerError(status)
            else -> PlanningError.BadRequest(status)
        }
        is AnthropicInvalidDataException -> PlanningError.Malformed("unexpected response shape")
        is AnthropicIoException -> ioError(e.cause ?: e)
        is InterruptedIOException, is UnknownHostException, is ConnectException, is SSLException -> ioError(e)
        else -> e.cause?.takeIf { it !== e }?.let { map(it) } ?: PlanningError.Unexpected(e.javaClass.simpleName)
    }

    private fun ioError(e: Throwable): PlanningError = when (e) {
        is InterruptedIOException -> PlanningError.Timeout
        is UnknownHostException, is ConnectException -> PlanningError.Network
        else -> e.cause?.takeIf { it !== e }?.let { ioError(it) } ?: PlanningError.Network
    }

    fun retryAfter(e: AnthropicServiceException): Duration? =
        e.headers().values("retry-after").firstOrNull()?.trim()?.toLongOrNull()?.let(Duration::ofSeconds)

    /** The error body as lower-case text, for message-dependent 400s; empty when unavailable. */
    fun bodyText(e: Throwable): String {
        val svc = e as? AnthropicServiceException ?: (e.cause as? AnthropicServiceException) ?: return ""
        return runCatching { svc.body().toString().lowercase() }.getOrDefault("")
    }
}
