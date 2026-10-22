package com.example.nutritrack.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.Reader
import java.time.LocalDate
import java.time.ZoneId

enum class SeedInitializationResult {
    IMPORTED,
    ALREADY_INITIALIZED
}

class NutrackRepository(
    private val context: Context,
    private val database: NutrackDatabase,
    private val patientDao: PatientDao,
    private val foodIntakeDao: FoodIntakeDao,
    private val userAccountDao: UserAccountDao,
    private val nutriCoachTipDao: NutriCoachTipDao,
    private val seedMetadataDao: SeedMetadataDao,
    private val questionnaireProfileDao: QuestionnaireProfileDao,
    private val csvParser: CsvParser = CsvParser(),
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val seedReaderProvider: () -> Reader = {
        context.assets.open(SEED_FILE_NAME).bufferedReader()
    },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val localZoneId: ZoneId = ZoneId.systemDefault(),
    private val todayProvider: () -> LocalDate = { LocalDate.now(localZoneId) }
) : DailyIntakeDataSource,
    AuthDataSource,
    QuestionnaireDataSource,
    DashboardDataSource,
    NutriCoachContentDataSource,
    SettingsDataSource {
    private val legacyQuestionnaireMigration = LegacyQuestionnaireMigration(
        context = context,
        database = database,
        questionnaireProfileDao = questionnaireProfileDao,
        nowEpochMillis = nowEpochMillis
    )

    companion object {
        internal const val SEED_NAME = "patient_csv"
        internal const val SEED_VERSION = 1
        private const val SEED_FILE_NAME = "data.csv"
        private const val MAX_FOOD_NAME_LENGTH = 120
        private const val MAX_SERVINGS = 50.0
        private const val MAX_SODIUM_MG = 50_000.0
        private const val MAX_ALCOHOL_DRINKS = 50.0
        private const val MAX_BEVERAGE_ML = 10_000.0
        private const val MAX_GRAMS = 1_000.0
        private const val REQUIRED_NUMERIC_FIELD_COUNT = 13
        private val MIN_INTAKE_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    }

    override suspend fun initializeDatabase(): SeedInitializationResult = withContext(Dispatchers.IO) {
        val existingMetadata = seedMetadataDao.getByName(SEED_NAME)
        if (existingMetadata != null && existingMetadata.version >= SEED_VERSION) {
            return@withContext SeedInitializationResult.ALREADY_INITIALIZED
        }

        val patients = seedReaderProvider().use(csvParser::parsePatientData)
        database.withTransaction {
            val metadataInsideTransaction = seedMetadataDao.getByName(SEED_NAME)
            if (metadataInsideTransaction != null && metadataInsideTransaction.version >= SEED_VERSION) {
                SeedInitializationResult.ALREADY_INITIALIZED
            } else {
                patientDao.insertSeedPatients(patients)
                seedMetadataDao.upsert(
                    SeedMetadata(
                        seedName = SEED_NAME,
                        version = SEED_VERSION,
                        importedAtEpochMillis = nowEpochMillis(),
                        sourceRowCount = patients.size
                    )
                )
                SeedInitializationResult.IMPORTED
            }
        }
    }

    suspend fun forceReloadCsvData(): SeedInitializationResult {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                seedMetadataDao.deleteByName(SEED_NAME)
            }
        }
        return initializeDatabase()
    }
    
    // ============ 用户认证相关方法 ============
    
    /**
     * 检查用户是否已注册
     */
    override suspend fun isUserRegistered(userId: Int): Boolean {
        return userAccountDao.isUserRegistered(userId) > 0
    }
    
    /**
     * 验证用户登录（UserID + 密码）
     */
    override suspend fun validateLogin(userId: Int, password: String): UserAccount? {
        val account = userAccountDao.getUserById(userId) ?: return null
        return withContext(Dispatchers.Default) {
            account.takeIf { passwordHasher.verify(password, it) }
        }
    }
    
    /**
     * 验证用户信息（UserID + PhoneNumber）用于注册
     */
    suspend fun validateUserForRegistration(userId: Int, phoneNumber: String): Patient? {
        val patient = patientDao.getPatientByUserId(userId)
        return patient?.takeIf { it.phoneNumber == phoneNumber }
    }

    override suspend fun isRegistrationIdentityValid(userId: Int, phoneNumber: String): Boolean =
        validateUserForRegistration(userId, phoneNumber) != null
    
    /**
     * 注册新用户
     */
    override suspend fun registerUser(userId: Int, phoneNumber: String, name: String, password: String): Boolean {
        return try {
            // 首先验证用户存在于患者表中
            val patient = validateUserForRegistration(userId, phoneNumber)
            if (patient != null) {
                val credential = withContext(Dispatchers.Default) {
                    passwordHasher.hash(password)
                }
                // 创建用户账户
                val userAccount = UserAccount(
                    userId = userId,
                    phoneNumber = phoneNumber,
                    name = name,
                    passwordHash = credential.hash,
                    passwordSalt = credential.salt,
                    passwordAlgorithm = credential.algorithm,
                    passwordAlgorithmVersion = credential.algorithmVersion,
                    passwordIterations = credential.iterations,
                    passwordKeyLengthBits = credential.keyLengthBits
                )
                userAccountDao.insertUser(userAccount)

                // 更新患者表中的姓名
                patientDao.updatePatientName(userId, name)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            android.util.Log.w("NutrackRepository", "User registration failed")
            false
        }
    }
    
    /**
     * 更新最后登录时间
     */
    override suspend fun updateLastLoginTime(userId: Int) {
        userAccountDao.updateLastLoginTime(userId, System.currentTimeMillis())
    }
    
    /**
     * 获取用户账户信息
     */
    override suspend fun getUserAccount(userId: Int): UserAccount? {
        return userAccountDao.getUserById(userId)
    }

    override suspend fun getSettingsAccount(userId: Int): SettingsAccount? =
        userAccountDao.getUserById(userId)?.let { SettingsAccount(it.userId, it.name, it.phoneNumber) }

    // ============ Patient 相关操作 ============
    fun getAllPatients(): Flow<List<Patient>> = patientDao.getAllPatients()

    override fun observeAvailableUserIds(): Flow<List<Int>> =
        patientDao.getAllPatients().map { patients -> patients.map(Patient::userId) }
    
    suspend fun getPatientByPhoneNumber(phoneNumber: String): Patient? {
        return patientDao.getPatientByPhoneNumber(phoneNumber)
    }
    
    override suspend fun getPatientByUserId(userId: Int): Patient? {
        return patientDao.getPatientByUserId(userId)
    }
    
    suspend fun insertPatient(patient: Patient) {
        patientDao.insertPatient(patient)
    }
    
    suspend fun updatePatient(patient: Patient) {
        patientDao.updatePatient(patient)
    }
    
    suspend fun updatePatientName(userId: Int, name: String) {
        patientDao.updatePatientName(userId, name)
    }
    
    suspend fun deletePatient(patient: Patient) {
        patientDao.deletePatient(patient)
    }
    
    suspend fun getPatientCount(): Int {
        return patientDao.getPatientCount()
    }

    // ============ QuestionnaireProfile 相关操作 ============

    override suspend fun migrateLegacyQuestionnaire(userId: Int): LegacyQuestionnaireMigrationResult {
        return legacyQuestionnaireMigration.migrateForUser(userId)
    }

    override suspend fun getQuestionnaireProfile(userId: Int): QuestionnaireProfile? =
        withContext(Dispatchers.IO) {
            questionnaireProfileDao.getByUserId(userId)
        }

    override suspend fun hasCompletedQuestionnaire(userId: Int): Boolean =
        getQuestionnaireProfile(userId)?.let(QuestionnaireProfileRules::isValid) == true

    override suspend fun saveQuestionnaireProfile(answers: QuestionnaireAnswers): QuestionnaireProfile =
        withContext(Dispatchers.IO) {
            val validationError = QuestionnaireProfileRules.validate(answers)
            require(validationError == null) { "Invalid questionnaire: $validationError" }

            val profile = QuestionnaireProfile(
                userId = answers.userId,
                canEatFruits = answers.canEatFruits,
                canEatVegetables = answers.canEatVegetables,
                canEatGrains = answers.canEatGrains,
                canEatRedMeat = answers.canEatRedMeat,
                canEatSeafood = answers.canEatSeafood,
                canEatPoultry = answers.canEatPoultry,
                canEatFish = answers.canEatFish,
                canEatEggs = answers.canEatEggs,
                canEatNutsAndSeeds = answers.canEatNutsAndSeeds,
                selectedPersona = answers.selectedPersona,
                biggestMealTime = answers.biggestMealTime,
                sleepTime = answers.sleepTime,
                wakeUpTime = answers.wakeUpTime,
                legacyPreferencesMigrationVersion =
                    QuestionnaireProfileRules.LEGACY_PREFERENCES_MIGRATION_VERSION,
                updatedAtEpochMillis = nowEpochMillis()
            )
            database.withTransaction {
                questionnaireProfileDao.upsert(profile)
            }
            legacyQuestionnaireMigration.clearLegacyBusinessKeys(answers.userId)
            profile
        }
    
    // ============ FoodIntake 相关操作 ============

    override fun observeDailyIntakes(
        userId: Int,
        localDate: LocalDate
    ): Flow<List<FoodIntake>> {
        require(userId > 0) { "A positive userId is required" }
        val startInclusive = localDate.atStartOfDay(localZoneId).toInstant().toEpochMilli()
        val endExclusive = localDate.plusDays(1).atStartOfDay(localZoneId).toInstant().toEpochMilli()
        return foodIntakeDao.observeByUserAndDateRange(
            userId = userId,
            startInclusive = startInclusive,
            endExclusive = endExclusive
        )
    }

    override fun observeDailyIntakeSummaries(userId: Int): Flow<List<DailyIntakeSummary>> {
        require(userId > 0) { "A positive userId is required" }
        return foodIntakeDao.observeByUser(userId).map { records ->
            records
                .groupBy { record ->
                    java.time.Instant.ofEpochMilli(record.submissionDate)
                        .atZone(localZoneId)
                        .toLocalDate()
                }
                .map { (date, entries) -> entries.toSummary(date) }
                .sortedByDescending(DailyIntakeSummary::localDate)
        }
    }

    override suspend fun getDailyIntake(userId: Int, recordId: Long): FoodIntake? =
        withContext(Dispatchers.IO) {
            require(userId > 0) { "A positive userId is required" }
            require(recordId > 0) { "A positive recordId is required" }
            foodIntakeDao.getByIdForUser(recordId, userId)
        }

    override suspend fun createDailyIntake(
        userId: Int,
        input: DailyIntakeFormInput
    ): Long = withContext(Dispatchers.IO) {
        require(input.recordId == null) { "A new daily intake cannot already have an ID" }
        val values = validateDailyIntake(userId, input)
        foodIntakeDao.insert(values.toEntity(userId = userId, id = 0L))
    }

    override suspend fun updateDailyIntake(
        userId: Int,
        input: DailyIntakeFormInput
    ) = withContext(Dispatchers.IO) {
        val recordId = input.recordId
            ?.takeIf { it > 0 }
            ?: throw DailyIntakeValidationException(listOf("A valid record ID is required"))
        val existing = foodIntakeDao.getByIdForUser(recordId, userId)
            ?: throw DailyIntakeAccessException()
        val values = validateDailyIntake(userId, input)
        val updated = existing.copy(
            submissionDate = values.localDate
                .atStartOfDay(localZoneId)
                .toInstant()
                .toEpochMilli(),
            foodName = values.foodName,
            vegetablesServings = values.vegetablesServings,
            fruitServings = values.fruitServings,
            grainsServings = values.grainsServings,
            meatServings = values.meatServings,
            dairyServings = values.dairyServings,
            discretionaryServings = values.discretionaryServings,
            sodiumMg = values.sodiumMg,
            alcoholStandardDrinks = values.alcoholStandardDrinks,
            waterMl = values.waterMl,
            totalBeverageMl = values.totalBeverageMl,
            sugarG = values.sugarG,
            saturatedFatG = values.saturatedFatG,
            unsaturatedFatG = values.unsaturatedFatG,
            questionnaireResponses = "{}"
        )
        if (!foodIntakeDao.updateForUser(userId, updated)) {
            throw DailyIntakeAccessException()
        }
    }

    private fun validateDailyIntake(
        userId: Int,
        input: DailyIntakeFormInput
    ): ValidatedDailyIntake {
        val problems = mutableListOf<String>()
        if (userId <= 0) problems += "A valid current user is required"

        val foodName = input.foodName.trim()
        if (foodName.isEmpty()) problems += "Food name is required"
        if (foodName.length > MAX_FOOD_NAME_LENGTH) {
            problems += "Food name must be $MAX_FOOD_NAME_LENGTH characters or fewer"
        }
        if (input.localDate < MIN_INTAKE_DATE || input.localDate > todayProvider()) {
            problems += "Intake date must be between $MIN_INTAKE_DATE and today"
        }

        fun number(label: String, rawValue: String, maximum: Double): Double? {
            if (rawValue.isBlank()) {
                problems += "$label is required"
                return null
            }
            val value = rawValue.toDoubleOrNull()
            if (value == null || !value.isFinite()) {
                problems += "$label must be a valid number"
                return null
            }
            if (value < 0.0 || value > maximum) {
                problems += "$label must be between 0 and ${maximum.toDisplayNumber()}"
                return null
            }
            return value
        }

        val vegetables = number("Vegetable servings", input.vegetablesServings, MAX_SERVINGS)
        val fruit = number("Fruit servings", input.fruitServings, MAX_SERVINGS)
        val grains = number("Grain servings", input.grainsServings, MAX_SERVINGS)
        val meat = number("Meat servings", input.meatServings, MAX_SERVINGS)
        val dairy = number("Dairy servings", input.dairyServings, MAX_SERVINGS)
        val discretionary = number(
            "Discretionary servings",
            input.discretionaryServings,
            MAX_SERVINGS
        )
        val sodium = number("Sodium", input.sodiumMg, MAX_SODIUM_MG)
        val alcohol = number(
            "Alcohol standard drinks",
            input.alcoholStandardDrinks,
            MAX_ALCOHOL_DRINKS
        )
        val water = number("Water", input.waterMl, MAX_BEVERAGE_ML)
        val totalBeverage = number("Total beverage", input.totalBeverageMl, MAX_BEVERAGE_ML)
        val sugar = number("Sugar", input.sugarG, MAX_GRAMS)
        val saturatedFat = number("Saturated fat", input.saturatedFatG, MAX_GRAMS)
        val unsaturatedFat = number("Unsaturated fat", input.unsaturatedFatG, MAX_GRAMS)

        val enteredValues = listOfNotNull(
            vegetables,
            fruit,
            grains,
            meat,
            dairy,
            discretionary,
            sodium,
            alcohol,
            water,
            totalBeverage,
            sugar,
            saturatedFat,
            unsaturatedFat
        )
        if (enteredValues.size == REQUIRED_NUMERIC_FIELD_COUNT && enteredValues.none { it > 0.0 }) {
            problems += "Enter at least one intake amount greater than 0"
        }
        if (water != null && totalBeverage != null && water > totalBeverage) {
            problems += "Total beverage must be at least the water amount"
        }
        if (problems.isNotEmpty()) throw DailyIntakeValidationException(problems)

        return ValidatedDailyIntake(
            foodName = foodName,
            localDate = input.localDate,
            vegetablesServings = requireNotNull(vegetables),
            fruitServings = requireNotNull(fruit),
            grainsServings = requireNotNull(grains),
            meatServings = requireNotNull(meat),
            dairyServings = requireNotNull(dairy),
            discretionaryServings = requireNotNull(discretionary),
            sodiumMg = requireNotNull(sodium),
            alcoholStandardDrinks = requireNotNull(alcohol),
            waterMl = requireNotNull(water),
            totalBeverageMl = requireNotNull(totalBeverage),
            sugarG = requireNotNull(sugar),
            saturatedFatG = requireNotNull(saturatedFat),
            unsaturatedFatG = requireNotNull(unsaturatedFat)
        )
    }

    private fun ValidatedDailyIntake.toEntity(userId: Int, id: Long) = FoodIntake(
        id = id,
        patientUserId = userId,
        submissionDate = localDate.atStartOfDay(localZoneId).toInstant().toEpochMilli(),
        foodName = foodName,
        vegetablesServings = vegetablesServings,
        fruitServings = fruitServings,
        grainsServings = grainsServings,
        meatServings = meatServings,
        dairyServings = dairyServings,
        discretionaryServings = discretionaryServings,
        sodiumMg = sodiumMg,
        alcoholStandardDrinks = alcoholStandardDrinks,
        waterMl = waterMl,
        totalBeverageMl = totalBeverageMl,
        sugarG = sugarG,
        saturatedFatG = saturatedFatG,
        unsaturatedFatG = unsaturatedFatG,
        questionnaireResponses = "{}"
    )

    private fun List<FoodIntake>.toSummary(localDate: LocalDate) = DailyIntakeSummary(
        localDate = localDate,
        recordCount = size,
        vegetablesServings = sumOf(FoodIntake::vegetablesServings),
        fruitServings = sumOf(FoodIntake::fruitServings),
        grainsServings = sumOf(FoodIntake::grainsServings),
        meatServings = sumOf(FoodIntake::meatServings),
        dairyServings = sumOf(FoodIntake::dairyServings),
        discretionaryServings = sumOf(FoodIntake::discretionaryServings),
        waterMl = sumOf(FoodIntake::waterMl),
        sugarG = sumOf(FoodIntake::sugarG)
    )

    private fun Double.toDisplayNumber(): String =
        if (this % 1.0 == 0.0) toInt().toString() else toString()

    private data class ValidatedDailyIntake(
        val foodName: String,
        val localDate: LocalDate,
        val vegetablesServings: Double,
        val fruitServings: Double,
        val grainsServings: Double,
        val meatServings: Double,
        val dairyServings: Double,
        val discretionaryServings: Double,
        val sodiumMg: Double,
        val alcoholStandardDrinks: Double,
        val waterMl: Double,
        val totalBeverageMl: Double,
        val sugarG: Double,
        val saturatedFatG: Double,
        val unsaturatedFatG: Double
    )
    
    // ============ NutriCoachTip 相关操作 ============
    override fun getNutriCoachTipsForUser(userId: Int): Flow<List<NutriCoachTip>> {
        return nutriCoachTipDao.getTipsForUser(userId)
    }
    
    fun getNutriCoachTipsByType(userId: Int, tipType: String): Flow<List<NutriCoachTip>> {
        return nutriCoachTipDao.getTipsForUserByType(userId, tipType)
    }
    
    override suspend fun insertNutriCoachTip(tip: NutriCoachTip): Long {
        return nutriCoachTipDao.insertTip(tip)
    }
    
    suspend fun updateNutriCoachTip(tip: NutriCoachTip) {
        nutriCoachTipDao.updateTip(tip)
    }
    
    suspend fun deleteNutriCoachTip(tip: NutriCoachTip) {
        nutriCoachTipDao.deleteTip(tip)
    }
    
    suspend fun deleteAllTipsForUser(userId: Int) {
        nutriCoachTipDao.deleteAllTipsForUser(userId)
    }
    
    suspend fun getTipCountForUser(userId: Int): Int {
        return nutriCoachTipDao.getTipCountForUser(userId)
    }
    
    suspend fun getNutriCoachTipById(tipId: Long): NutriCoachTip? {
        return nutriCoachTipDao.getTipById(tipId)
    }

}
