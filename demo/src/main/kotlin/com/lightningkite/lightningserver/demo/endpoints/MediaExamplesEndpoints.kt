package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import kotlinx.serialization.Serializable

/**
 * MediaExamplesEndpoints - Demonstrates the Lightning Server media processing capabilities.
 *
 * The media module provides automatic image processing including:
 * - Thumbnail generation at multiple sizes
 * - Format conversion (PNG, JPEG, WebP, etc.)
 * - Quality optimization for different use cases
 * - Aspect ratio enforcement
 * - EXIF orientation correction
 * - Intelligent preview selection based on client needs
 *
 * Processing can be done synchronously (immediate) or asynchronously (background tasks).
 *
 * Key Features:
 * - Uses ServerFileWithMetadata instead of plain ServerFile
 * - Automatically generates preview variants
 * - Smart preview selection based on supported formats and dimensions
 * - Integrates seamlessly with the files module
 * - Works with any file storage backend (S3, Azure, local)
 *
 * Note: This is a documentation endpoint. In a real application, you would:
 * 1. Use interceptImagesForProcessing() for synchronous processing
 * 2. Use processImagesInBackground() task for async processing
 * 3. Store ServerFileWithMetadata in your models instead of ServerFile
 */
object MediaExamplesEndpoints : ServerBuilder() {

    /**
     * POST /media/concepts
     *
     * Explains the core concepts of the Lightning Server media processing system.
     */
    val concepts = path.path("media").path("concepts").post bind ApiHttpHandler(
        summary = "Media processing concepts",
        description = "Overview of the Lightning Server image processing architecture",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            MediaConceptsResponse(
                keyFeatures = listOf(
                    "Automatic thumbnail generation at multiple sizes",
                    "Format conversion (PNG, JPEG, WebP, TIFF, GIF, BMP)",
                    "Quality control for lossy formats",
                    "Aspect ratio enforcement with cropping",
                    "EXIF orientation correction",
                    "Intelligent preview selection",
                    "Works with any file storage backend"
                ),

                dataModel = """
                    // Instead of ServerFile, use ServerFileWithMetadata
                    @Serializable
                    data class Product(
                        val _id: Uuid = Uuid.random(),
                        val name: String,
                        val photo: ServerFileWithMetadata  // ← Contains original + previews
                    )

                    // ServerFileWithMetadata structure:
                    {
                        "original": { "location": "photo.jpg" },
                        "mimeType": "image/jpeg",
                        "width": 3000,
                        "height": 2000,
                        "size": 1024000,
                        "previews": [
                            { "file": { "location": "photo-200-jpg.jpg" }, "width": 200, ... },
                            { "file": { "location": "photo-800-jpg.jpg" }, "width": 800, ... },
                            { "file": { "location": "photo-1200-jpg.jpg" }, "width": 1200, ... }
                        ]
                    }
                """.trimIndent(),

                processingOptions = """
                    MediaPreviewOptions controls how images are processed:

                    - sizeInPixels: Max dimension (width or height)
                    - forceRatio: Aspect ratio (e.g., 16/9, 1.0 for square)
                    - type: Output format (JPEG, PNG, WebP, etc.)
                    - quality: 0.0-1.0 for lossy formats (default 0.95 for JPEG)

                    Common presets:
                    - MediaPreviewOptions.CorrectOddFeatures: Only EXIF correction
                """.trimIndent()
            )
        }
    )

    /**
     * POST /media/preview-configurations
     *
     * Shows common MediaPreviewOptions configurations for different use cases.
     */
    val previewConfigurations = path.path("media").path("preview-configurations").post bind ApiHttpHandler(
        summary = "Common preview configurations",
        description = "Examples of MediaPreviewOptions for different scenarios",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { input: PreviewConfigRequest ->
            val configs = mapOf(
                "thumbnails" to PreviewConfigExample(
                    useCase = "List view thumbnails",
                    configurations = listOf(
                        """
                        // Small thumbnail for lists
                        MediaPreviewOptions(
                            sizeInPixels = 100,
                            type = MediaType.Image.JPEG,
                            quality = 0.7  // Lower quality for small size
                        )
                        """.trimIndent()
                    ),
                    description = "Small, low-quality images for list views and cards"
                ),

                "responsive" to PreviewConfigExample(
                    useCase = "Responsive web images",
                    configurations = listOf(
                        """
                        // Multiple sizes for responsive design
                        MediaPreviewOptions(sizeInPixels = 400),   // Mobile
                        MediaPreviewOptions(sizeInPixels = 800),   // Tablet
                        MediaPreviewOptions(sizeInPixels = 1200),  // Desktop
                        MediaPreviewOptions(sizeInPixels = 2400)   // Retina displays
                        """.trimIndent()
                    ),
                    description = "Generate multiple sizes for srcset in responsive images"
                ),

                "formats" to PreviewConfigExample(
                    useCase = "Multiple formats for browser support",
                    configurations = listOf(
                        """
                        // WebP for modern browsers
                        MediaPreviewOptions(
                            sizeInPixels = 800,
                            type = MediaType.Image.WebP,
                            quality = 0.85
                        ),

                        // JPEG fallback for older browsers
                        MediaPreviewOptions(
                            sizeInPixels = 800,
                            type = MediaType.Image.JPEG,
                            quality = 0.85
                        )
                        """.trimIndent()
                    ),
                    description = "Serve WebP to modern browsers, JPEG as fallback"
                ),

                "square" to PreviewConfigExample(
                    useCase = "Square thumbnails (profiles, avatars)",
                    configurations = listOf(
                        """
                        // Force 1:1 aspect ratio
                        MediaPreviewOptions(
                            sizeInPixels = 200,
                            forceRatio = 1.0,  // Square
                            type = MediaType.Image.JPEG,
                            quality = 0.85
                        )
                        """.trimIndent()
                    ),
                    description = "Crop/pad images to perfect squares for avatars"
                ),

                "widescreen" to PreviewConfigExample(
                    useCase = "Hero images and banners",
                    configurations = listOf(
                        """
                        // 16:9 widescreen format
                        MediaPreviewOptions(
                            sizeInPixels = 1200,
                            forceRatio = 16.0 / 9.0,
                            type = MediaType.Image.JPEG,
                            quality = 0.9  // Higher quality for hero images
                        )
                        """.trimIndent()
                    ),
                    description = "Enforce widescreen aspect ratio for banners"
                ),

                "quality" to PreviewConfigExample(
                    useCase = "Quality tiers for different uses",
                    configurations = listOf(
                        """
                        // High quality for display
                        MediaPreviewOptions(
                            sizeInPixels = 1200,
                            quality = 0.95
                        ),

                        // Medium quality for cards
                        MediaPreviewOptions(
                            sizeInPixels = 400,
                            quality = 0.85
                        ),

                        // Low quality for thumbnails
                        MediaPreviewOptions(
                            sizeInPixels = 100,
                            quality = 0.7
                        )
                        """.trimIndent()
                    ),
                    description = "Balance quality vs file size for different contexts"
                )
            )

            configs[input.configType] ?: configs["thumbnails"]!!
        }
    )

    /**
     * POST /media/processing-strategies
     *
     * Explains synchronous vs asynchronous image processing and when to use each.
     */
    val processingStrategies = path.path("media").path("processing-strategies").post bind ApiHttpHandler(
        summary = "Processing strategies",
        description = "Compares synchronous and asynchronous image processing approaches",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            ProcessingStrategiesResponse(
                strategies = listOf(
                    ProcessingStrategy(
                        name = "Synchronous (Interceptors)",
                        timing = "During API request - blocks response",
                        setup = """
                            val products = database
                                .table<Product>()
                                .interceptImagesForProcessing(
                                    MediaPreviewOptions(sizeInPixels = 200),
                                    MediaPreviewOptions(sizeInPixels = 800),
                                    makePath = { it.path { it.photo } }
                                )
                        """.trimIndent(),
                        pros = listOf(
                            "Previews available immediately after upload",
                            "Guaranteed processing before response",
                            "Simpler to implement",
                            "No separate task management needed"
                        ),
                        cons = listOf(
                            "Slower API responses",
                            "Can timeout on large images",
                            "Blocks the request thread",
                            "Not suitable for user-facing uploads"
                        ),
                        bestFor = "Small images, admin interfaces, when immediate availability is critical"
                    ),

                    ProcessingStrategy(
                        name = "Asynchronous (Background Tasks)",
                        timing = "After API response - non-blocking",
                        setup = """
                            // IMPORTANT: Task must be bound to a path!
                            val processProductImages = path.path("tasks").path("process-images").task bind
                                processImagesInBackground(
                                    info = productEndpoints.info,
                                    MediaPreviewOptions(sizeInPixels = 200),
                                    MediaPreviewOptions(sizeInPixels = 800),
                                    timeout = 5.minutes,
                                    makePath = { it.path { it.photo } }
                                )
                        """.trimIndent(),
                        pros = listOf(
                            "Fast API responses",
                            "Can handle large images",
                            "Doesn't block request threads",
                            "Better user experience for uploads",
                            "Can retry on failure"
                        ),
                        cons = listOf(
                            "Previews not immediately available",
                            "Need to handle missing previews in UI",
                            "Requires task infrastructure",
                            "More complex to set up"
                        ),
                        bestFor = "User-facing uploads, large images, high-traffic applications"
                    )
                ),
                recommendation = """
                    General guideline:
                    - Use SYNCHRONOUS for: Admin interfaces, small images, low traffic
                    - Use ASYNCHRONOUS for: User uploads, large images, production apps

                    For best UX:
                    1. Show original image immediately after upload
                    2. Process in background
                    3. Replace with optimized preview when ready
                    4. Fall back to original if previews aren't ready
                """.trimIndent()
            )
        }
    )

    /**
     * POST /media/preview-selection
     *
     * Demonstrates the intelligent preview selection algorithm.
     */
    val previewSelection = path.path("media").path("preview-selection").post bind ApiHttpHandler(
        summary = "Preview selection algorithm",
        description = "Shows how ServerFileWithMetadata intelligently selects the best preview",
        auth = noAuth,
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            PreviewSelectionResponse(
                howItWorks = """
                    The previews() method on ServerFileWithMetadata intelligently selects
                    the best preview based on:

                    1. Supported media types (prefer WebP, fall back to JPEG/PNG)
                    2. Minimum required dimensions
                    3. Scoring system that penalizes undersized previews

                    Algorithm:
                    - Filters by supported format
                    - Calculates how well each preview meets size requirements
                    - Penalizes undersized previews (adds 2000px to score)
                    - Selects preview with lowest penalty score
                """.trimIndent(),

                example = """
                    // Get best preview for web display
                    val displayImage = product.photo.previews(
                        supportedTypes = setOf(
                            MediaType.Image.WebP,  // Prefer WebP
                            MediaType.Image.JPEG   // Fall back to JPEG
                        ),
                        preferredMinimumWidth = 800,
                        preferredMinimumHeight = 600
                    ).firstOrNull()?.file ?: product.photo.original

                    // This will:
                    // 1. Try WebP previews first
                    // 2. Prefer preview >= 800x600
                    // 3. Fall back to smaller preview if needed
                    // 4. Use JPEG if no WebP available
                    // 5. Use original if no suitable preview exists
                """.trimIndent(),

                scenarios = listOf(
                    PreviewScenario(
                        context = "Mobile thumbnail",
                        code = """
                            photo.previews(
                                supportedTypes = setOf(MediaType.Image.JPEG),
                                preferredMinimumWidth = 100
                            ).firstOrNull()
                        """.trimIndent(),
                        result = "Selects smallest preview >= 100px"
                    ),

                    PreviewScenario(
                        context = "Responsive image srcset",
                        code = """
                            val previews = photo.previews(
                                supportedTypes = setOf(MediaType.Image.WebP, MediaType.Image.JPEG)
                            ).toList()

                            // Generate srcset
                            val srcset = previews.joinToString(", ") {
                                "${'$'}{it.file.url} ${'$'}{it.width}w"
                            }
                        """.trimIndent(),
                        result = "All previews in preferred formats for responsive images"
                    ),

                    PreviewScenario(
                        context = "Retina display",
                        code = """
                            photo.previews(
                                supportedTypes = setOf(MediaType.Image.WebP),
                                preferredMinimumWidth = 1600  // 800px * 2 for retina
                            ).firstOrNull()
                        """.trimIndent(),
                        result = "Selects preview suitable for high-DPI displays"
                    )
                )
            )
        }
    )
}

// Request/Response models

@Serializable
data class MediaConceptsResponse(
    val keyFeatures: List<String>,
    val dataModel: String,
    val processingOptions: String
)

@Serializable
data class PreviewConfigRequest(
    val configType: String
)

@Serializable
data class PreviewConfigExample(
    val useCase: String,
    val configurations: List<String>,
    val description: String
)

@Serializable
data class ProcessingStrategiesResponse(
    val strategies: List<ProcessingStrategy>,
    val recommendation: String
)

@Serializable
data class ProcessingStrategy(
    val name: String,
    val timing: String,
    val setup: String,
    val pros: List<String>,
    val cons: List<String>,
    val bestFor: String
)

@Serializable
data class PreviewSelectionResponse(
    val howItWorks: String,
    val example: String,
    val scenarios: List<PreviewScenario>
)

@Serializable
data class PreviewScenario(
    val context: String,
    val code: String,
    val result: String
)
