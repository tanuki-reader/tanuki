---
name: kdoc-generator
description: Generates comprehensive KDoc documentation for Kotlin classes, functions, properties, and interfaces. Creates professional API documentation following Kotlin conventions and best practices.
invocation: manual
context: project
allowed-tools: Write
---

# KDoc Documentation Generator

This skill generates professional Kotlin KDoc documentation that follows Kotlin conventions and best practices. It creates clear, comprehensive documentation for classes, functions, properties, interfaces, and more.

## KDoc Structure & Format

### Basic Anatomy
```kotlin
/**
 * Brief description of what this does (one line summary).
 *
 * More detailed explanation if needed. Can span multiple paragraphs.
 * Explain the purpose, behavior, and any important context.
 *
 * @param paramName Description of this parameter
 * @param anotherParam What this parameter is used for
 * @return Description of what is returned
 * @throws ExceptionType When this exception might be thrown
 * @see RelatedClass For more information
 * @author Author name (optional)
 * @since 1.0
 */
```

## Documentation for Different Elements

### 1. Class Documentation

#### Data Classes
```kotlin
/**
 * Represents a user in the system.
 *
 * This data class encapsulates user information including personal details
 * and account status. It is used throughout the application for user-related operations.
 *
 * @property id The unique identifier for the user
 * @property name The user's full name
 * @property email The user's email address
 * @property createdAt The timestamp when the user was created
 *
 * @see UserRepository for database operations
 * @since 1.0
 */
data class User(
    val id: String,
    val name: String,
    val email: String,
    val createdAt: LocalDateTime
)
```

#### Regular Classes
```kotlin
/**
 * Manages all user-related business logic and operations.
 *
 * This class serves as the main entry point for user management functionality.
 * It handles user creation, updates, deletions, and queries. All operations
 * are executed asynchronously and may throw domain-specific exceptions.
 *
 * @see User for the user data model
 * @see UserRepository for persistence layer
 * @since 1.0
 */
class UserManager(
    private val repository: UserRepository,
    private val validator: UserValidator
) {
    // implementation
}
```

#### Exception Classes
```kotlin
/**
 * Sealed class for all user-related errors and exceptions.
 *
 * This sealed class defines a hierarchy of possible errors that can occur
 * during user operations. Use pattern matching to handle specific error types.
 *
 * @since 1.0
 */
sealed class UserException(message: String) : Exception(message) {
    /**
     * Thrown when a user with the given ID is not found.
     *
     * @property userId The ID of the user that was not found
     */
    data class UserNotFound(val userId: String) :
        UserException("User with ID $userId not found")

    /**
     * Thrown when user input validation fails.
     *
     * @property field The field that failed validation
     * @property reason The reason why validation failed
     */
    data class ValidationError(val field: String, val reason: String) :
        UserException("Validation failed for $field: $reason")
}
```

### 2. Function/Method Documentation

#### Suspend Functions (Coroutines)
```kotlin
/**
 * Creates a new user in the system.
 *
 * This is a suspending function that performs asynchronous user creation.
 * The operation may take several seconds to complete due to I/O operations.
 * If the user already exists, a [UserException.DuplicateUser] exception is thrown.
 *
 * @param name The user's full name (must not be blank)
 * @param email The user's email address (must be valid format)
 * @return The newly created [User] object
 * @throws UserException.ValidationError if name or email is invalid
 * @throws UserException.DuplicateUser if user with this email already exists
 *
 * @see User for the user model
 * @since 1.0
 *
 * Example:
 * ```
* val user = userManager.createUser("John Doe", "john@example.com")
* println(user.name)
* ```
*/
suspend fun createUser(name: String, email: String): User
```

#### Regular Functions
```kotlin
/**
 * Validates if an email address follows the correct format.
 *
 * Checks for standard email format requirements:
 * - Contains exactly one '@' symbol
 * - Has characters before and after the '@'
 * - Contains a valid domain with a TLD
 *
 * @param email The email address to validate
 * @return `true` if the email is valid, `false` otherwise
 *
 * @since 1.0
 *
 * Example:
 * ```
* val isValid = isValidEmail("user@example.com")
* ```
*/
fun isValidEmail(email: String): Boolean
```

#### Extension Functions
```kotlin
/**
 * Converts this [User] to a display-friendly format.
 *
 * This extension function creates a formatted string suitable for UI display,
 * masking sensitive information while showing relevant user details.
 *
 * @return A formatted string like "John Doe (john@...)"
 *
 * @since 1.0
 */
fun User.toDisplayString(): String
```

#### Functions with Multiple Return Types
```kotlin
/**
 * Attempts to parse a user from the given JSON string.
 *
 * Returns a [Result] that contains either the parsed [User] on success
 * or a [UserException] on failure. This approach avoids throwing exceptions
 * and allows for cleaner error handling.
 *
 * @param json The JSON string to parse
 * @return A [Result] containing the parsed user or an exception
 *
 * @since 1.0
 */
fun parseUser(json: String): Result<User>
```

### 3. Property Documentation

#### Class Properties
```kotlin
/**
 * The repository used for user persistence operations.
 *
 * This should be injected during construction and remains immutable
 * throughout the lifetime of this manager instance.
 */
private val repository: UserRepository
```

#### Interface Properties
```kotlin
/**
 * The unique identifier for this user.
 *
 * This is guaranteed to be unique across all users in the system.
 */
val id: String
```

### 4. Interface Documentation

```kotlin
/**
 * Defines the contract for user persistence operations.
 *
 * Implementations of this interface handle all database interactions
 * for user data. All operations are asynchronous and may throw
 * [UserException] subclasses on failure.
 *
 * @see DefaultUserRepository for the default implementation
 * @since 1.0
 */
interface UserRepository {
    /**
     * Retrieves a user by their unique identifier.
     *
     * @param id The user's ID to look up
     * @return The user if found
     * @throws UserException.UserNotFound if no user exists with this ID
     */
    suspend fun getUserById(id: String): User

    /**
     * Saves or updates a user in the database.
     *
     * If the user doesn't exist, it will be created.
     * If it does exist, all fields will be updated.
     *
     * @param user The user to save
     * @return The saved user with any server-generated fields updated
     */
    suspend fun saveUser(user: User): User
}
```

## Special Tags and Syntax

### Parameter Documentation
```kotlin
/**
 * @param paramName Description of the parameter
 * @param anotherParam Description of another parameter
 */
```

### Return Values
```kotlin
/**
 * @return Description of what is returned
 * @return A [User] object with populated fields
 */
```

### Exceptions
```kotlin
/**
 * @throws ExceptionType When this exception might be thrown
 * @throws UserException.NotFound when user doesn't exist
 * @throws IllegalArgumentException if email format is invalid
 */
```

### Cross-References
```kotlin
/**
 * @see User for the user data model
 * @see UserRepository for persistence operations
 * @see UserValidator for validation logic
 */
```

### Deprecation
```kotlin
/**
 * Retrieves a user by ID (deprecated).
 *
 * @deprecated Use [getUserById] with the new Result-based API instead
 * @since 1.0
 */
@Deprecated("Use getUserById instead", ReplaceWith("getUserById(id)"))
fun getUser(id: String): User?
```

### Code Examples
```kotlin
/**
 * Creates a new user in the system.
 *
 * @return The newly created user
 *
 * Example:
 * ```
* val user = userManager.createUser("John Doe", "john@example.com")
* println("Created user: ${user.name}")
* ```
*/
suspend fun createUser(name: String, email: String): User
```

## Documentation Best Practices

### 1. Write Clear Summaries
❌ Bad: `The user class`
✅ Good: `Represents a user in the system with authentication and profile information`

### 2. Document Parameters Thoroughly
❌ Bad: `@param name Name`
✅ Good: `@param name The user's full name (must not be blank, max 255 characters)`

### 3. Explain Return Values
❌ Bad: `@return A User`
✅ Good: `@return The newly created User with ID and timestamps populated by the server`

### 4. Document Exceptions
Always explain when and why exceptions are thrown:
```kotlin
/**
 * @throws UserException.ValidationError if the email format is invalid
 * @throws UserException.DuplicateUser if a user with this email already exists
 */
```

### 5. Use Links for Cross-References
```kotlin
/**
 * @see [User] for the user data model
 * @see [UserRepository] for database operations
 */
```

### 6. Include Examples for Complex Functions
```kotlin
/**
 * Example:
 * ```
* val result = parser.parseUser(jsonString)
* result.onSuccess { user ->
*     println("Parsed user: ${user.name}")
* }
* ```
*/
```

## Documentation Patterns

### For Use Cases/Interactors
```kotlin
/**
 * Use case for creating a new user account.
 *
 * Orchestrates the user creation process including validation,
 * persistence, and notification. Ensures all business rules are
 * enforced before creating the user.
 *
 * @see User
 * @since 1.0
 */
class CreateUserUseCase(
    private val repository: UserRepository,
    private val validator: UserValidator
) {
    /**
     * Executes the user creation process.
     *
     * @param name The user's full name
     * @param email The user's email address
     * @return The newly created user
     * @throws UserException if validation or creation fails
     */
    suspend operator fun invoke(name: String, email: String): User
}
```

### For Repository/DAO Classes
```kotlin
/**
 * SQL database implementation of [UserRepository].
 *
 * Uses Room for persistence and handles all database operations
 * for user data. Automatically handles migrations and schema updates.
 *
 * @see UserRepository
 * @since 1.0
 */
class UserRepositoryImpl(
    private val database: UserDatabase
) : UserRepository {
    // implementation
}
```

### For Validator Classes
```kotlin
/**
 * Validates user input and business constraints.
 *
 * Performs comprehensive validation including:
 * - Format validation (email, phone, etc.)
 * - Business rule validation (uniqueness, constraints)
 * - Domain-specific validation
 *
 * @since 1.0
 */
class UserValidator {
    /**
     * Validates a user's email address.
     *
     * @param email The email to validate
     * @throws UserException.InvalidEmail if the email format is invalid
     */
    fun validateEmail(email: String)
}
```

## Formatting Guidelines

### Indentation
- Use proper indentation within code blocks
- Align parameters and descriptions for readability

### Line Length
- Keep descriptions concise but complete
- Break long descriptions into multiple sentences or paragraphs

### Emphasis
- Use `[ClassName]` for class references
- Use `code` for code snippets
- Use **bold** for important concepts sparingly

### Structure
1. One-line summary (brief description)
2. Blank line
3. Detailed explanation (if needed)
4. Blank line
5. `@param` tags
6. `@return` tag
7. `@throws` tags
8. `@see` tags
9. Examples (if needed)
10. `@since` tag

## Generation Guidelines

When generating KDoc documentation:

1. **Identify the Element**: Determine what is being documented (class, function, property, etc.)
2. **Write Summary**: Create a clear, one-line description
3. **Add Details**: Provide context and explain behavior
4. **Document Parameters**: Describe each parameter's purpose and constraints
5. **Explain Returns**: Clearly state what is returned
6. **List Exceptions**: Document all possible exceptions
7. **Add Examples**: Include examples for complex operations
8. **Use References**: Link to related classes and interfaces
9. **Include Metadata**: Add `@since`, `@author` if relevant

## What to Document

✅ **Always document:**
- Public classes and interfaces
- Public functions and methods
- Public properties
- Exceptions and error cases
- Complex logic or non-obvious behavior

✅ **Consider documenting:**
- Protected members (if subclassing is expected)
- Private functions (if they're complex)
- Internal APIs used by other teams

❌ **Usually skip:**
- Private implementation details
- Obvious getters/setters
- Simple trivial functions

---

When generating documentation, ensure it:
1. Follows Kotlin/KDoc conventions exactly
2. Is clear and professionally written
3. Includes all relevant tags (@param, @return, @throws, @see)
4. Includes examples for complex functions
5. Uses proper formatting and cross-references
6. Explains the "why" not just the "what"
