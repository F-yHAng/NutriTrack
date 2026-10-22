package com.example.nutritrack.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class NutritionRecommendationCategory {
    FRUIT_AND_VEGETABLES,
    HYDRATION,
    SUGAR,
    SODIUM,
    BALANCED_MEALS,
    LIFESTYLE
}

enum class NutritionTargetUnit {
    SERVINGS_PER_DAY,
    MILLILITERS_PER_DAY,
    GRAMS_PER_DAY,
    MILLIGRAMS_PER_DAY,
    MEALS_PER_DAY,
    MINUTES_PER_DAY
}

data class NutritionRecommendationV1(
    val category: NutritionRecommendationCategory,
    val title: String,
    val action: String,
    val targetValue: Double,
    val targetUnit: NutritionTargetUnit
)

data class NutritionPlanOutputV1(
    val schemaVersion: Int,
    val summary: String,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val recommendations: List<NutritionRecommendationV1>,
    val cautions: List<String>,
    val disclaimer: String,
    val evidenceToolCallIds: List<String>
)

data class ValidatedNutritionPlanOutput(
    val plan: NutritionPlanOutputV1,
    val normalizedJson: String,
    val draftInput: DraftNutritionPlanInput
)

enum class NutritionPlanValidationError {
    MALFORMED_JSON,
    UNKNOWN_FIELD,
    INVALID_SCHEMA_VERSION,
    INVALID_STRING,
    INVALID_DATE,
    INVALID_ENUM,
    INVALID_NUMBER,
    INVALID_EVIDENCE,
    UNSAFE_CONTENT
}

class NutritionPlanValidationException(
    val reason: NutritionPlanValidationError,
    val safetyDiagnostic: PlanSafetyDiagnostic? = null
) : IllegalArgumentException(reason.name + (safetyDiagnostic?.let { " ${it.safeSummary}" } ?: ""))

class NutritionPlanOutputParser {
    /** Re-validates a stored plan whose evidence references have already been mapped to audit IDs. */
    fun parse(
        rawJson: String,
        request: NutritionPlanningRequest,
        successfulToolCallIds: Set<String>
    ): ValidatedNutritionPlanOutput = parseValidated(
        rawJson = rawJson,
        request = request,
        successfulEvidenceReferences = successfulToolCallIds.associateWith { it },
        requireTemporaryAliases = false
    )

    /** Validates fresh model output and maps its bounded temporary aliases to audit IDs. */
    fun parse(
        rawJson: String,
        request: NutritionPlanningRequest,
        successfulEvidenceReferences: Map<String, String>
    ): ValidatedNutritionPlanOutput = parseValidated(
        rawJson = rawJson,
        request = request,
        successfulEvidenceReferences = successfulEvidenceReferences,
        requireTemporaryAliases = true
    )

    private fun parseValidated(
        rawJson: String,
        request: NutritionPlanningRequest,
        successfulEvidenceReferences: Map<String, String>,
        requireTemporaryAliases: Boolean
    ): ValidatedNutritionPlanOutput {
        if (rawJson.length > MAX_JSON_LENGTH || rawJson.trim().let { !it.startsWith('{') || !it.endsWith('}') }) {
            fail(NutritionPlanValidationError.MALFORMED_JSON)
        }
        val root = parseObject(rawJson)
        root.requireExactKeys(ROOT_FIELDS)

        val schemaVersion = root.strictInt("schemaVersion")
        if (schemaVersion != SCHEMA_VERSION) fail(NutritionPlanValidationError.INVALID_SCHEMA_VERSION)
        val summary = root.strictText("summary", MAX_SUMMARY_LENGTH, checkMedicalSafety = true)
        val validFrom = root.strictDate("validFrom")
        val validTo = root.strictDate("validTo")
        val inclusiveDays = ChronoUnit.DAYS.between(validFrom, validTo) + 1
        if (
            validFrom != request.validFrom || validTo != request.validTo ||
            inclusiveDays !in 1..MAX_PLAN_DAYS
        ) {
            fail(NutritionPlanValidationError.INVALID_DATE)
        }

        val recommendations = root.requiredArray("recommendations")
            .takeIf { it.size() in 1..MAX_RECOMMENDATIONS }
            ?.mapIndexed { index, item -> parseRecommendation(item, index) }
            ?: fail(NutritionPlanValidationError.INVALID_NUMBER)
        val cautions = root.requiredArray("cautions").strictTextList(
            fieldPath = "cautions",
            maximumItems = MAX_CAUTIONS,
            maximumLength = MAX_CAUTION_LENGTH,
            allowEmpty = true,
            checkPii = true,
            checkMedicalSafety = true
        )
        val disclaimer = root.strictText(
            "disclaimer",
            MAX_DISCLAIMER_LENGTH,
            checkMedicalSafety = true
        )
        val evidenceReferences = root.requiredArray("evidenceToolCallIds").strictTextList(
            fieldPath = "evidenceToolCallIds",
            maximumItems = NutritionPlanningAgentConfig.MAX_TOOL_CALLS,
            maximumLength = MAX_EVIDENCE_ID_LENGTH,
            allowEmpty = false,
            checkPii = false,
            checkMedicalSafety = false
        )
        if (
            successfulEvidenceReferences.isEmpty() ||
            successfulEvidenceReferences.keys.any(String::isBlank) ||
            successfulEvidenceReferences.values.any(String::isBlank) ||
            successfulEvidenceReferences.values.size != successfulEvidenceReferences.values.distinct().size ||
            (requireTemporaryAliases && !successfulEvidenceReferences.hasContiguousTemporaryAliases()) ||
            evidenceReferences.size != evidenceReferences.distinct().size ||
            evidenceReferences.any { it !in successfulEvidenceReferences }
        ) {
            fail(NutritionPlanValidationError.INVALID_EVIDENCE)
        }
        val evidenceIds = evidenceReferences.map(successfulEvidenceReferences::getValue)

        val plan = NutritionPlanOutputV1(
            schemaVersion = schemaVersion,
            summary = summary,
            validFrom = validFrom,
            validTo = validTo,
            recommendations = recommendations,
            cautions = cautions,
            disclaimer = disclaimer,
            evidenceToolCallIds = evidenceIds
        )
        val normalizedJson = plan.toNormalizedJson()
        return ValidatedNutritionPlanOutput(
            plan = plan,
            normalizedJson = normalizedJson,
            draftInput = DraftNutritionPlanInput(
                schemaVersion = schemaVersion,
                validFrom = validFrom,
                validUntil = validTo,
                contentJson = normalizedJson,
                cautions = cautions,
                disclaimer = disclaimer
            )
        )
    }

    private fun Map<String, String>.hasContiguousTemporaryAliases(): Boolean {
        if (size !in 1..MAX_TEMPORARY_EVIDENCE_ALIASES) return false
        val expectedAliases = (1..size).mapTo(linkedSetOf()) { index -> "e$index" }
        return keys == expectedAliases && keys.all(TEMPORARY_EVIDENCE_ALIAS_PATTERN::matches)
    }

    private fun parseRecommendation(element: JsonElement, index: Int): NutritionRecommendationV1 {
        if (!element.isJsonObject) fail(NutritionPlanValidationError.MALFORMED_JSON)
        val item = element.asJsonObject
        item.requireExactKeys(RECOMMENDATION_FIELDS)
        val category = item.strictEnum<NutritionRecommendationCategory>("category")
        val unit = item.strictEnum<NutritionTargetUnit>("targetUnit")
        if (CATEGORY_UNIT[category] != unit) fail(NutritionPlanValidationError.INVALID_ENUM)
        val targetValue = item.strictDouble("targetValue")
        val allowedRange = TARGET_RANGES.getValue(unit)
        if (targetValue !in allowedRange) fail(NutritionPlanValidationError.INVALID_NUMBER)
        return NutritionRecommendationV1(
            category = category,
            title = item.strictText("title", MAX_TITLE_LENGTH, checkMedicalSafety = true,
                fieldPath = "recommendations[$index].title"),
            action = item.strictText("action", MAX_ACTION_LENGTH, checkMedicalSafety = true,
                fieldPath = "recommendations[$index].action"),
            targetValue = targetValue,
            targetUnit = unit
        )
    }

    private fun parseObject(rawJson: String): JsonObject = try {
        JsonParser.parseString(rawJson).takeIf { it.isJsonObject }?.asJsonObject
            ?: fail(NutritionPlanValidationError.MALFORMED_JSON)
    } catch (_: JsonSyntaxException) {
        fail(NutritionPlanValidationError.MALFORMED_JSON)
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        if (keySet() != expected) fail(NutritionPlanValidationError.UNKNOWN_FIELD)
    }

    private fun JsonObject.strictInt(key: String): Int {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?: fail(NutritionPlanValidationError.MALFORMED_JSON)
        if (!primitive.isNumber || !INTEGER_PATTERN.matches(primitive.asString)) {
            fail(NutritionPlanValidationError.INVALID_NUMBER)
        }
        return primitive.asString.toIntOrNull()
            ?: fail(NutritionPlanValidationError.INVALID_NUMBER)
    }

    private fun JsonObject.strictDouble(key: String): Double {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?: fail(NutritionPlanValidationError.MALFORMED_JSON)
        if (!primitive.isNumber) fail(NutritionPlanValidationError.INVALID_NUMBER)
        return primitive.asString.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: fail(NutritionPlanValidationError.INVALID_NUMBER)
    }

    private fun JsonObject.strictDate(key: String): LocalDate = try {
        LocalDate.parse(
            strictText(
                key,
                MAX_DATE_LENGTH,
                checkPii = false,
                checkMedicalSafety = false
            )
        )
    } catch (_: DateTimeException) {
        fail(NutritionPlanValidationError.INVALID_DATE)
    }

    private inline fun <reified T : Enum<T>> JsonObject.strictEnum(key: String): T {
        val value = strictText(
            key,
            MAX_ENUM_LENGTH,
            checkPii = false,
            checkMedicalSafety = false
        )
        return enumValues<T>().firstOrNull { it.name == value }
            ?: fail(NutritionPlanValidationError.INVALID_ENUM)
    }

    private fun JsonObject.strictText(
        key: String,
        maximumLength: Int,
        checkPii: Boolean = true,
        checkMedicalSafety: Boolean,
        fieldPath: String = key
    ): String {
        val primitive = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?: fail(NutritionPlanValidationError.MALFORMED_JSON)
        if (!primitive.isString) fail(NutritionPlanValidationError.INVALID_STRING)
        return validateText(primitive.asString, maximumLength, checkPii, checkMedicalSafety, fieldPath)
    }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?: fail(NutritionPlanValidationError.MALFORMED_JSON)

    private fun JsonArray.strictTextList(
        fieldPath: String,
        maximumItems: Int,
        maximumLength: Int,
        allowEmpty: Boolean,
        checkPii: Boolean,
        checkMedicalSafety: Boolean
    ): List<String> {
        if (size() > maximumItems || (!allowEmpty && size() == 0)) {
            fail(NutritionPlanValidationError.INVALID_NUMBER)
        }
        return mapIndexed { index, element ->
            val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?: fail(NutritionPlanValidationError.MALFORMED_JSON)
            if (!primitive.isString) fail(NutritionPlanValidationError.INVALID_STRING)
            validateText(primitive.asString, maximumLength, checkPii, checkMedicalSafety, "$fieldPath[$index]")
        }
    }

    private fun validateText(
        raw: String,
        maximumLength: Int,
        checkPii: Boolean,
        checkMedicalSafety: Boolean,
        fieldPath: String
    ): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > maximumLength) {
            fail(NutritionPlanValidationError.INVALID_STRING)
        }
        val diagnostic = (if (checkPii) piiRule(value)?.let {
            PlanSafetyDiagnostic(it, fieldPath, PlanSafetyCategory.IDENTITY_OR_CREDENTIAL)
        } else null) ?: if (checkMedicalSafety) medicalDiagnostic(value, fieldPath) else null
        if (diagnostic != null) throw NutritionPlanValidationException(
            NutritionPlanValidationError.UNSAFE_CONTENT, diagnostic
        )
        return value
    }

    private fun piiRule(value: String): PlanSafetyRule? = when {
        EMAIL_PATTERN.containsMatchIn(value) -> PlanSafetyRule.PII_EMAIL
        SENSITIVE_SECRET_PATTERN.containsMatchIn(value) -> PlanSafetyRule.PII_CREDENTIAL
        PHONE_PATTERN.findAll(value).any { match ->
                val candidate = match.value.trim()
                !ISO_DATE_PATTERN.matches(candidate) || runCatching {
                    LocalDate.parse(candidate)
                }.isFailure
            } -> PlanSafetyRule.PII_PHONE
        else -> null
    }

    private fun medicalDiagnostic(value: String, fieldPath: String): PlanSafetyDiagnostic? {
        val english = ENGLISH_MEDICAL_CLAIM_PATTERN.findAll(value).firstOrNull {
            !value.hasEnglishNegationBefore(it.range.first)
        }
        val chinese = if (english == null) CHINESE_MEDICAL_CLAIM_PATTERN.findAll(value).firstOrNull {
            !value.hasChineseNegationBefore(it.range.first)
        } else null
        val match = english ?: chinese ?: return null
        val token = match.value.lowercase()
        val rule = when {
            token.startsWith("diagnos") || token == "诊断" -> PlanSafetyRule.MEDICAL_DIAGNOSIS
            token.startsWith("treat") || token == "治疗" -> PlanSafetyRule.MEDICAL_TREATMENT
            token.startsWith("prescrib") || token == "处方" -> PlanSafetyRule.MEDICAL_PRESCRIPTION
            token.startsWith("cure") || token == "治愈" -> PlanSafetyRule.MEDICAL_CURE
            token.startsWith("guarantee") || token == "保证疗效" -> PlanSafetyRule.MEDICAL_GUARANTEE
            token == "prevent disease" -> PlanSafetyRule.MEDICAL_DISEASE_PREVENTION
            else -> PlanSafetyRule.MEDICAL_MEDICATION_CHANGE
        }
        val hasNegation = if (english != null) {
            ENGLISH_NEGATION_PATTERN.containsMatchIn(value.clauseBefore(match.range.first, ENGLISH_CLAUSE_BOUNDARIES))
        } else {
            CHINESE_NEGATION_PATTERN.containsMatchIn(value.clauseBefore(match.range.first, CHINESE_CLAUSE_BOUNDARIES))
        }
        return PlanSafetyDiagnostic(rule, fieldPath, if (hasNegation)
            PlanSafetyCategory.UNRECOGNIZED_NEGATION_SCOPE else PlanSafetyCategory.UNNEGATED_MEDICAL_CLAIM)
    }

    private fun String.hasEnglishNegationBefore(index: Int): Boolean {
        val clause = clauseBefore(index, ENGLISH_CLAUSE_BOUNDARIES)
        val negation = ENGLISH_NEGATION_PATTERN.findAll(clause).lastOrNull() ?: return false
        val scopedPrefix = clause.substring(negation.range.last + 1)
        val withoutEarlierClaims = ENGLISH_MEDICAL_CLAIM_PATTERN.replace(scopedPrefix, " ")
        return ENGLISH_NEGATION_SCOPE_FILLER.matches(withoutEarlierClaims)
    }

    private fun String.hasChineseNegationBefore(index: Int): Boolean {
        val clause = clauseBefore(index, CHINESE_CLAUSE_BOUNDARIES)
        val negation = CHINESE_NEGATION_PATTERN.findAll(clause).lastOrNull() ?: return false
        val scopedPrefix = clause.substring(negation.range.last + 1)
        val withoutEarlierClaims = CHINESE_MEDICAL_CLAIM_PATTERN.replace(scopedPrefix, "")
        return CHINESE_NEGATION_SCOPE_FILLER.matches(withoutEarlierClaims)
    }

    private fun String.clauseBefore(index: Int, boundaries: Set<Char>): String {
        val prefix = substring(0, index)
        val boundary = prefix.indexOfLast(boundaries::contains)
        return prefix.substring(boundary + 1)
    }

    private fun NutritionPlanOutputV1.toNormalizedJson(): String = JsonObject().apply {
        addProperty("schemaVersion", schemaVersion)
        addProperty("summary", summary)
        addProperty("validFrom", validFrom.toString())
        addProperty("validTo", validTo.toString())
        add("recommendations", JsonArray().apply {
            recommendations.forEach { recommendation ->
                add(JsonObject().apply {
                    addProperty("category", recommendation.category.name)
                    addProperty("title", recommendation.title)
                    addProperty("action", recommendation.action)
                    addProperty("targetValue", recommendation.targetValue)
                    addProperty("targetUnit", recommendation.targetUnit.name)
                })
            }
        })
        add("cautions", JsonArray().apply { cautions.forEach(::add) })
        addProperty("disclaimer", disclaimer)
        add("evidenceToolCallIds", JsonArray().apply { evidenceToolCallIds.forEach(::add) })
    }.toString()

    private fun fail(reason: NutritionPlanValidationError): Nothing =
        throw NutritionPlanValidationException(reason)

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_JSON_LENGTH = 65_536
        private const val MAX_PLAN_DAYS = 31L
        private const val MAX_SUMMARY_LENGTH = 500
        private const val MAX_RECOMMENDATIONS = 8
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_ACTION_LENGTH = 500
        private const val MAX_CAUTIONS = 8
        private const val MAX_CAUTION_LENGTH = 300
        private const val MAX_DISCLAIMER_LENGTH = 600
        private const val MAX_EVIDENCE_ID_LENGTH = 64
        private const val MAX_TEMPORARY_EVIDENCE_ALIASES = 4
        private const val MAX_DATE_LENGTH = 10
        private const val MAX_ENUM_LENGTH = 40
        private val INTEGER_PATTERN = Regex("0|[1-9]\\d*")
        private val TEMPORARY_EVIDENCE_ALIAS_PATTERN = Regex("e[1-4]")
        private val ROOT_FIELDS = setOf(
            "schemaVersion",
            "summary",
            "validFrom",
            "validTo",
            "recommendations",
            "cautions",
            "disclaimer",
            "evidenceToolCallIds"
        )
        private val RECOMMENDATION_FIELDS = setOf(
            "category",
            "title",
            "action",
            "targetValue",
            "targetUnit"
        )
        private val CATEGORY_UNIT = mapOf(
            NutritionRecommendationCategory.FRUIT_AND_VEGETABLES to NutritionTargetUnit.SERVINGS_PER_DAY,
            NutritionRecommendationCategory.HYDRATION to NutritionTargetUnit.MILLILITERS_PER_DAY,
            NutritionRecommendationCategory.SUGAR to NutritionTargetUnit.GRAMS_PER_DAY,
            NutritionRecommendationCategory.SODIUM to NutritionTargetUnit.MILLIGRAMS_PER_DAY,
            NutritionRecommendationCategory.BALANCED_MEALS to NutritionTargetUnit.MEALS_PER_DAY,
            NutritionRecommendationCategory.LIFESTYLE to NutritionTargetUnit.MINUTES_PER_DAY
        )
        private val TARGET_RANGES = mapOf(
            NutritionTargetUnit.SERVINGS_PER_DAY to 0.0..20.0,
            NutritionTargetUnit.MILLILITERS_PER_DAY to 0.0..10_000.0,
            NutritionTargetUnit.GRAMS_PER_DAY to 0.0..1_000.0,
            NutritionTargetUnit.MILLIGRAMS_PER_DAY to 0.0..50_000.0,
            NutritionTargetUnit.MEALS_PER_DAY to 0.0..20.0,
            NutritionTargetUnit.MINUTES_PER_DAY to 0.0..1_440.0
        )
        private val ENGLISH_CLAUSE_BOUNDARIES = setOf('.', '!', '?', ';', ':', '\n')
        private val CHINESE_CLAUSE_BOUNDARIES = setOf('。', '！', '？', '；', '：', '\n')
        private val EMAIL_PATTERN = Regex(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            RegexOption.IGNORE_CASE
        )
        private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[ -]?){8,15}(?!\\d)")
        private val ISO_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val SENSITIVE_SECRET_PATTERN = Regex(
            "\\b(?:password|debug[ _-]?token|api[ _-]?key|AIza[0-9A-Za-z_-]+)\\b",
            RegexOption.IGNORE_CASE
        )
        private val ENGLISH_MEDICAL_CLAIM_PATTERN = Regex(
            "\\b(?:diagnos(?:e|is|ed|ing)|treat(?:ment|ed|ing)?|prescrib(?:e|ed|ing)|cure[sd]?|" +
                "guarantee(?:d|s)?|prevent disease|(?:start|stop|change|adjust|increase|decrease|skip)" +
                "\\s+(?:your\\s+)?(?:medication|dose))\\b",
            RegexOption.IGNORE_CASE
        )
        private val ENGLISH_NEGATION_PATTERN = Regex(
            "\\b(?:does not|do not|is not|cannot|can't|won't|isn't|doesn't|not|no|never|without)\\b",
            RegexOption.IGNORE_CASE
        )
        private val ENGLISH_NEGATION_SCOPE_FILLER = Regex(
            "(?:\\s|,|/|\\b(?:or|nor|medical|intended|designed|meant|to|provide|offer|" +
                "constitute|as|a|any|substitute|for|professional|care|medication|advice)\\b)*",
            RegexOption.IGNORE_CASE
        )
        private val CHINESE_MEDICAL_CLAIM_PATTERN =
            Regex("诊断|治疗|处方|药物调整|调整药物|停药|治愈|保证疗效")
        private val CHINESE_NEGATION_PATTERN = Regex("不会|不能|不可|并非|不要|不|非|无|勿")
        private val CHINESE_NEGATION_SCOPE_FILLER =
            Regex("[\\s、，,或和与及以及医学医疗用于进行提供构成意在旨在建议]*")
    }
}
