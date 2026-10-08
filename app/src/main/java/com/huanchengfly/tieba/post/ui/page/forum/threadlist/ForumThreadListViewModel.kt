package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.frsPage.Classify
import com.huanchengfly.tieba.post.api.models.protos.updateAgreeStatus
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.repository.FrsPageRepository
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.models.distinctById
import com.huanchengfly.tieba.post.utils.ForumTabFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import kotlin.math.min

/**
 * “最新”列表因分区屏蔽整页无内容时，单次操作最多额外继续加载的页数。
 */
private const val MaxContinueLoadAttempts = 3

abstract class ForumThreadListViewModel :
    BaseViewModel<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState, ForumThreadListUiEvent>() {
    override fun createInitialState(): ForumThreadListUiState = ForumThreadListUiState()

    override fun dispatchEvent(partialChange: ForumThreadListPartialChange): UiEvent? =
        when (partialChange) {
            is ForumThreadListPartialChange.FirstLoad.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ForumThreadListPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ForumThreadListPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ForumThreadListPartialChange.Agree.Failure -> {
                ForumThreadListUiEvent.AgreeFail(
                    partialChange.threadId,
                    partialChange.postId,
                    partialChange.hasAgree,
                    partialChange.error.getErrorCode(),
                    partialChange.error.getErrorMessage()
                )
            }

            else -> null
        }
}

enum class ForumThreadListType {
    Latest, Good
}

@Stable
@HiltViewModel
class LatestThreadListViewModel @Inject constructor() : ForumThreadListViewModel() {
    override fun createPartialChangeProducer(): PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> =
        ForumThreadListPartialChangeProducer(ForumThreadListType.Latest)
}

@Stable
@HiltViewModel
class GoodThreadListViewModel @Inject constructor() : ForumThreadListViewModel() {
    override fun createPartialChangeProducer(): PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> =
        ForumThreadListPartialChangeProducer(ForumThreadListType.Good)
}

private class ForumThreadListPartialChangeProducer(val type: ForumThreadListType) :
    PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun toPartialChangeFlow(intentFlow: Flow<ForumThreadListUiIntent>): Flow<ForumThreadListPartialChange> =
        merge(
            intentFlow.filterIsInstance<ForumThreadListUiIntent.FirstLoad>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<ForumThreadListUiIntent.Refresh>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<ForumThreadListUiIntent.LoadMore>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<ForumThreadListUiIntent.Agree>()
                .flatMapConcat { it.producePartialChange() },
        )

    /**
     * 当前吧被屏蔽的分区 tabId 集合（仅“最新”列表读取，其他列表返回空集合）。
     */
    private fun blockedTabsFor(forumName: String): Set<Int> =
        if (type == ForumThreadListType.Latest) ForumTabFilter.getBlockedTabs(forumName) else emptySet()

    /**
     * 仅“最新”列表：整页帖子都被分区屏蔽且还有下一页时，继续加载下一页（有次数上限）。
     */
    private fun shouldContinueLoading(
        blockedTabs: Set<Int>,
        isEmpty: Boolean,
        hasMore: Boolean,
        attempts: Int,
    ): Boolean = type == ForumThreadListType.Latest &&
            blockedTabs.isNotEmpty() &&
            isEmpty &&
            hasMore &&
            attempts < MaxContinueLoadAttempts

    private fun ThreadInfo.keptByTabFilter(blockedTabs: Set<Int>): Boolean =
        isTop == 1 || tabId !in blockedTabs

    private fun ForumThreadListUiIntent.FirstLoad.producePartialChange(): Flow<ForumThreadListPartialChange.FirstLoad> =
        flow {
            emit(ForumThreadListPartialChange.FirstLoad.Start)
            val blockedTabs = blockedTabsFor(forumName)
            var page = 1
            var attempts = 0
            var forumRuleTitle: String? = null
            var goodClassifies: ImmutableList<ImmutableHolder<Classify>> = persistentListOf()
            val threadList = mutableListOf<ThreadItemData>()
            var threadListIds: List<Long> = emptyList()
            var hasMore = false
            while (true) {
                val response = FrsPageRepository.frsPage(
                    forumName,
                    page,
                    if (page == 1) 1 else 2,
                    sortType.takeIf { type == ForumThreadListType.Latest } ?: -1,
                    goodClassifyId.takeIf { type == ForumThreadListType.Good }
                ).first()
                if (response.data_?.page == null) throw TiebaUnknownException
                if (page == 1) {
                    forumRuleTitle = response.data_.forum_rule?.title.takeIf {
                        type == ForumThreadListType.Latest && response.data_.forum_rule?.has_forum_rule == 1
                    }
                    goodClassifies = (response.data_.forum?.good_classify ?: emptyList()).wrapImmutable()
                }
                threadList += response.data_.thread_list
                    .filter { it.keptByTabFilter(blockedTabs) }
                    .map { ThreadItemData(it.wrapImmutable()) }
                threadListIds = response.data_.thread_id_list
                hasMore = response.data_.page.has_more == 1
                if (!shouldContinueLoading(blockedTabs, threadList.isEmpty(), hasMore, attempts)) break
                page++
                attempts++
            }
            emit(
                ForumThreadListPartialChange.FirstLoad.Success(
                    forumRuleTitle = forumRuleTitle,
                    threadList = threadList,
                    threadListIds = threadListIds,
                    goodClassifies = goodClassifies,
                    goodClassifyId = goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    hasMore = hasMore,
                    currentPage = page
                )
            )
        }.catch { emit(ForumThreadListPartialChange.FirstLoad.Failure(it)) }

    private fun ForumThreadListUiIntent.Refresh.producePartialChange(): Flow<ForumThreadListPartialChange.Refresh> =
        flow {
            emit(ForumThreadListPartialChange.Refresh.Start)
            val blockedTabs = blockedTabsFor(forumName)
            var page = 1
            var attempts = 0
            var goodClassifies: ImmutableList<ImmutableHolder<Classify>> = persistentListOf()
            val threadList = mutableListOf<ThreadItemData>()
            var threadListIds: List<Long> = emptyList()
            var hasMore = false
            while (true) {
                val response = FrsPageRepository.frsPage(
                    forumName,
                    page,
                    if (page == 1) 1 else 2,
                    sortType.takeIf { type == ForumThreadListType.Latest } ?: -1,
                    goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    forceNew = page == 1
                ).first()
                if (response.data_?.page == null) throw TiebaUnknownException
                if (page == 1) {
                    goodClassifies = (response.data_.forum?.good_classify ?: emptyList()).wrapImmutable()
                }
                threadList += response.data_.thread_list
                    .filter { it.keptByTabFilter(blockedTabs) }
                    .map { ThreadItemData(it.wrapImmutable()) }
                threadListIds = response.data_.thread_id_list
                hasMore = response.data_.page.has_more == 1
                if (!shouldContinueLoading(blockedTabs, threadList.isEmpty(), hasMore, attempts)) break
                page++
                attempts++
            }
            emit(
                ForumThreadListPartialChange.Refresh.Success(
                    threadList = threadList,
                    threadListIds = threadListIds,
                    goodClassifies = goodClassifies,
                    goodClassifyId = goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    hasMore = hasMore,
                    currentPage = page
                )
            )
        }.catch { emit(ForumThreadListPartialChange.Refresh.Failure(it)) }

    private fun ForumThreadListUiIntent.LoadMore.producePartialChange(): Flow<ForumThreadListPartialChange.LoadMore> =
        flow {
            emit(ForumThreadListPartialChange.LoadMore.Start)
            val blockedTabs = blockedTabsFor(forumName)
            var threadIds = threadListIds
            var page = currentPage
            var attempts = 0
            val threadList = mutableListOf<ThreadItemData>()
            var publishedThreadIds: List<Long> = threadIds
            var publishedPage = page
            var hasMore = true
            while (true) {
                val responseThreadList: List<ThreadInfo>
                if (threadIds.isNotEmpty()) {
                    val size = min(threadIds.size, 30)
                    val response = FrsPageRepository.threadList(
                        forumId,
                        forumName,
                        page,
                        sortType,
                        threadIds.subList(0, size).joinToString(separator = ",") { "$it" }
                    ).first()
                    if (response.data_ == null) throw TiebaUnknownException
                    responseThreadList = response.data_.thread_list
                    threadIds = threadIds.drop(size)
                    publishedThreadIds = threadIds
                    publishedPage = page
                    hasMore = responseThreadList.isNotEmpty()
                } else {
                    val response = FrsPageRepository.frsPage(
                        forumName,
                        page + 1,
                        2,
                        sortType.takeIf { type == ForumThreadListType.Latest } ?: -1,
                        goodClassifyId.takeIf { type == ForumThreadListType.Good }
                    ).first()
                    if (response.data_?.page == null) throw TiebaUnknownException
                    responseThreadList = response.data_.thread_list
                    page += 1
                    publishedThreadIds = response.data_.thread_id_list
                    threadIds = publishedThreadIds
                    publishedPage = page
                    hasMore = response.data_.page.has_more == 1
                }
                threadList += responseThreadList
                    .filter { it.keptByTabFilter(blockedTabs) }
                    .map { ThreadItemData(it.wrapImmutable()) }
                if (!shouldContinueLoading(blockedTabs, threadList.isEmpty(), hasMore, attempts)) break
                attempts++
            }
            emit(
                ForumThreadListPartialChange.LoadMore.Success(
                    threadList = threadList,
                    threadListIds = publishedThreadIds,
                    currentPage = publishedPage,
                    hasMore = hasMore
                )
            )
        }.catch { emit(ForumThreadListPartialChange.LoadMore.Failure(it)) }

    private fun ForumThreadListUiIntent.Agree.producePartialChange(): Flow<ForumThreadListPartialChange.Agree> =
        TiebaApi.getInstance().opAgreeFlow(
            threadId.toString(),
            postId.toString(),
            hasAgree,
            objType = 3
        ).map<AgreeBean, ForumThreadListPartialChange.Agree> {
            ForumThreadListPartialChange.Agree.Success(
                threadId,
                hasAgree xor 1
            )
        }
            .catch {
                emit(
                    ForumThreadListPartialChange.Agree.Failure(
                        threadId,
                        postId,
                        hasAgree,
                        it
                    )
                )
            }
            .onStart { emit(ForumThreadListPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
}

sealed interface ForumThreadListUiIntent : UiIntent {
    data class FirstLoad(
        val forumName: String,
        val sortType: Int = -1,
        val goodClassifyId: Int? = null,
    ) : ForumThreadListUiIntent

    data class Refresh(
        val forumName: String,
        val sortType: Int = -1,
        val goodClassifyId: Int? = null,
    ) : ForumThreadListUiIntent

    data class LoadMore(
        val forumId: Long,
        val forumName: String,
        val currentPage: Int,
        val threadListIds: List<Long>,
        val sortType: Int = -1,
        val goodClassifyId: Int? = null,
    ) : ForumThreadListUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int
    ) : ForumThreadListUiIntent
}

sealed interface ForumThreadListPartialChange : PartialChange<ForumThreadListUiState> {
    sealed class FirstLoad : ForumThreadListPartialChange {
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                Start -> oldState
                is Success -> oldState.copy(
                    isRefreshing = false,
                    forumRuleTitle = forumRuleTitle,
                    threadList = threadList.distinctById(),
                    threadListIds = threadListIds.toImmutableList(),
                    goodClassifies = goodClassifies.toImmutableList(),
                    goodClassifyId = goodClassifyId,
                    currentPage = currentPage,
                    hasMore = hasMore
                )

                is Failure -> oldState.copy(isRefreshing = false)
            }

        data object Start : FirstLoad()

        data class Success(
            val forumRuleTitle: String?,
            val threadList: List<ThreadItemData>,
            val threadListIds: List<Long>,
            val goodClassifies: List<ImmutableHolder<Classify>>,
            val goodClassifyId: Int?,
            val hasMore: Boolean,
            val currentPage: Int = 1,
        ) : FirstLoad()

        data class Failure(
            val error: Throwable
        ) : FirstLoad()
    }

    sealed class Refresh : ForumThreadListPartialChange {
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> oldState.copy(
                    isRefreshing = false,
                    threadList = threadList.distinctById(),
                    threadListIds = threadListIds.toImmutableList(),
                    goodClassifies = goodClassifies.toImmutableList(),
                    goodClassifyId = goodClassifyId,
                    currentPage = currentPage,
                    hasMore = hasMore
                )

                is Failure -> oldState.copy(isRefreshing = false)
            }

        data object Start : Refresh()

        data class Success(
            val threadList: List<ThreadItemData>,
            val threadListIds: List<Long>,
            val goodClassifies: List<ImmutableHolder<Classify>>,
            val goodClassifyId: Int? = null,
            val hasMore: Boolean,
            val currentPage: Int = 1,
        ) : Refresh()

        data class Failure(
            val error: Throwable
        ) : Refresh()
    }

    sealed class LoadMore : ForumThreadListPartialChange {
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                Start -> oldState.copy(isLoadingMore = true)
                is Success -> oldState.copy(
                    isLoadingMore = false,
                    threadList = (oldState.threadList + threadList).distinctById(),
                    threadListIds = threadListIds.toImmutableList(),
                    currentPage = currentPage,
                    hasMore = hasMore
                )

                is Failure -> oldState.copy(isLoadingMore = false)
            }

        data object Start : LoadMore()

        data class Success(
            val threadList: List<ThreadItemData>,
            val threadListIds: List<Long>,
            val currentPage: Int,
            val hasMore: Boolean,
        ) : LoadMore()

        data class Failure(
            val error: Throwable
        ) : LoadMore()
    }

    sealed class Agree private constructor() : ForumThreadListPartialChange {
        private fun List<ThreadItemData>.updateAgreeStatus(
            threadId: Long,
            hasAgree: Int,
        ): ImmutableList<ThreadItemData> {
            return map { data ->
                val (thread) = data
                if (thread.get { id } == threadId) {
                    ThreadItemData(thread.getImmutable { updateAgreeStatus(hasAgree) })
                } else data
            }.toImmutableList()
        }

        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                is Start -> {
                    oldState.copy(
                        threadList = oldState.threadList.updateAgreeStatus(
                            threadId,
                            hasAgree
                        )
                    )
                }

                is Success -> {
                    oldState.copy(
                        threadList = oldState.threadList.updateAgreeStatus(
                            threadId,
                            hasAgree
                        )
                    )
                }

                is Failure -> {
                    oldState.copy(
                        threadList = oldState.threadList.updateAgreeStatus(
                            threadId,
                            hasAgree
                        )
                    )
                }
            }

        data class Start(
            val threadId: Long,
            val hasAgree: Int
        ) : Agree()

        data class Success(
            val threadId: Long,
            val hasAgree: Int
        ) : Agree()

        data class Failure(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
            val error: Throwable
        ) : Agree()
    }
}

data class ForumThreadListUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val goodClassifyId: Int? = null,
    val forumRuleTitle: String? = null,
    val threadList: ImmutableList<ThreadItemData> = persistentListOf(),
    val threadListIds: ImmutableList<Long> = persistentListOf(),
    val goodClassifies: ImmutableList<ImmutableHolder<Classify>> = persistentListOf(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
) : UiState

sealed interface ForumThreadListUiEvent : UiEvent {
    data class AgreeFail(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        val errorCode: Int,
        val errorMsg: String
    ) : ForumThreadListUiEvent

    data class Refresh(
        val isGood: Boolean,
        val sortType: Int
    ) : ForumThreadListUiEvent

    data class BackToTop(
        val isGood: Boolean
    ) : ForumThreadListUiEvent

    data class AddThread(
        val forumName: String,
    ):ForumThreadListUiEvent
}