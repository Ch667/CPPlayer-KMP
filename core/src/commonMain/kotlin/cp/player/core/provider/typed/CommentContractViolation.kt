package cp.player.core.provider.typed

/** Host-created diagnostics only: never include provider values, URLs or exception text. */
internal class CommentContractViolation private constructor(
    val field: String,
    val rule: String,
) : IllegalArgumentException("comments.$field:$rule") {
    companion object {
        fun pageSize(): CommentContractViolation = CommentContractViolation("count", "request-limit")
        fun total(): CommentContractViolation = CommentContractViolation("totalCount", "at-least-page-size")
        fun sort(): CommentContractViolation = CommentContractViolation("sortType", "effective-sort-1-to-3")
        fun hotSort(): CommentContractViolation = CommentContractViolation("sortType", "fallback-must-be-hot")
    }
}
