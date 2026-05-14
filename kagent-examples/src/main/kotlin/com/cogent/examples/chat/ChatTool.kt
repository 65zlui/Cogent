package com.cogent.examples.chat

/**
 * Simple tool abstraction for the debuggable chat agent.
 * Tools are invoked as named steps during execution, producing
 * [RuntimeEvent.ToolCall] and [RuntimeEvent.ToolResult] events
 * in the timeline.
 */
interface ChatTool {
    val name: String
    suspend fun execute(input: String): String
}

// ================================================================
// Search Tool
// ================================================================

/** Simulated web search tool with expanded canned results. */
class SearchTool : ChatTool {
    override val name = "search"

    override suspend fun execute(input: String): String {
        kotlinx.coroutines.delay(50)
        val query = input.lowercase().trim()

        // Find best matching key
        val bestKey = results.keys
            .filter { query.contains(it) || it.contains(query) }
            .maxByOrNull { it.length.coerceAtMost(query.length) }

        return if (bestKey != null) {
            results[bestKey]!!
        } else {
            "No results found for \"$input\". Try: ${results.keys.joinToString(", ")}"
        }
    }

    companion object {
        val results = mapOf(
            "kotlin" to "Kotlin is a modern JVM language by JetBrains. It features null safety, " +
                "coroutines for async programming, and seamless Java interop.",
            "coroutine" to "Coroutines are lightweight threads in Kotlin for asynchronous " +
                "programming. They support structured concurrency and suspension.",
            "coroutines" to "Coroutines are lightweight threads in Kotlin for asynchronous " +
                "programming. They support structured concurrency and suspension.",
            "cogent" to "Cogent: an observable execution runtime for JVM-native AI agents. " +
                "Features timeline DAG debugging, state snapshots, and full execution tracing.",
            "jetbrains" to "JetBrains is the company behind Kotlin, IntelliJ IDEA, and other " +
                "developer tools. Headquartered in Prague, Czech Republic.",
            "intellij" to "IntelliJ IDEA is a Java/Kotlin IDE by JetBrains with advanced " +
                "code analysis, refactoring, and debugging capabilities.",
            "jvm" to "The Java Virtual Machine (JVM) is a platform-independent execution " +
                "environment for Java and Kotlin bytecode.",
            "openai" to "OpenAI creates AI models including GPT-4, GPT-4o, and DALL-E. " +
                "Their API is widely used for text generation and chat.",
            "deepseek" to "DeepSeek is an AI research company. Their API provides cost-effective " +
                "language models including DeepSeek-V2 and DeepSeek-R1.",
            "kimi" to "Kimi (Moonshot AI) is a Chinese AI company. Their API provides the " +
                "moonshot-v1-8k model for chat and text generation.",
            "moonshot" to "Moonshot AI develops the Kimi assistant platform and API services " +
                "with long-context language models.",
        )
    }
}

// ================================================================
// Calculator Tool (recursive descent parser)
// ================================================================

/**
 * Calculator tool supporting multi-operator expressions with
 * operator precedence and parentheses.
 *
 * Grammar:
 * ```
 * expression → term (('+' | '-') term)*
 * term       → factor (('*' | '/') factor)*
 * factor     → number | '(' expression ')'
 * ```
 */
class CalculatorTool : ChatTool {
    override val name = "calculator"

    override suspend fun execute(input: String): String {
        kotlinx.coroutines.delay(20)
        val expr = input.trim()
        if (expr.isBlank()) return "Please provide an expression to calculate."
        return try {
            val tokens = tokenize(expr)
            if (tokens.isEmpty()) return "Empty expression: $input"
            val result = eval(tokens)
            val formatted = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                "%.4f".format(result).trimEnd('0').trimEnd('.')
            }
            "$expr = $formatted"
        } catch (e: ArithmeticException) {
            "Math error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            "Cannot calculate: $input — ${e.message}"
        } catch (e: Exception) {
            "Cannot calculate: $input"
        }
    }

    // ----------------------------------------------------------
    // Tokenizer
    // ----------------------------------------------------------

    private data class Token(
        val type: TokenType,
        val value: String = ""
    )

    private enum class TokenType {
        NUMBER, PLUS, MINUS, TIMES, DIVIDE, LPAREN, RPAREN, END
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            when {
                ch.isWhitespace() -> i++
                ch == '+' -> { tokens.add(Token(TokenType.PLUS, "+")); i++ }
                ch == '-' -> { tokens.add(Token(TokenType.MINUS, "-")); i++ }
                ch == '*' -> { tokens.add(Token(TokenType.TIMES, "*")); i++ }
                ch == '/' -> { tokens.add(Token(TokenType.DIVIDE, "/")); i++ }
                ch == '(' -> { tokens.add(Token(TokenType.LPAREN, "(")); i++ }
                ch == ')' -> { tokens.add(Token(TokenType.RPAREN, ")")); i++ }
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens.add(Token(TokenType.NUMBER, expr.substring(start, i)))
                }
                else -> throw IllegalArgumentException("unexpected character '$ch'")
            }
        }
        tokens.add(Token(TokenType.END))
        return tokens
    }

    // ----------------------------------------------------------
    // Recursive descent parser + evaluator
    // ----------------------------------------------------------

    private var pos = 0
    private lateinit var tokens: List<Token>

    private fun eval(tokens: List<Token>): Double {
        this.tokens = tokens
        pos = 0
        val result = parseExpression()
        if (current().type != TokenType.END) {
            throw IllegalArgumentException("unexpected token '${current().value}'")
        }
        return result
    }

    // expression → term (('+' | '-') term)*
    private fun parseExpression(): Double {
        var result = parseTerm()
        while (current().type == TokenType.PLUS || current().type == TokenType.MINUS) {
            when (advance().type) {
                TokenType.PLUS -> result += parseTerm()
                TokenType.MINUS -> result -= parseTerm()
                else -> {}
            }
        }
        return result
    }

    // term → factor (('*' | '/') factor)*
    private fun parseTerm(): Double {
        var result = parseFactor()
        while (current().type == TokenType.TIMES || current().type == TokenType.DIVIDE) {
            when (advance().type) {
                TokenType.TIMES -> result *= parseFactor()
                TokenType.DIVIDE -> {
                    val divisor = parseFactor()
                    if (divisor == 0.0) throw ArithmeticException("division by zero")
                    result /= divisor
                }
                else -> {}
            }
        }
        return result
    }

    // factor → number | '(' expression ')'
    private fun parseFactor(): Double {
        return when (current().type) {
            TokenType.NUMBER -> {
                val v = current().value.toDouble()
                advance()
                v
            }
            TokenType.LPAREN -> {
                advance() // consume '('
                val v = parseExpression()
                if (current().type != TokenType.RPAREN) {
                    throw IllegalArgumentException("missing closing parenthesis")
                }
                advance() // consume ')'
                v
            }
            TokenType.MINUS -> {
                advance() // consume '-'
                -parseFactor()
            }
            else -> throw IllegalArgumentException("expected number or '(' but got '${current().value}'")
        }
    }

    private fun current(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]
}

// ================================================================
// Summarize Tool
// ================================================================

/** Formats tool results as a markdown summary. */
class SummarizeTool : ChatTool {
    override val name = "summarize"

    override suspend fun execute(input: String): String {
        // Input can be raw results text
        val lines = input.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "*No data to summarize.*"

        return buildString {
            appendLine("## Summary")
            appendLine()
            appendLine("**Source:** ${lines.size} entries")
            appendLine()
            appendLine("| # | Content |")
            appendLine("|---|---------|")
            lines.forEachIndexed { i, line ->
                val preview = line.take(80).replace("|", "/")
                appendLine("| ${i + 1} | $preview |")
            }
            appendLine()
            appendLine("---")
            appendLine("*Generated by Cogent SummarizeTool*")
        }
    }
}
