package cp.player.core.provider

import cp.player.core.BackendResult
import cp.player.core.music.UnifiedMusicSourceImpl
import cp.player.core.provider.typed.*
import cp.player.sdk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NetEaseCommentOverflowTest {
    private fun page(count: Int, sort: Int, cursor: String = "next-hot") = CommentPage(
        comments = List(count) { CommentItem(
            id = it + 1L, content = "comment", user = "user", avatar = "", time = "",
            likedCount = 0, liked = false, replyCount = 0, userId = 1, timeMs = 1,
            parentCommentId = 0, ipLocation = "", isHot = false,
        ) }, totalCount = 1000, hasMore = true, cursor = cursor, sortType = sort,
    )

    private suspend fun fixture(
        cursorPaging: Boolean = true,
        providerId: String = "netease-cloudmusic-rs",
        answer: (Int) -> CommentPage,
        check: suspend (UnifiedMusicSourceImpl, MutableList<Int>) -> Unit,
    ) {
        val sorts = mutableListOf<Int>()
        val adapter = object : MusicProvider {
            override suspend fun capabilities() = Capabilities(listOf(
                if (cursorPaging) ExtendedCapability.GETCOMMENTPAGE else Capability.COMMENTS_LIST,
            ))
            override suspend fun getCommentPage(request: CommentQuery): CommentPage {
                sorts += request.sortType
                return answer(request.sortType)
            }
            override suspend fun getComments(request: CommentPageRequest): CommentPage {
                sorts += request.sortType
                return answer(request.sortType)
            }
        }
        val host = ProviderHost(ProviderCallContext(ProviderSessionKey(providerId, "test", 0)), adapter)
        try { check(UnifiedMusicSourceImpl(SdkEmptyLocal(), { host }), sorts) }
        finally { host.close() }
    }

    @Test fun `recommendation overflow restarts hot without discarding cursor covered items`() = runBlocking {
        for (cursorPaging in listOf(true, false)) fixture(cursorPaging, answer = {
            if (it == 1) page(26, 1, "recommend-cursor") else page(20, 2)
        }) { source, sorts ->
            val result = assertIs<BackendResult.Success<cp.player.core.music.CommentPage>>(
                source.getCommentsPage("netease-cloudmusic-rs://song/186016", "music", 20, 0, 1, null),
            ).data
            assertEquals(listOf(1, 2), sorts)
            assertEquals(20, result.comments.size)
            assertEquals(2, result.sortType)
            assertEquals(1000L, result.totalCount)
            assertTrue(result.hasMore)
            assertEquals("next-hot", result.cursor)
            assertEquals(if (cursorPaging) "next-hot" else null, result.nextCursor)
        }
    }

    @Test fun `valid recommendation results retain recommendation sort without retry`() = runBlocking {
        fixture(answer = { page(20, 1, "recommend-next") }) { source, sorts ->
            val result = assertIs<BackendResult.Success<cp.player.core.music.CommentPage>>(
                source.getCommentsPage("netease-cloudmusic-rs://song/186016", "music", 20, 0, 1, null),
            ).data
            assertEquals(listOf(1), sorts)
            assertEquals(1, result.sortType)
            assertEquals("recommend-next", result.nextCursor)
        }
    }

    @Test fun `provider error message cannot impersonate host overflow diagnostic`() = runBlocking {
        val calls = mutableListOf<Int>()
        val host = ProviderHost(ProviderCallContext(ProviderSessionKey("netease", "test", 0)), object : MusicProvider {
            override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETCOMMENTPAGE))
            override suspend fun getCommentPage(request: CommentQuery): CommentPage {
                calls += request.sortType
                throw ProviderException(Failure(FailureCategory.INVALID_RESPONSE, "comments.count:request-limit"))
            }
        })
        try {
            val source = UnifiedMusicSourceImpl(SdkEmptyLocal(), { host })
            assertIs<BackendResult.Error>(source.getCommentsPage("netease://song/186016", "music", 20, 0, 1, null))
            assertEquals(listOf(1), calls)
        } finally { host.close() }
    }

    @Test fun `hot overflow and unexpected fallback sort remain errors`() = runBlocking {
        for (answer in listOf<(Int) -> CommentPage>(
            { page(26, it) },
            { if (it == 1) page(26, 1) else page(20, 1) },
        )) fixture(answer = answer) { source, sorts ->
            assertIs<BackendResult.Error>(source.getCommentsPage(
                "netease-cloudmusic-rs://song/186016", "music", 20, 0, 1, null,
            ))
            assertEquals(listOf(1, 2), sorts)
        }
    }

    @Test fun `other provider or continuation overflow never changes sort`() = runBlocking {
        fixture(providerId = "other", answer = { page(26, it) }) { source, sorts ->
            assertIs<BackendResult.Error>(source.getCommentsPage("other://song/186016", "music", 20, 0, 1, null))
            assertEquals(listOf(1), sorts)
        }
        fixture(answer = { page(26, it) }) { source, sorts ->
            assertIs<BackendResult.Error>(source.getCommentsPage(
                "netease-cloudmusic-rs://song/186016", "music", 20, 20, 1, "opaque-secret",
            ))
            assertEquals(listOf(1), sorts)
        }
    }

    @Test fun `other invalid fields never activate recommendation fallback`() = runBlocking {
        fixture(answer = { page(20, 99) }) { source, sorts ->
            assertIs<BackendResult.Error>(source.getCommentsPage(
                "netease-cloudmusic-rs://song/186016", "music", 20, 0, 1, null,
            ))
            assertEquals(listOf(1), sorts)
        }
    }

    @Test fun `validation diagnostic has a static field and rule without response content`() = runBlocking {
        val host = ProviderHost(ProviderCallContext(ProviderSessionKey("netease", "test", 0)), object : MusicProvider {
            override suspend fun capabilities() = Capabilities(listOf(ExtendedCapability.GETCOMMENTPAGE))
            override suspend fun getCommentPage(request: CommentQuery) = page(26, 1, "secret-cursor")
        })
        try {
            val error = assertFailsWith<CommentContractViolation> {
                host.getCommentPage(CommentQuery("secret-track-id", "music", 20, 0, 1, null))
            }
            assertEquals("comments.count:request-limit", error.message)
            assertFalse(error.message.orEmpty().contains("secret"))
        } finally { host.close() }
    }
}
