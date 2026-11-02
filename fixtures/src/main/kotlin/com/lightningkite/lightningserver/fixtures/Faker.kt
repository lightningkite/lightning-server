package com.lightningkite.lightningserver.fixtures

import kotlinx.datetime.LocalDate
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.lightningkite.services.data.*
import java.util.Locale
import kotlin.math.asin

public fun Random.nextDoubleNormal(): Double = sqrt(-2.0 * ln(nextDouble().coerceAtLeast(Double.MIN_VALUE))) * cos(2 * PI * nextDouble())
public fun Random.nextDoubleNormal(mean: Double, standardDeviation: Double): Double = nextDoubleNormal() * standardDeviation + mean

public data class WeightedList<T>(public val weightTotal: Double, public val values: List<WeightedValue<T>>) {
    public fun random(random: Random): T {
        var currentValue = random.nextDouble(weightTotal)
        for(item in values) {
            currentValue -= item.weight
            if(currentValue < 0) return item.value
        }
        return values.last().value
    }
}
public fun <T> weightedListOf(vararg items: WeightedValue<T>): WeightedList<T> = WeightedList(
    weightTotal = items.sumOf { it.weight },
    values = items.toList()
)
public data class WeightedValue<T>(public val value: T, public val weight: Double)
public infix fun <T> T.weight(value: Double): WeightedValue<T> = WeightedValue(this, value)
public infix fun <T> T.weight(value: Int): WeightedValue<T> = WeightedValue(this, value.toDouble())

public class Faker(public val seed: Long = Random.nextLong()) {
    public val random: Random = Random(seed)
    public fun faker(other: Long): Faker = Faker(31 * seed + other)
    public fun faker(other: Any): Faker = Faker(31 * seed + other.hashCode().toLong())
}

context(faker: Faker) public fun <T> List<T>.random(): T = this.random(faker.random)
context(faker: Faker) public fun <T> WeightedList<T>.random(): T = random(faker.random)
context(faker: Faker) public fun <T> List<T>.randomZipf(): T = this[(size + 1.0).pow(randomDouble()).toInt() - 1]
context(faker: Faker) public fun <T> List<T>.shuffled(): List<T> = this.shuffled(faker.random)
context(faker: Faker) public fun IntRange.random(): Int = this.random(faker.random)
context(faker: Faker) public fun LongRange.random(): Long = this.random(faker.random)
context(faker: Faker) public fun CharRange.random(): Char = this.random(faker.random)
context(faker: Faker) public fun String.random(): Char = this.random(faker.random)
context(faker: Faker) public fun ClosedFloatingPointRange<Float>.random(): Float = faker.random.nextDouble(start.toDouble(), endInclusive.toDouble()).toFloat()
context(faker: Faker) public fun ClosedFloatingPointRange<Double>.random(): Double = faker.random.nextDouble(start, endInclusive)
// The top half is always zero, which no Uuid.random() can be (its version nibble lives there), so
// generated rows can be told apart from real ones and deleted - see deleteSeedData.
context(faker: Faker) public fun randomUuid(): Uuid = Uuid.fromLongs(0L, faker.random.nextLong())
context(faker: Faker) public fun randomBits(bitCount: Int): Int = faker.random.nextBits(bitCount)
context(faker: Faker) public fun randomInt(): Int = faker.random.nextInt()
context(faker: Faker) public fun randomInt(until: Int): Int = faker.random.nextInt(until)
context(faker: Faker) public fun randomInt(from: Int, until: Int): Int = faker.random.nextInt(from, until)
context(faker: Faker) public fun randomLong(): Long = faker.random.nextLong()
context(faker: Faker) public fun randomLong(until: Long): Long = faker.random.nextLong(until)
context(faker: Faker) public fun randomLong(from: Long, until: Long): Long = faker.random.nextLong(from, until)
context(faker: Faker) public fun randomBoolean(): Boolean = faker.random.nextBoolean()
context(faker: Faker) public fun randomDoubleNormal(): Double = faker.random.nextDoubleNormal()
context(faker: Faker) public fun randomDoubleNormal(mean: Double, standardDeviation: Double): Double = faker.random.nextDoubleNormal(mean, standardDeviation)
context(faker: Faker) public fun randomDouble(): Double = faker.random.nextDouble()
context(faker: Faker) public fun randomDouble(until: Double): Double = faker.random.nextDouble(until)
context(faker: Faker) public fun randomDouble(from: Double, until: Double): Double = faker.random.nextDouble(from, until)
context(faker: Faker) public fun randomFloat(): Float = faker.random.nextFloat()
context(faker: Faker) public fun randomBytes(size: Int): ByteArray = faker.random.nextBytes(size)
context(faker: Faker) public fun randomLocalDate(from: LocalDate, to: LocalDate): LocalDate = LocalDate.fromEpochDays(randomLong(from.toEpochDays(), to.toEpochDays()))
context(faker: Faker) public fun randomInstant(from: Instant, to: Instant): Instant = Instant.fromEpochMilliseconds(randomLong(from.toEpochMilliseconds(), to.toEpochMilliseconds()))
context(faker: Faker) public fun randomDuration(from: Duration, to: Duration): Duration = (randomDouble(from.toDouble(DurationUnit.SECONDS), to.toDouble(DurationUnit.SECONDS))).seconds
context(faker: Faker) public fun randomGeoCoordinateUs(): GeoCoordinate = GeoCoordinate(
    longitude = randomDouble(-124.8, -66.9),
    latitude = randomDouble(24.5, 49.5),
)
context(faker: Faker) public fun randomGeoCoordinate(): GeoCoordinate = GeoCoordinate(
    longitude = randomDouble(-180.0, 180.0),
    latitude = asin(2 * randomDouble() - 1) * (180 / PI)
)
context(faker: Faker) public fun randomEmailAddress(): EmailAddress = (randomStringGarbleWord(3, 5) + "@" + randomStringGarbleWord(3, 5) + "." + tlds.randomZipf()).toEmailAddress()
context(faker: Faker) public fun randomPhoneNumber(): PhoneNumber = phoneNumberGenerators.random()()
context(faker: Faker) public fun randomStringName(): String = randomStringGarbleWord(3, 10)
context(faker: Faker) public fun randomStringFullName(): String = listOfNotNull(
    randomStringName(),
    randomStringName().takeIf { randomBoolean() },
    randomStringName(),
).joinToString(" ")
context(faker: Faker) public fun randomStringId(characters: String, length: Int): String = buildString {
    repeat(length) { append(characters.random()) }
}
context(faker: Faker) public fun randomStringGarbleSentences(sentences: Int): String = buildString {
    var long = randomBoolean()
    repeat(sentences) { it ->
        long = !long
        append(randomStringGarbleSentence(if(long) randomInt(2, 3) else 1))
        if(it != sentences - 1) {
            append("  ")
        }
    }
}
context(faker: Faker) public fun randomStringGarbleSentence(chunks: Int): String = buildString {
    repeat(chunks) { it ->
        val p = randomStringGarbleSentenceChunk(randomDoubleNormal(5.0, 2.0).roundToInt().coerceAtLeast(2))
        append(if(it == 0) p.capitalize() else p)
        if(it != chunks - 1) {
            append(chunkDivider.random())
            append(' ')
        }
    }
    append(endSentencePunctuation.random())
}
context(faker: Faker) public fun randomStringGarbleSentenceChunk(length: Int): String = buildString {
    var sinceShort = 0
    repeat(length) { it ->
        if(randomInt(0, sinceShort + 1) == 0) {
            append(randomStringGarbleWord(syllableFrequencyNotOne.random() * 2 + randomInt(1)))
            sinceShort++
        } else {
            append(singleSyllableCommonWords.randomZipf())
            sinceShort = 0
        }
        if(it != length - 1) {
            append(" ")
        }
    }
}
context(faker: Faker) public fun randomStringGarbleWord(): String = when(val it = syllableFrequency.random()) {
    1 -> singleSyllableCommonWords.randomZipf()
    else -> randomStringGarbleWord(it * 2 + randomInt(1))
}
context(faker: Faker) public fun randomStringGarbleWord(minSounds: Int, maxSounds: Int): String = randomStringGarbleWord(randomInt(minSounds, maxSounds))
context(faker: Faker) public fun randomStringGarbleWord(sounds: Int): String {
    while(true) {
        val r = buildString {
            var consonant: Boolean = true //boolean()
            repeat(sounds) { i ->
                val options = if (i == 0) {
                    if (consonant) consonantsStart else vowelsStart
                } else if (i == sounds - 1) {
                    if (consonant) consonantsEnd else vowelsEnd
                } else {
                    if (consonant) consonantsMid else vowelsMid
                }
                append(options.randomZipf())
                consonant = !consonant
            }
        }
        if (!BadWordList.detectParanoid(r)) return r
    }
}
private val phoneNumberGenerators: WeightedList<context(Faker) () -> PhoneNumber> = weightedListOf(
    context(faker: Faker) fun(): PhoneNumber { return ("+1" + randomStringId("0123456789", 10)).toPhoneNumber() } weight 10,
    context(faker: Faker) fun(): PhoneNumber { return ("+81" + "0" + randomStringId("0123456789", 9)).toPhoneNumber() } weight 1,
    context(faker: Faker) fun(): PhoneNumber { return ("+44" + "0" + randomStringId("0123456789", 10)).toPhoneNumber() } weight 1,
    context(faker: Faker) fun(): PhoneNumber { return ("+55" + "0" + randomStringId("0123456789", 10)).toPhoneNumber() } weight 1,
    context(faker: Faker) fun(): PhoneNumber { return ("+55" + "0" + randomStringId("0123456789", 11)).toPhoneNumber() } weight 1,
)
private val tlds = listOf("com", "org", "net", "io", "ai")
private val chunkDivider = weightedListOf("," weight 10, ";" weight 3, ":" weight 1)
private val endSentencePunctuation = weightedListOf("." weight 10, "!" weight 3, "?" weight 3)

// Ordered by frequency in English so the Zipf pick above produces plausible-looking words.
private val consonantsStart = listOf("th", "t", "s", "w", "h", "b", "c", "m", "d", "p", "f", "g", "l", "n", "r", "sh", "y", "v", "ch", "k", "j")
private val consonantsMid = listOf("t", "n", "s", "r", "d", "l", "c", "m", "th", "w", "f", "g", "p", "h", "b", "v", "ck", "ch", "sh", "j")
private val consonantsEnd = listOf("t", "n", "s", "r", "d", "l", "th", "c", "m", "ng", "g", "ck", "f", "p", "b", "sh", "ch")
private val vowelsStart = listOf("a", "o", "e", "i")
private val vowelsMid = listOf("e", "a", "i", "o", "u", "ee", "oo")
private val vowelsEnd = listOf("e", "y", "ay", "ee", "ey", "oo", "ah", "oh", "uh", "eh", "ih", "oy")
private val syllableFrequency = weightedListOf(
    1 weight 6_312,
    2 weight 15_930,
    3 weight 10_944,
    4 weight 6_059,
    5 weight 2_161,
    6 weight 666,
    7 weight 156,
    8 weight 50,
)
private val syllableFrequencyNotOne = weightedListOf(
    2 weight 15_930,
    3 weight 10_944,
    4 weight 6_059,
    5 weight 2_161,
    6 weight 666,
    7 weight 156,
    8 weight 50,
)
private val singleSyllableCommonWords = listOf(
    "the",
    "be",
    "to",
    "of",
    "and",
    "a",
    "in",
    "that",
    "have",
    "I",
    "it",
    "for",
    "not",
    "on",
    "with",
    "he",
    "as",
    "you",
    "do",
    "at",
    "this",
    "but",
    "his",
    "by",
    "from",
    "they",
    "we",
    "say",
    "her",
    "she",
    "or",
    "an",
    "will",
    "my",
    "one",
    "all",
    "would",
    "there",
    "their",
    "what",
    "so",
    "up",
    "out",
    "if",
    "about",
    "who",
    "get",
    "which",
    "go",
    "me",
    "when",
    "make",
    "can",
    "like",
    "time",
    "no",
    "just",
    "him",
    "know",
    "take",
    "people",
    "into",
    "year",
    "your",
    "good",
    "some",
    "could",
    "them",
    "see",
    "other",
    "than",
    "then",
    "now",
    "look",
    "only",
    "come",
    "its",
    "over",
    "think",
    "also",
    "back",
    "after",
    "use",
    "two",
    "how",
    "our",
    "work",
    "first",
    "well",
    "way",
    "even",
    "new",
    "want",
    "because",
    "any",
    "these",
    "give",
    "day",
    "most",
    "us",
)
private fun String.capitalize() =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }