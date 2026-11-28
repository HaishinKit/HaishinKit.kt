package com.haishinkit.app.chatgpt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haishinkit.app.R
import com.haishinkit.screen.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal data class ChatModel(val slug: String, val label: String)

internal data class ChatLine(val user: Boolean, val text: String)

internal data class AiChatState(
    val accounts: List<ChatGptAccount> = emptyList(),
    val account: String? = null,
    val models: List<ChatModel> = emptyList(),
    val model: String? = null,
    val lines: List<ChatLine> = emptyList(),
    val partial: String = "",
    val pendingPrompt: String? = null,
    val activeTurnId: Int? = null,
    val imageApproved: Boolean = false,
    val busy: Boolean = false,
    val mcpReady: Boolean = false,
    val approval: Boolean = false,
    val welcome: Boolean = false,
    val notice: String? = null,
    val usageLimited: Boolean = false,
) {
    val canChangeModel get() = !busy && lines.isEmpty()

    fun beginTurn(
        prompt: String,
        turnId: Int,
    ): AiChatState = copy(busy = true, pendingPrompt = prompt, activeTurnId = turnId, partial = "", notice = null, usageLimited = false)

    fun completeTurn(
        prompt: String,
        answer: String,
        hasImages: Boolean,
    ): AiChatState =
        copy(busy = false, lines = (lines + ChatLine(true, prompt) + ChatLine(false, answer)).takeLast(12), pendingPrompt = null, activeTurnId = null, partial = "", imageApproved = hasImages)

    fun discardPendingTurn(hasImages: Boolean): AiChatState = copy(pendingPrompt = null, activeTurnId = null, partial = "", imageApproved = hasImages, approval = false)

    fun appendDelta(
        turnId: Int,
        delta: String,
    ): AiChatState =
        if (activeTurnId == turnId && busy && pendingPrompt != null) copy(partial = partial + delta) else this

    fun withAccounts(
        accounts: List<ChatGptAccount>,
        selected: String?,
        welcome: Boolean,
        reconnectNotice: String? = null,
    ): AiChatState {
        val id = selected?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.lastOrNull()?.id
        val selectedAccount = accounts.firstOrNull { it.id == id }
        val disconnected = account == id && this.accounts.any { it.id == id && it.connected } && selectedAccount?.connected != true
        val next = withAccount(id, planEnabled = selectedAccount?.planEnabled == true).copy(accounts = accounts, welcome = welcome)
        return if (disconnected && reconnectNotice != null) next.copy(notice = reconnectNotice, usageLimited = false) else next
    }

    fun withAccount(
        id: String?,
        planEnabled: Boolean = true,
    ): AiChatState =
        if (id == account && planEnabled) this else copy(account = id, models = emptyList(), model = null)
}

internal class AiChatViewModel(application: Application) : AndroidViewModel(application) {
    val state get() = mutableState.asStateFlow()

    private val mutableState = MutableStateFlow(AiChatState())
    private val http = OpenAiHttp()
    private val auth = ChatGptAuth(application, http)
    private val conversation = ChatGptConversation(http)
    private val mcpLifecycle = Mutex()
    private var bridge: LocalMcpBridge? = null
    private var bridgeJob: Job? = null
    private var authJob: Job? = null
    private var chatJob: Job? = null
    private var accountSyncJob: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var tools = JsonArray(emptyList())
    private var generation = 0
    private var authGeneration = 0

    init {
        accountTask {
            reloadAccounts()
            loadModels()
        }
    }

    fun visible(screen: Screen) {
        queueAccountSync()
        if (bridge != null) return
        val next = LocalMcpBridge(screen)
        bridge = next
        bridgeJob =
            viewModelScope.launch {
                try {
                    mcpLifecycle.withLock {
                        val definitions = next.open()
                        if (bridge === next) {
                            tools = definitions
                            mutableState.update { it.copy(mcpReady = true) }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (bridge === next) {
                        bridge = null
                        report(error)
                    }
                }
            }
    }

    fun pause() {
        cancelChat()
        bridgeJob?.cancel()
        val previous = bridge
        bridge = null
        previous?.detach()
        mutableState.update { it.copy(mcpReady = false) }
        if (previous != null) {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { mcpLifecycle.withLock { previous.close() } }
            }
        }
    }

    fun connect(
        account: String?,
        openBrowser: (String) -> Unit,
    ) {
        if (mutableState.value.busy) return
        accountTask {
            try {
                val id = auth.signIn(account, openBrowser)
                clearConversation()
                reloadAccounts(id)
                loadModels()
            } catch (error: Exception) {
                if (error !is CancellationException) runCatching { reloadAccounts() }
                throw error
            }
        }
    }

    fun selectAccount(id: String) {
        if (mutableState.value.busy) return
        clearConversation()
        mutableState.update { it.copy(account = id, models = emptyList(), model = null) }
        accountTask { loadModels() }
    }

    fun selectModel(slug: String) {
        if (mutableState.value.canChangeModel) mutableState.update { it.copy(model = slug) }
    }

    fun signOut() {
        val account = mutableState.value.account ?: return
        cancelChat()
        accountTask {
            val confirmed = auth.signOut(account)
            clearConversation()
            reloadAccounts(account)
            mutableState.update { it.copy(models = emptyList(), model = null, notice = if (confirmed) null else text(R.string.ai_revocation_unconfirmed)) }
        }
    }

    fun send(prompt: String) {
        val current = mutableState.value
        val account = current.account ?: return
        val model = current.model ?: return
        val connection = bridge ?: return
        if (current.busy || current.welcome || !current.mcpReady || prompt.isBlank()) return
        val turn = ++generation
        mutableState.update { it.beginTurn(prompt, turn) }
        chatJob =
            viewModelScope.launch {
                try {
                    val answer =
                        conversation.send(
                            model, prompt, { auth.accessToken(account) }, tools, connection::call,
                            approve = {
                                val pending = CompletableDeferred<Boolean>()
                                approval = pending
                                mutableState.update { it.copy(approval = true) }
                                try {
                                    pending.await()
                                } finally {
                                    approval = null
                                    mutableState.update { it.copy(approval = false) }
                                }
                            },
                            onDelta = { delta -> mutableState.update { it.appendDelta(turn, delta) } },
                            onImage = { if (generation == turn) mutableState.update { it.copy(imageApproved = true) } },
                        )
                    if (generation == turn) mutableState.update { it.completeTurn(prompt, answer, conversation.hasImages) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == turn) recover(error)
                } finally {
                    if (generation == turn) mutableState.update { it.discardPendingTurn(conversation.hasImages).copy(busy = false) }
                }
            }
    }

    fun stop() {
        authGeneration++
        authJob?.cancel()
        cancelChat()
        mutableState.update { it.copy(busy = false) }
    }

    fun acknowledgePlanNotice() {
        viewModelScope.launch {
            try {
                auth.acknowledgePlanNotice()
                mutableState.update { it.copy(welcome = false) }
            } catch (error: Exception) {
                report(error)
            }
        }
    }

    fun approveImage(allowed: Boolean) {
        approval?.complete(allowed)
    }

    fun cancelChat() {
        generation++
        chatJob?.cancel()
        approval?.complete(false)
        approval = null
        mutableState.update { it.discardPendingTurn(conversation.hasImages).copy(busy = authJob?.isActive == true) }
        queueAccountSync()
    }

    fun clearConversation() {
        cancelChat()
        conversation.clear()
        mutableState.update { it.copy(lines = emptyList(), partial = "", pendingPrompt = null, imageApproved = false, notice = null, usageLimited = false) }
    }

    override fun onCleared() {
        pause()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { auth.close() }
        super.onCleared()
    }

    private fun accountTask(block: suspend () -> Unit) {
        val attempt = ++authGeneration
        authJob?.cancel()
        mutableState.update { it.copy(busy = true, notice = null, usageLimited = false) }
        authJob =
            viewModelScope.launch {
                try {
                    block()
                } catch (error: Exception) {
                    if (attempt == authGeneration) recover(error)
                } finally {
                    if (attempt == authGeneration) mutableState.update { it.copy(busy = false) }
                }
            }
    }

    private suspend fun reloadAccounts(selected: String? = mutableState.value.account) {
        val accounts = auth.accounts()
        val id = selected?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.lastOrNull()?.id
        val welcome = accounts.any { it.id == id && it.planEnabled } && auth.needsPlanNotice()
        mutableState.update { it.withAccounts(accounts, id, welcome) }
    }

    private fun queueAccountSync() {
        val selected = mutableState.value.account
        val attempt = authGeneration
        accountSyncJob?.cancel()
        accountSyncJob =
            viewModelScope.launch {
                try {
                    // Wait behind any started renewal, then update metadata without restarting inference.
                    val accounts = auth.accounts()
                    val id = selected?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.lastOrNull()?.id
                    val welcome = accounts.any { it.id == id && it.planEnabled } && auth.needsPlanNotice()
                    currentCoroutineContext().ensureActive()
                    if (attempt == authGeneration) {
                        mutableState.update { current ->
                            if (current.account == selected) current.withAccounts(accounts, selected, welcome, text(R.string.ai_sign_in_required)) else current
                        }
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (attempt == authGeneration && mutableState.value.account == selected) report(error)
                }
            }
    }

    private suspend fun loadModels() {
        val current = mutableState.value
        val account = current.accounts.firstOrNull { it.id == current.account } ?: return
        if (!account.planEnabled) return
        val response = http.get("https://api.openai.com/v1/models", auth.accessToken(account.id))
        val models =
            response["models"]!!.jsonArray.map { it.jsonObject }.filter { it.textOrNull("visibility") == "list" }
                .map { ChatModel(it.text("slug"), it.text("display_name")) }
        if (mutableState.value.account == account.id) mutableState.update { it.copy(models = models, model = models.firstOrNull()?.slug) }
    }

    private suspend fun recover(error: Exception) {
        if ((error as? OpenAiFailure)?.requiresSignIn == true) reloadAccounts()
        report(error)
    }

    private fun report(error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) return
        val message = chatErrorMessage(error)
        mutableState.update { it.copy(notice = text(message), usageLimited = message == R.string.ai_usage_limit) }
    }

    private fun text(resource: Int): String = getApplication<Application>().getString(resource)
}

internal fun chatErrorMessage(error: Exception): Int =
    when {
        (error as? OpenAiFailure)?.requiresSignIn == true -> R.string.ai_sign_in_required
        (error as? OpenAiFailure)?.inferenceRestricted == true -> R.string.ai_inference_restricted
        (error as? OpenAiFailure)?.code == "access_denied" -> R.string.ai_denied
        (error as? OpenAiFailure)?.code == "subscription_sharing_user_not_eligible" -> R.string.ai_not_eligible
        (error as? OpenAiFailure)?.code in setOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable") -> R.string.ai_usage_limit
        else -> R.string.ai_request_failed
    }
