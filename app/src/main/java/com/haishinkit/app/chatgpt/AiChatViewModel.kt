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
    val image: String? = null,
    val busy: Boolean = false,
    val mcpReady: Boolean = false,
    val approval: Boolean = false,
    val welcome: Boolean = false,
    val notice: String? = null,
    val usageLimited: Boolean = false,
) {
    fun withAccount(id: String?): AiChatState =
        if (id == account) this else copy(account = id, models = emptyList(), model = null)
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
        if (!mutableState.value.busy && mutableState.value.lines.isEmpty()) mutableState.update { it.copy(model = slug) }
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
        mutableState.update { it.copy(busy = true, partial = "", notice = null, usageLimited = false, lines = it.lines + ChatLine(true, prompt)) }
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
                            onDelta = { delta -> if (generation == turn) mutableState.update { it.copy(partial = it.partial + delta) } },
                            onImage = { image -> if (generation == turn) mutableState.update { it.copy(image = image) } },
                        )
                    if (generation == turn) mutableState.update { it.copy(lines = (it.lines + ChatLine(false, answer)).takeLast(12), partial = "") }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == turn) report(error)
                } finally {
                    if (generation == turn) mutableState.update { it.copy(busy = false, partial = "") }
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
        mutableState.update { it.copy(busy = authJob?.isActive == true, approval = false, partial = "") }
    }

    fun clearConversation() {
        cancelChat()
        conversation.clear()
        mutableState.update { it.copy(lines = emptyList(), partial = "", image = null, notice = null, usageLimited = false) }
    }

    override fun onCleared() {
        pause()
        http.close()
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
                    if (attempt == authGeneration) report(error)
                } finally {
                    if (attempt == authGeneration) mutableState.update { it.copy(busy = false) }
                }
            }
    }

    private suspend fun reloadAccounts(selected: String? = mutableState.value.account) {
        val accounts = auth.accounts()
        val id = selected?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.lastOrNull()?.id
        val welcome = accounts.any { it.id == id && it.planEnabled } && auth.needsPlanNotice()
        mutableState.update { it.withAccount(id).copy(accounts = accounts, welcome = welcome) }
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

    private fun report(error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) return
        val message =
            when ((error as? OpenAiFailure)?.code) {
                "access_denied" -> R.string.ai_denied
                "subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable" -> R.string.ai_usage_limit
                else -> R.string.ai_request_failed
            }
        mutableState.update { it.copy(notice = text(message), usageLimited = message == R.string.ai_usage_limit) }
    }

    private fun text(resource: Int): String = getApplication<Application>().getString(resource)
}
