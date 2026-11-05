package com.saule.lang.format

import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.tree.IElementType
import com.saule.lang.SauleIndentDefaults
import com.saule.lang.lexer.SauleLexer
import com.saule.lang.lexer.SauleTokenTypes as T

/**
 * How far one source line is indented.
 *
 * Split in two because the Code Style page has two knobs: [blocks] is counted in
 * *Indent* units (one per enclosing `class` / `fn` / `if` / … block) and
 * [continuations] in *Continuation indent* units (one per still-open bracket).
 */
data class SauleIndent(val blocks: Int, val continuations: Int) {

    /** Render as literal indent text, honouring tabs / spaces / sizes. */
    fun render(options: CommonCodeStyleSettings.IndentOptions): String {
        val indentSize = options.INDENT_SIZE.takeIf { it > 0 } ?: SauleIndentDefaults.INDENT
        val continuationSize =
            options.CONTINUATION_INDENT_SIZE.takeIf { it > 0 } ?: indentSize

        if (!options.USE_TAB_CHARACTER) {
            return " ".repeat(blocks * indentSize + continuations * continuationSize)
        }
        // "Smart tabs" is the platform's name for tabs-to-indent, spaces-to-align.
        // A continuation is alignment inside an unclosed bracket, so it gets
        // spaces; the block levels are real indentation and get hard tabs.
        return if (options.SMART_TABS) {
            "\t".repeat(blocks) + " ".repeat(continuations * continuationSize)
        } else {
            "\t".repeat(blocks + continuations)
        }
    }

    companion object {
        val ZERO = SauleIndent(0, 0)
    }
}

/**
 * Works out the indentation of any line of Saule source.
 *
 * `saule-lsp` owns *formatting*, but it is not in the loop while you type, so
 * the IDE needs its own answer to "how far in does this line start?" for
 * pressing Enter, for `Adjust Indent`, and for the auto-dedent of `end`.
 *
 * The model is a stack machine over the token stream rather than the PSI —
 * [com.saule.lang.SauleParserDefinition] builds a deliberately flat tree, and a
 * stack is anyway what matches Saule's `keyword … end` block syntax. Everything
 * here is derived from the printer in `crates/saule-fmt/src/lib.rs`; keep the
 * two in step or the editor and `saule fmt` will disagree.
 */
object SauleIndentModel {

    /**
     * A block that is currently open.
     *
     * [INTERFACE] is tracked separately because an interface body holds bare
     * method *signatures* — `fn foo()` with no `end` — so `fn` must not open a
     * block there ([saule_fmt::method_sig]).
     *
     * [CASE] is a `match` arm: `case … then` followed by a newline indents its
     * statements, and the arm is closed by the next `case` or by the `match`'s
     * `end` rather than by one of its own.
     *
     * [LOOP_HEADER] is a `for` / `while` block whose header has not reached its
     * `do` yet. It is a block like any other — it just remembers that the next
     * `do` belongs to the header and so must not open a second one.
     *
     * The rest are what keep this in step with `saule fmt`:
     *
     * [FN_HEADER] is a `fn` whose parameter list has not closed. Its body has
     * not started, so it is no level yet.
     *
     * [INLINE_CASE] is `case … then value`. An arm on one line steps nothing in.
     *
     * [BRACKET] is open with nothing decided. [SETTLED_BRACKET] was followed by
     * a line break, so its contents are one level in. [HUGGED_BRACKET] holds a
     * block on the line it opened on (`f(fn()`) and stays no level at all.
     */
    private enum class Frame {
        BLOCK,
        LOOP_HEADER,
        INTERFACE,
        CASE,
        BRACKET,
        FN_HEADER,
        INLINE_CASE,
        SETTLED_BRACKET,
        HUGGED_BRACKET,
    }

    private fun isBracket(frame: Frame?): Boolean =
        frame == Frame.BRACKET || frame == Frame.SETTLED_BRACKET || frame == Frame.HUGGED_BRACKET

    /** Turn the undecided brackets on top of the stack into [to]. */
    private fun decideBrackets(stack: ArrayList<Frame>, to: Frame) {
        var i = stack.lastIndex
        while (i >= 0 && stack[i] == Frame.BRACKET) {
            stack[i] = to
            i--
        }
    }

    /** Push a frame that opens a block, which hugs any bracket still undecided. */
    private fun pushBlock(stack: ArrayList<Frame>, frame: Frame) {
        decideBrackets(stack, Frame.HUGGED_BRACKET)
        stack.add(frame)
    }

    /** Keywords that open a block closed by `end` (or, for `repeat`, `until`). */
    private val BLOCK_OPENERS = setOf("class", "enum", "if", "repeat", "try", "match")

    /**
     * Loop keywords, whose header runs up to a `do`.
     *
     * Kept apart from [BLOCK_OPENERS] only so that `do` can tell the two uses
     * of itself apart: it terminates a loop header (which has already opened a
     * block), and anywhere else it opens a *trailing block* —
     * `Canvas() do … end`, the sugar for a call whose last argument is a
     * block-bodied lambda ([saule_fmt::trailing_block_arg]). Both bodies are
     * one level in.
     */
    private val LOOP_OPENERS = setOf("while", "for")

    /** Keywords that continue the enclosing block: written one level out. */
    private val CONTINUATIONS = setOf("else", "elseif", "catch", "finally")

    /** Keywords that close the enclosing block: also written one level out. */
    private val CLOSERS = setOf("end", "until")

    /**
     * Every keyword that sits one level out from the body above it.
     *
     * `case` is in here because a `match` arm with a block body is closed by the
     * next `case`, not by an `end`. Typing any of these as the whole of a line
     * is what triggers the editor's auto-dedent.
     */
    val DEDENT_KEYWORDS: Set<String> = CLOSERS + CONTINUATIONS + "case"

    private val OPEN_BRACKETS = setOf("(", "[", "{")
    private val CLOSE_BRACKETS = setOf(")", "]", "}")

    /**
     * Tokens after which an `fn` starts a *type* rather than a definition.
     *
     * `fn(T) -> U` written as a parameter's type, a local's annotation or a
     * return type has no body and no `end`. Treating its `fn` as a block
     * opener left a frame on the stack that nothing ever closed, and the
     * damage compounded: with a stray block on top, the `)` closing the
     * enclosing parameter list no longer matched a `BRACKET` frame, so that
     * stayed open too. Every later line in the file was then indented by the
     * leftovers — a file with two such signatures put new lines two blocks
     * and two continuations deep.
     *
     * Only these four contexts qualify, and each admits nothing but a type:
     * `:` ascribes one, `->` introduces a return type, `<` opens a generic
     * argument list, and `as` casts to one. Notably absent are `,` and `(` —
     * an anonymous function passed as an argument (`map(xs, fn(x) … end)`)
     * follows those, and it really is a block.
     */
    private val TYPE_POSITION = setOf(":", "->", "<", "as")

    /**
     * The indent the line spanning `[lineStart, lineEnd)` of [text] should have.
     *
     * The line's own first token matters: `end`, `else` and friends sit one
     * level out from the body they close, so the caller gets the right answer
     * for a line that is already (partly) typed as well as for an empty one.
     */
    fun indentForLine(text: CharSequence, lineStart: Int, lineEnd: Int): SauleIndent {
        val stack = ArrayList<Frame>()
        val lexer = SauleLexer()
        lexer.start(text, 0, text.length, 0)

        var firstOnLine: String? = null
        var previous: String? = null
        var beforePrevious: String? = null
        var previousStart = 0
        while (true) {
            val type: IElementType = lexer.tokenType ?: break
            val start = lexer.tokenStart
            if (start >= lineEnd) break
            if (isSignificant(type)) {
                val word = text.subSequence(start, lexer.tokenEnd).toString()
                val newLine = (previousStart until start).any { text[it] == '\n' }
                if (newLine) decideBrackets(stack, Frame.SETTLED_BRACKET)
                if (start >= lineStart) {
                    firstOnLine = word
                    break
                }
                apply(word, previous, beforePrevious, stack, newLine)
                beforePrevious = previous
                previous = word
                previousStart = start
            }
            lexer.advance()
        }

        val brackets = stack.count { it == Frame.BRACKET || it == Frame.SETTLED_BRACKET }
        val blocks = stack.count {
            it == Frame.BLOCK || it == Frame.LOOP_HEADER || it == Frame.INTERFACE || it == Frame.CASE
        }
        val top = stack.lastOrNull()

        var blockDedent = 0
        var bracketDedent = 0
        when {
            firstOnLine == null -> Unit
            firstOnLine in CLOSERS -> blockDedent = 1 + openCaseFrames(stack)
            firstOnLine in CONTINUATIONS -> blockDedent = 1
            // A `case` only steps out when a previous arm is still open;
            // the first arm of a `match` already sits at the body level.
            firstOnLine == "case" -> if (top == Frame.CASE) blockDedent = 1
            firstOnLine in CLOSE_BRACKETS ->
                if (top == Frame.BRACKET || top == Frame.SETTLED_BRACKET) bracketDedent = 1
        }

        return SauleIndent(
            (blocks - blockDedent).coerceAtLeast(0),
            (brackets - bracketDedent).coerceAtLeast(0),
        )
    }

    /** Whitespace and comments carry no block structure. */
    private fun isSignificant(type: IElementType): Boolean =
        type != TokenType.WHITE_SPACE && type != T.LINE_COMMENT && type != T.BLOCK_COMMENT

    private fun apply(
        word: String,
        previous: String?,
        beforePrevious: String?,
        stack: ArrayList<Frame>,
        newLine: Boolean,
    ) {
        if (previous == "then" && !newLine && stack.lastOrNull() == Frame.CASE) {
            stack[stack.lastIndex] = Frame.INLINE_CASE
        }
        when {
            word == "interface" -> stack.add(Frame.INTERFACE)
            // Also covers lambdas — `fn(x) … end` is a block like any other.
            // A `fn` in type position (`f: fn(T) -> U`) opens nothing: it is
            // a type, it has no body, and no `end` will ever come for it.
            word == "fn" -> {
                // `name: fn(x) … end` in a call or a table is a value, not an
                // annotation. Only a parameter list says `name: fn(T) -> U`.
                val below = stack.getOrNull(stack.lastIndex - 1)
                val namedValue = previous == ":" && isBracket(stack.lastOrNull()) &&
                    below != Frame.FN_HEADER && below != Frame.INTERFACE
                // `(fn() -> T)?` after a `:` is a type in brackets.
                val bracketedType = previous == "(" && beforePrevious in TYPE_POSITION
                if (stack.lastOrNull() != Frame.INTERFACE && !bracketedType &&
                    (namedValue || previous !in TYPE_POSITION)
                ) {
                    pushBlock(stack, Frame.FN_HEADER)
                }
            }
            word in BLOCK_OPENERS -> pushBlock(stack, Frame.BLOCK)
            word in LOOP_OPENERS -> pushBlock(stack, Frame.LOOP_HEADER)
            // Closing a loop header costs nothing — `for`/`while` opened the
            // block already — but a `do` anywhere else is a trailing block,
            // and that one is its own level.
            word == "do" ->
                if (stack.lastOrNull() == Frame.LOOP_HEADER) {
                    stack[stack.lastIndex] = Frame.BLOCK
                } else {
                    pushBlock(stack, Frame.BLOCK)
                }
            word in CLOSERS -> closeBlock(stack)
            word == "case" -> {
                if (stack.lastOrNull() == Frame.CASE || stack.lastOrNull() == Frame.INLINE_CASE) {
                    stack.removeAt(stack.lastIndex)
                }
                stack.add(Frame.CASE)
            }
            word in OPEN_BRACKETS -> stack.add(Frame.BRACKET)
            word in CLOSE_BRACKETS -> if (isBracket(stack.lastOrNull())) {
                stack.removeAt(stack.lastIndex)
                if (stack.lastOrNull() == Frame.FN_HEADER) stack[stack.lastIndex] = Frame.BLOCK
            }
        }
    }

    /**
     * Pop one real block. Any `case` arms — and any brackets left open by
     * half-typed code — are discarded with it, mirroring how `end` closes a
     * `match` and all of its arms at once.
     */
    private fun closeBlock(stack: ArrayList<Frame>) {
        while (stack.isNotEmpty() &&
            stack.last() != Frame.BLOCK &&
            stack.last() != Frame.FN_HEADER &&
            stack.last() != Frame.LOOP_HEADER &&
            stack.last() != Frame.INTERFACE
        ) {
            stack.removeLast()
        }
        if (stack.isNotEmpty()) stack.removeLast()
    }

    /** `match` arms open above the block that `end` is about to close. */
    private fun openCaseFrames(stack: ArrayList<Frame>): Int {
        var n = 0
        for (i in stack.indices.reversed()) {
            when (stack[i]) {
                Frame.CASE -> n++
                // Half-typed code or a one-line arm; not a level of its own here.
                Frame.BRACKET, Frame.SETTLED_BRACKET, Frame.HUGGED_BRACKET, Frame.INLINE_CASE -> Unit
                else -> return n
            }
        }
        return n
    }
}
