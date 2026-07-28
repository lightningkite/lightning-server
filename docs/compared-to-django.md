# Lightning Server for Django Developers

<!-- by Claude -->

Last updated January 2025 (`version-5`)

If you're coming from Django, welcome! This guide maps your existing Django knowledge to Lightning Server concepts.
While the frameworks have different philosophies, many patterns will feel familiar.

## Philosophy Differences

Before diving in, it's helpful to understand the key philosophical differences:

| Aspect           | Django                              | Lightning Server                           |
|------------------|-------------------------------------|--------------------------------------------|
| Language         | Python                              | Kotlin                                     |
| Primary focus    | Full-stack web framework            | API-first backend framework                |
| Database default | SQL (PostgreSQL, MySQL, SQLite)     | MongoDB (with SQL support)                 |
| Templating       | Built-in (Django templates, Jinja2) | API-focused (JSON responses)               |
| Admin panel      | Built-in                            | Provided via lightning-server-kiteui       |
| Type safety      | Runtime (with type hints)           | Compile-time (Kotlin's type system)        |
| Deployment       | WSGI/ASGI servers                   | Multiple engines (Ktor, Netty, AWS Lambda) |

## Feature Comparison Quick Reference

| Django Feature           | Lightning Server Equivalent                                         |
|--------------------------|---------------------------------------------------------------------|
| Django Admin             | Admin UI in lightning-server-kiteui                                 |
| Django ORM               | Type-safe query DSL with `condition {}` and `modification {}`       |
| Models                   | `@Serializable` data classes with `@GenerateDataClassPaths`         |
| Signals                  | Database lifecycle hooks (`postCreate`, `postChange`, `postDelete`) |
| Named URLs / `reverse()` | Endpoint constants with type-safe path construction                 |
| Forms                    | KiteUI form renderers (40+ built-in types)                          |
| Django REST Framework    | Typed endpoints with auto SDK generation                            |
| Middleware               | `HttpInterceptor` system                                            |
| Authentication           | JWT, email magic links, PIN codes, SMS, OAuth                       |
| Settings                 | `settings.json` with typed data classes                             |

---

## The Admin Panel

One of Django's most beloved features is its auto-generated admin interface. Lightning Server has this too, and it's
quite capable.

### Django Admin

```python
# admin.py
from django.contrib import admin
from .models import Post

@admin.register(Post)
class PostAdmin(admin.ModelAdmin):
    list_display = ['title', 'author', 'created_at']
    search_fields = ['title', 'body']
    list_filter = ['author', 'created_at']
```

### Lightning Server Admin (via lightning-server-kiteui)

The admin UI is automatically generated from your server schema at runtime. You customize it through annotations on your
models:

```kotlin
@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["title", "author", "updatedAt"])
@Description("A blog post in the system")
data class Post(
    override val _id: Uuid = Uuid.random(),
    @Description("The post title")
    val title: String,
    @Description("Email of the author")
    val author: String,
    @Multiline @MimeType("text/html")
    val body: String,
    @AdminHidden
    val privateNotes: String? = null,
    @References(User::class)
    val authorId: Uuid,
    val updatedAt: Instant = Clock.System.now()
) : HasId<Uuid>
```

**Admin Features:**

- Full CRUD auto-generated from server schema
- Advanced filtering with full Condition DSL (more powerful than Django's)
- Multi-field sorting and column selection
- Real-time updates via WebSocket
- CSV import/export (up to 100k items)
- Bulk delete with confirmation
- Foreign key pickers with nested search
- Permission-aware display
- Form generation for 40+ types
- Works on mobile (Kotlin Multiplatform)

Available annotations:

- `@AdminTableColumns([...])` - Columns to show in table view
- `@AdminHidden` - Hide field from admin panel
- `@Multiline` - Render as textarea
- `@MimeType("...")` - Specify content type for rich fields
- `@References(Model::class)` - Foreign key picker
- `@MultipleReferences(Model::class)` - Multi-select foreign key picker

---

## Models and the ORM

### Django Models

```python
# models.py
from django.db import models

class Post(models.Model):
    title = models.CharField(max_length=200)
    author = models.EmailField()
    body = models.TextField()
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['-created_at']
```

### Lightning Server Models

```kotlin
import kotlinx.serialization.*
import com.lightningkite.services.database.HasId
import com.lightningkite.services.data.GenerateDataClassPaths
import kotlin.uuid.Uuid
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock

@Serializable
@GenerateDataClassPaths  // Required for query DSL
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
) : HasId<Uuid>
```

**Key differences:**

- Models are Kotlin data classes with `@Serializable`
- `@GenerateDataClassPaths` enables the type-safe query DSL
- Primary key is explicitly defined via `HasId<T>`
- No migrations needed for MongoDB (schemaless)

### Querying

**Django:**

```python
# Get all posts by an author
posts = Post.objects.filter(author='user@example.com')

# Chain filters
posts = Post.objects.filter(
    author='user@example.com',
    created_at__gte=some_date
).order_by('-created_at')

# Update
Post.objects.filter(title='Test').update(title='Updated Title')

# Delete
Post.objects.filter(author='user@example.com').delete()
```

**Lightning Server:**

```kotlin
val posts = postTable()

// Get all posts by an author
posts.find(condition { it.author eq "user@example.com" }).toList()

// Chain conditions
posts.find(
    condition {
        (it.author eq "user@example.com") and (it.createdAt gte someDate)
    },
    orderBy = listOf(SortPart(Post::createdAt, ascending = false))
).toList()

// Update
posts.updateMany(
    condition { it.title eq "Test" },
    modification { it.title assign "Updated Title" }
)

// Delete
posts.deleteMany(condition { it.author eq "user@example.com" })
```

### Common Query Operations

| Django                         | Lightning Server                                |
|--------------------------------|-------------------------------------------------|
| `filter(field=value)`          | `condition { it.field eq value }`               |
| `exclude(field=value)`         | `condition { it.field neq value }`              |
| `filter(field__gt=value)`      | `condition { it.field gt value }`               |
| `filter(field__gte=value)`     | `condition { it.field gte value }`              |
| `filter(field__lt=value)`      | `condition { it.field lt value }`               |
| `filter(field__in=[...])`      | `condition { it.field inside listOf(...) }`     |
| `filter(field__contains='x')`  | `condition { it.field contains "x" }`           |
| `filter(field__icontains='x')` | `condition { it.field containsIgnoreCase "x" }` |
| `Q(a) \| Q(b)`                 | `(conditionA) or (conditionB)`                  |
| `Q(a) & Q(b)`                  | `(conditionA) and (conditionB)`                 |

---

## Signals (Lifecycle Hooks)

Django signals let you run code when models are saved, deleted, etc. Lightning Server has lifecycle hooks that serve the
same purpose.

### Django Signals

```python
from django.db.models.signals import post_save, post_delete
from django.dispatch import receiver

@receiver(post_save, sender=Post)
def post_saved(sender, instance, created, **kwargs):
    if created:
        print(f"New post created: {instance.title}")
    else:
        print(f"Post updated: {instance.title}")

@receiver(post_delete, sender=Post)
def post_deleted(sender, instance, **kwargs):
    print(f"Post deleted: {instance.title}")
```

### Lightning Server Lifecycle Hooks

```kotlin
val collection = postTable()
    .interceptCreate { value ->
        // Modify value before creation
        println("About to insert: ${value.title}")
        value.copy(title = value.title + " (New)")
    }
    .postCreate { value ->
        println("Post created: ${value.title}")
    }
    .postChange { value ->
        println("Post updated: ${value.title}")
    }
    .postDelete { value ->
        println("Post deleted: ${value.title}")
    }
```

**Available hooks:**

- `interceptCreate` - Modify value before creation
- `interceptChange` - Modify a modification before application
- `postCreate` - Called after successful creation
- `postChange` - Called after successful update
- `postDelete` - Called after successful deletion
- `postNewValue` - Called after creation or update

Lightning Server also has a full **Notifications System** with PubSub support (Redis, MQTT, AWS SNS backends) for more
complex event-driven architectures.

---

## URL Routing / Named URLs

### Django URLs

```python
# urls.py
from django.urls import path
from . import views

urlpatterns = [
    path('posts/', views.post_list, name='post-list'),
    path('posts/<int:pk>/', views.post_detail, name='post-detail'),
    path('users/<str:user_id>/posts/', views.user_posts, name='user-posts'),
]

# Using reverse()
from django.urls import reverse
url = reverse('post-detail', args=[123])  # '/posts/123/'
```

### Lightning Server Endpoints

```kotlin
object Server : ServerBuilder() {
    // Endpoints are stored as constants
    val postList = path.path("posts").get bind HttpHandler {
        HttpResponse.json(postTable().find(condition { it.always }).toList())
    }

    val postDetail = path.path("posts").arg<Int>("pk").get bind HttpHandler { request ->
        val pk = request.path.arg1
        val post = postTable().get(pk) ?: throw NotFoundException()
        HttpResponse.json(post)
    }

    val userPosts = path.path("users").arg<String>("userId").path("posts").get bind HttpHandler { request ->
        val userId = request.path.arg1
        HttpResponse.json(
            postTable().find(condition { it.author eq userId }).toList()
        )
    }
}

// Type-safe URL construction
val url = Server.postDetail.path.toString(123)  // "/posts/123"
```

**Key differences:**

- Endpoints are stored as constants, enabling type-safe references
- Path arguments are type-safe (`arg<Int>("pk")` ensures `arg1` is an `Int`)
- No string-based lookup; you reference the endpoint directly

---

## Views / Endpoints

### Django Views

```python
# views.py
from django.http import JsonResponse
from django.views import View

class PostListView(View):
    def get(self, request):
        posts = list(Post.objects.values())
        return JsonResponse(posts, safe=False)

    def post(self, request):
        data = json.loads(request.body)
        post = Post.objects.create(**data)
        return JsonResponse({'id': post.id}, status=201)
```

### Lightning Server Endpoints

```kotlin
object PostEndpoints : ServerBuilder() {
    val list = path.get bind HttpHandler {
        HttpResponse.json(postTable().find(condition { it.always }).toList())
    }

    val create = path.post bind HttpHandler { request ->
        val data = request.body?.parse<Post>() ?: throw BadRequestException("Missing body")
        val created = postTable().insertOne(data)
        HttpResponse(
            body = TypedData.json(mapOf("id" to created._id)),
            status = HttpStatus.Created
        )
    }
}
```

---

## Django REST Framework Equivalent

DRF provides serializers, viewsets, and automatic API documentation. Lightning Server's typed endpoints offer similar
capabilities with compile-time type safety.

### Django REST Framework

```python
# serializers.py
from rest_framework import serializers

class PostSerializer(serializers.ModelSerializer):
    class Meta:
        model = Post
        fields = ['id', 'title', 'author', 'body', 'created_at']

# views.py
from rest_framework import viewsets

class PostViewSet(viewsets.ModelViewSet):
    queryset = Post.objects.all()
    serializer_class = PostSerializer
    permission_classes = [IsAuthenticated]
```

### Lightning Server Typed Endpoints

```kotlin
@Serializable
data class CreatePostRequest(
    @StringLength(1, 200)
    val title: String,
    @EmailPattern
    val author: String,
    val body: String
)

object PostApi : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // Define model info with permissions
    val postInfo = database.modelInfo<User?, Post, Uuid>(
        auth = authOptions<User>(),
        tableName = "Post",
        permissions = {
            val user = authOrNull?.fetch()
            ModelPermissions(
                create = condition { it.author eq user?.email },
                read = condition { it.always },
                update = condition { it.author eq user?.email },
                delete = condition { it.author eq user?.email }
            )
        }
    )

    // Auto-generate full CRUD API
    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
```

This automatically creates:

- `GET /posts` - Query/list posts
- `POST /posts` - Create post
- `GET /posts/{id}` - Get post by ID
- `PUT /posts/{id}` - Replace post
- `PATCH /posts/{id}` - Modify post
- `DELETE /posts/{id}` - Delete post
- `POST /posts/count` - Count posts
- `POST /posts/aggregate` - Aggregate numeric fields

**Additional features:**

- OpenAPI/Swagger schema auto-generation
- Auto-generated TypeScript and Kotlin client SDKs
- WebSocket support for real-time updates
- Input validation via annotations

### Custom Typed Endpoint

```kotlin
val createPost = path.path("posts").post bind ApiHttpHandler<_, User?, CreatePostRequest, Post>(
    summary = "Create a new post",
    description = "Creates a new blog post. Requires authentication.",
    auth = authOptions<User>(),
    errorCases = listOf(
        LSError(http = 400, detail = "validation-failed", message = "Invalid post data"),
        LSError(http = 401, detail = "unauthorized", message = "Authentication required")
    ),
    implementation = { input ->
        val user = auth.fetch()
        val post = Post(
            title = input.title,
            author = user.email,
            body = input.body
        )
        postTable().insertOne(post)
        post
    }
)
```

---

## Middleware

### Django Middleware

```python
# middleware.py
class LoggingMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        print(f"Request: {request.path}")
        response = self.get_response(request)
        print(f"Response: {response.status_code}")
        return response
```

### Lightning Server Interceptors

```kotlin
val loggingInterceptor = HttpInterceptor { request, cont ->
    val start = Clock.System.now()
    println("Request: ${request.path}")
    val response = cont(request)
    val duration = Clock.System.now() - start
    println("Response: ${response.status} (${duration.inWholeMilliseconds}ms)")
    response
}

object Server : ServerBuilder() {
    init {
        install(loggingInterceptor)
        install(CorsInterceptor(corsSettings))
    }
}
```

Interceptors can:

- Modify requests before passing to handlers
- Short-circuit and return responses early
- Modify responses after handler execution
- Handle exceptions from downstream handlers

---

## Authentication

### Django Authentication

```python
from django.contrib.auth.decorators import login_required

@login_required
def protected_view(request):
    return JsonResponse({'user': request.user.email})
```

### Lightning Server Authentication

Lightning Server supports multiple authentication methods:

- JWT tokens
- Email magic links (PIN codes)
- SMS verification
- OAuth providers (Google, Apple, GitHub)
- Time-based OTP (TOTP)
- Known device authentication

```kotlin
@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: Uuid = Uuid.random(),
    override val email: String,
    override val hashedPassword: String = "",
    val isSuperUser: Boolean = false
) : HasId<Uuid>, HasEmail, HasPassword

object Server : ServerBuilder() {
    object UserAuth : PrincipalType<User, Uuid> {
        override val idSerializer = Uuid.serializer()
        override val subjectSerializer = User.serializer()
        override val name = "User"

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User =
            userTable().get(id) ?: throw NotFoundException()
    }

    // Protected endpoint
    val protectedEndpoint = path.path("protected").get bind ApiHttpHandler<_, User, Unit, String>(
        summary = "Protected Resource",
        auth = UserAuth.require(),
        implementation = {
            "Hello, ${auth.fetch().email}!"
        }
    )

    // Optional authentication
    val optionalAuthEndpoint = path.path("greeting").get bind ApiHttpHandler<_, User?, Unit, String>(
        summary = "Greeting",
        auth = UserAuth.require() or AuthRequirement.None,
        implementation = {
            val user = authOrNull?.fetch()
            if (user != null) "Hello, ${user.email}!" else "Hello, guest!"
        }
    )
}
```

---

## Settings / Configuration

### Django Settings

```python
# settings.py
DEBUG = True
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.postgresql',
        'NAME': 'mydb',
        'HOST': 'localhost',
    }
}
EMAIL_HOST = 'smtp.example.com'
```

### Lightning Server Settings

Settings are defined programmatically with typed defaults:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    val webUrl = setting("webUrl", "http://localhost:8080")
}
```

Configuration lives in `settings.json`:

```json
{
  "database": { "url": "mongodb://localhost:27017/mydb" },
  "cache": { "url": "ram" },
  "email": { "url": "console" },
  "webUrl": "http://localhost:8080"
}
```

**Key principle:** Lightning Server generates `settings.json` with working defaults on first run. Your application
should work out-of-the-box with the generated file.

**Additional features:**

- Encrypted settings files (OpenSSL)
- Chained configuration files (defaults inheritance)
- Properties file format support
- Type-safe access in code

---

## Forms

Django forms provide validation and HTML rendering. Lightning Server (via KiteUI) has a sophisticated form system
focused on programmatic form generation.

### Django Forms

```python
from django import forms

class PostForm(forms.ModelForm):
    class Meta:
        model = Post
        fields = ['title', 'author', 'body']
        widgets = {
            'body': forms.Textarea(attrs={'rows': 10}),
        }
```

### Lightning Server (KiteUI Forms)

Form rendering is handled by KiteUI with a priority-based renderer selection system:

```kotlin
@Serializable
data class PostForm(
    @StringLength(1, 200)
    val title: String,

    @EmailPattern
    val author: String,

    @Multiline
    @MimeType("text/html")
    val body: String
)
```

The admin panel and KiteUI automatically render appropriate form fields based on:

- Field type (String, Int, Boolean, Instant, etc.)
- Annotations (`@Multiline`, `@MimeType`, `@References`)
- Built-in renderers for 40+ types

---

## Database Backends

### Django Database Backends

- PostgreSQL
- MySQL
- SQLite
- Oracle

### Lightning Server Database Backends

- **MongoDB** (recommended, fully supported)
- **PostgreSQL** (partial support)
- **In-Memory** (for testing)
- **JSON Files** (for local development)

```json
// MongoDB
{ "database": { "url": "mongodb://localhost:27017/mydb" } }

// PostgreSQL
{ "database": { "url": "postgresql://user:pass@localhost:5432/mydb" } }

// In-Memory (testing)
{ "database": { "url": "ram" } }

// JSON Files (local development)
{ "database": { "url": "json://./data" } }
```

---

## Migrations

Django requires migrations for schema changes. Lightning Server's MongoDB support is schemaless, meaning:

- No migrations needed for field additions
- Field removals are handled gracefully
- Type changes may require data migration scripts

For PostgreSQL, schema changes would need manual handling (the PostgreSQL support is still in development).

---

## Testing

### Django Tests

```python
from django.test import TestCase, Client

class PostTests(TestCase):
    def test_create_post(self):
        client = Client()
        response = client.post('/api/posts/', {
            'title': 'Test',
            'body': 'Content'
        }, content_type='application/json')
        self.assertEqual(response.status_code, 201)
```

### Lightning Server Tests

```kotlin
class PostTests {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            JsonFileDatabase  // Load mock service implementations
        }
    }

    @Test
    fun testCreatePost() = runBlocking {
        val engine = LocalEngine(Server.build())
        val response = Server.createPost.test(
            engine,
            CreatePostRequest(title = "Test", author = "test@example.com", body = "Content")
        )
        assertEquals(HttpStatus.Created, response.status)
    }
}
```

Unit tests use mock services (`JsonFileDatabase`, RAM cache) to avoid external dependencies.

---

## Internationalization (i18n)

Django has built-in i18n support. Lightning Server, being API-focused, typically leaves translation to clients. For
server-side text (emails, notifications), you can implement i18n per-project:

```kotlin
// Example approach
object Messages {
    fun welcomeEmail(locale: String): String = when(locale) {
        "es" -> "Bienvenido!"
        "fr" -> "Bienvenue!"
        else -> "Welcome!"
    }
}
```

---

## Deployment

### Django Deployment

- WSGI servers (Gunicorn, uWSGI)
- ASGI servers (Daphne, Uvicorn)
- Traditional VM/container deployment

### Lightning Server Deployment

- **Ktor Engine** - Traditional server, good for development
- **Netty Engine** - High-performance traditional server
- **JDK Server Engine** - Pure JDK HTTP server
- **AWS Lambda** - Serverless with auto-generated Terraform

```kotlin
// Development (Ktor)
fun main() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

For AWS Lambda deployment, the framework generates Terraform configuration automatically, handling Lambda functions, API
Gateway, DynamoDB tables, and S3 buckets.

---

## Summary: Key Takeaways for Django Developers

1. **The admin panel exists and is powerful** - Don't miss `lightning-server-kiteui`'s auto-generated admin UI.

2. **Type safety is your friend** - Kotlin's type system catches errors at compile time that Django would catch at
   runtime (or not at all).

3. **Models are simpler** - Data classes with annotations, no complex ORM metaclasses.

4. **Query DSL is different but capable** - `condition { }` and `modification { }` replace Django's QuerySet API.

5. **API-first design** - Lightning Server excels at building APIs with auto-generated SDKs and documentation.

6. **Settings work out-of-the-box** - The generated `settings.json` should just work.

7. **Testing is straightforward** - Mock services make unit testing easy without external dependencies.

8. **Multiple deployment targets** - From traditional servers to serverless Lambda, choose what fits your needs.

---

## See Also

- [Setup Guide](setup.md) - Getting started with Lightning Server
- [Endpoints](endpoints.md) - HTTP endpoint patterns
- [Typed Endpoints](typed-endpoints.md) - Type-safe API development
- [Database](database.md) - Query DSL reference
- [Authentication](authentication.md) - Auth setup and patterns
- [Settings](settings.md) - Configuration management
