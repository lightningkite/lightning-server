package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import kotlinx.serialization.Serializable

/**
 * TypedApiExamplesEndpoints - Demonstrates type-safe API endpoints with auto-documentation.
 * 
 * These examples showcase the typed endpoint API which provides:
 * - Automatic OpenAPI documentation generation
 * - Type-safe request/response handling
 * - SDK generation for TypeScript and Kotlin clients
 * - Built-in error handling and validation
 * - Example requests/responses for documentation
 */
object TypedApiExamplesEndpoints : ServerBuilder() {
    
    /**
     * POST /api/calculator
     * 
     * A simple calculator endpoint demonstrating basic typed API usage.
     * Shows input validation and structured error responses.
     */
    val calculator = path.path("api").path("calculator").post bind ApiHttpHandler(
        summary = "Perform basic arithmetic operations",
        description = "Calculates the result of two numbers using the specified operation (+, -, *, /)",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "invalid-operation", message = "Operation must be one of: +, -, *, /"),
            LSError(http = 400, detail = "division-by-zero", message = "Cannot divide by zero")
        ),
        successCode = HttpStatus.OK,
        examples = listOf(
            ApiHttpHandler.Example(
                input = CalculatorRequest(10.0, 5.0, "+"),
                output = CalculatorResponse(result = 15.0, operation = "10.0 + 5.0 = 15.0")
            ),
            ApiHttpHandler.Example(
                input = CalculatorRequest(10.0, 3.0, "/"),
                output = CalculatorResponse(result = 3.333, operation = "10.0 / 3.0 = 3.333")
            )
        ),
        implementation = { input: CalculatorRequest ->
            val result = when (input.operation) {
                "+" -> input.a + input.b
                "-" -> input.a - input.b
                "*" -> input.a * input.b
                "/" -> {
                    if (input.b == 0.0) {
                        throw BadRequestException("division-by-zero", "Cannot divide by zero")
                    }
                    input.a / input.b
                }
                else -> throw BadRequestException(
                    "invalid-operation",
                    "Operation must be one of: +, -, *, /"
                )
            }
            
            CalculatorResponse(
                result = result,
                operation = "${input.a} ${input.operation} ${input.b} = $result"
            )
        }
    )
    
    /**
     * POST /api/validate-email
     * 
     * Demonstrates input validation and conditional responses.
     */
    val validateEmail = path.path("api").path("validate-email").post bind ApiHttpHandler(
        summary = "Validate an email address",
        description = "Checks if the provided email address is in a valid format",
        auth = noAuth,
        successCode = HttpStatus.OK,
        examples = listOf(
            ApiHttpHandler.Example(
                input = EmailValidationRequest("user@example.com"),
                output = EmailValidationResponse(isValid = true, message = "Email is valid")
            ),
            ApiHttpHandler.Example(
                input = EmailValidationRequest("invalid-email"),
                output = EmailValidationResponse(isValid = false, message = "Email format is invalid")
            )
        ),
        implementation = { input: EmailValidationRequest ->
            val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
            val isValid = emailRegex.matches(input.email)
            
            EmailValidationResponse(
                isValid = isValid,
                message = if (isValid) "Email is valid" else "Email format is invalid",
                domain = if (isValid) input.email.substringAfter("@") else null
            )
        }
    )
    
    /**
     * POST /api/search
     * 
     * Demonstrates complex input/output with optional fields and lists.
     */
    val search = path.path("api").path("search").post bind ApiHttpHandler(
        summary = "Search for items",
        description = "Performs a search with optional filtering and sorting",
        auth = noAuth,
        successCode = HttpStatus.OK,
        examples = listOf(
            ApiHttpHandler.Example(
                input = SearchRequest(
                    query = "lightning server",
                    tags = listOf("kotlin", "backend"),
                    maxResults = 10
                ),
                output = SearchResponse(
                    results = listOf(
                        SearchResult("1", "Lightning Server Documentation", 95),
                        SearchResult("2", "Getting Started with Lightning Server", 87)
                    ),
                    totalCount = 2,
                    query = "lightning server"
                )
            )
        ),
        implementation = { input: SearchRequest ->
            // Mock search results
            val mockResults = listOf(
                SearchResult("1", "Lightning Server Documentation", 95),
                SearchResult("2", "Getting Started with Lightning Server", 87),
                SearchResult("3", "Building APIs with Lightning Server", 82),
                SearchResult("4", "Advanced Lightning Server Patterns", 76)
            ).filter { result ->
                // Simple filtering based on query
                result.title.contains(input.query, ignoreCase = true)
            }.take(input.maxResults ?: 10)
            
            SearchResponse(
                results = mockResults,
                totalCount = mockResults.size,
                query = input.query,
                appliedTags = input.tags
            )
        }
    )
    
    /**
     * POST /api/transform
     * 
     * Demonstrates nullable inputs and outputs.
     */
    val transform = path.path("api").path("transform").post bind ApiHttpHandler(
        summary = "Transform text with optional operations",
        description = "Applies various transformations to text. Returns null if input is null.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        examples = listOf(
            ApiHttpHandler.Example(
                input = TransformRequest("hello world", TransformType.UPPERCASE),
                output = TransformResponse("HELLO WORLD")
            ),
            ApiHttpHandler.Example(
                input = TransformRequest(null, TransformType.UPPERCASE),
                output = TransformResponse(null)
            )
        ),
        implementation = { input: TransformRequest ->
            val result = input.text?.let { text ->
                when (input.type) {
                    TransformType.UPPERCASE -> text.uppercase()
                    TransformType.LOWERCASE -> text.lowercase()
                    TransformType.REVERSE -> text.reversed()
                    TransformType.CAPITALIZE -> text.split(" ").joinToString(" ") { 
                        it.replaceFirstChar { c -> c.uppercase() } 
                    }
                }
            }
            
            TransformResponse(result)
        }
    )
}

// Request/Response models for typed endpoints

@Serializable
data class CalculatorRequest(
    val a: Double,
    val b: Double,
    val operation: String
)

@Serializable
data class CalculatorResponse(
    val result: Double,
    val operation: String
)

@Serializable
data class EmailValidationRequest(
    val email: String
)

@Serializable
data class EmailValidationResponse(
    val isValid: Boolean,
    val message: String,
    val domain: String? = null
)

@Serializable
data class SearchRequest(
    val query: String,
    val tags: List<String> = listOf(),
    val maxResults: Int? = null,
    val sortBy: String? = null
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult>,
    val totalCount: Int,
    val query: String,
    val appliedTags: List<String> = listOf()
)

@Serializable
data class SearchResult(
    val id: String,
    val title: String,
    val score: Int
)

@Serializable
data class TransformRequest(
    val text: String?,
    val type: TransformType
)

@Serializable
data class TransformResponse(
    val result: String?
)

@Serializable
enum class TransformType {
    UPPERCASE,
    LOWERCASE,
    REVERSE,
    CAPITALIZE
}
