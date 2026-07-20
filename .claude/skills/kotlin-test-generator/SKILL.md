---
name: kotlin-test-generator
description: Generates Kotlin unit tests following project naming conventions and patterns. Creates test classes with proper setup, assertions, and error handling using kotest and coroutine patterns.
invocation: manual
context: project
allowed-tools: Write
---

# Kotlin Test Generator

This skill generates Kotlin unit tests that follow your project's established patterns and naming conventions. It creates comprehensive test classes with proper setup, assertions, and structured testing practices.

## Test Structure Pattern

### Class Naming Convention
- **Format**: `[FunctionName/ClassName]Test`
- **Example**: `CreateCategoryWithNameTest`, `UpdateUserProfileTest`, `DeleteProductTest`

### Test File Organization
```kotlin
class YourFeatureTest {
    private val repo = FakeRepository()
    private val useCase = DefaultImplementation(repo)

    @Test
    fun `description of what should happen`() = runTest {
        // Arrange
        val input = "someValue"

        // Act & Assert
        useCase(input).shouldBeFailure {
            it.shouldBeInstanceOf<CustomException.SpecificError>()
        }
    }
}
```

## Testing Patterns to Follow

### 1. Setup Dependencies
Always initialize with:
- Fake/Mock repositories using the `Fake[RepositoryName]` pattern
- Use case instances using the `Default[UseCaseName]` pattern

```kotlin
private val repo = Fake[Name]Repository()
private val useCase = Default[UseCaseName](repo)
```

### 2. Test Naming Convention
Use descriptive, backtick-quoted function names that clearly state:
- What input is being tested
- What exception or result is expected

**Good examples:**
- `` `blank names should return InvalidName exception` ``
- `` `empty input should return InvalidInput exception` ``
- `` `unique value should create Entity` ``
- `` `duplicate values should return DuplicateError exception` ``

### 3. Test Patterns

#### Failure Case Testing
```kotlin
@Test
fun `invalid input should return CustomException`() = runTest {
    useCase("invalidInput").shouldBeFailure {
        it.shouldBeInstanceOf<CustomException.SpecificError>()
    }
}
```

#### Success Case Testing
```kotlin
@Test
fun `valid input should create Entity`() = runTest {
    val result = useCase("validInput")

    result.shouldBeSuccess { entity ->
        entity.property shouldBe expectedValue
    }
}
```

#### Sequential Operations
```kotlin
@Test
fun `second operation with duplicate should fail`() = runTest {
    useCase("value")

    useCase("value").shouldBeFailure {
        it.shouldBeInstanceOf<CustomException.Duplicate>()
    }
}
```

### 4. Assertion Patterns
Use these assertion styles consistently:
- `shouldBeFailure { it.shouldBeInstanceOf<ExceptionType>() }` - for error cases
- `shouldBeSuccess { entity -> entity.property shouldBe value }` - for success cases
- `shouldBe`, `shouldContain`, `shouldNotBeNull()` - for property assertions

### 5. Coroutine Testing
- Always use `runTest { }` for suspend functions
- Allows testing of async operations directly
- No need for additional coroutine builders

## Imports to Include
```kotlin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.arrow.result.shouldBeFailure
import io.kotest.assertions.arrow.result.shouldBeSuccess
import kotlinx.coroutines.test.runTest
import org.junit.Test
```

## Generation Guidelines

When asked to generate tests:

1. **Identify the Use Case**: Determine the feature/function being tested
2. **Create Test Class**: Name it `[FeatureName]Test` following CamelCase
3. **Setup Dependencies**: Initialize fakes and use case with proper naming
4. **Generate Test Cases**: Cover:
  - Happy path (success scenario)
  - Failure cases (each exception type)
  - Edge cases (empty, null, boundary values)
  - Business rule violations (duplicates, constraints)
5. **Use Descriptive Names**: Make test names self-documenting
6. **Follow Formatting**: Maintain consistent spacing and organization

## Example Variations

### For Validation Use Cases
```kotlin
@Test
fun `[invalid condition] should return [ExceptionType]`() = runTest {
    useCase(invalidInput).shouldBeFailure {
        it.shouldBeInstanceOf<CustomException.ValidationError>()
    }
}
```

### For CRUD Operations
```kotlin
@Test
fun `[operation] with [condition] should [expected outcome]`() = runTest {
    val result = useCase(validInput)

    result.shouldBeSuccess { entity ->
        entity.id shouldBe expectedId
        entity.status shouldBe expectedStatus
    }
}
```

### For Repository Operations
```kotlin
@Test
fun `[query] should return [expected data]`() = runTest {
    val result = useCase.execute(query)

    result.shouldBeSuccess { items ->
        items.size shouldBe expectedCount
        items.first().property shouldBe expectedValue
    }
}
```

## Tips for Better Tests

- **Be Specific**: Test one behavior per test function
- **Use Realistic Data**: Use meaningful test values instead of generic placeholders
- **Clear Assertions**: Make it obvious what's being verified
- **Consistent Style**: Match the pattern shown in examples above
- **Document Intent**: Test names should explain why they're important

---

When generating tests, ensure they:
1. Follow the exact naming convention shown
2. Match the structure and patterns above
3. Are properly formatted and indented
4. Include all necessary imports
5. Cover both success and failure paths
6. Use meaningful test data
