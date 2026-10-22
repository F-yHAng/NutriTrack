package com.example.nutritrack

import android.app.Application
import androidx.work.WorkManager
import com.example.nutritrack.agent.AgentRepository
import com.example.nutritrack.agent.AgentModelGateway
import com.example.nutritrack.agent.AgentToolDispatcher
import com.example.nutritrack.agent.AgentToolRegistry
import com.example.nutritrack.agent.AgentTraceLogging
import com.example.nutritrack.agent.NutritionPlanOutputParser
import com.example.nutritrack.agent.RoomNutritionPlanCompletionProcessor
import com.example.nutritrack.agent.SessionManagerCurrentNutritionPlanUserProvider
import com.example.nutritrack.agent.NutritionPlanningAgent
import com.example.nutritrack.agent.RoomNutritionPlanActionDispatcher
import com.example.nutritrack.agent.RoomAgentReadDataSource
import com.example.nutritrack.agent.UuidIdGenerator
import com.example.nutritrack.agent.WorkManagerNutritionPlanLifecycleScheduler
import com.example.nutritrack.agent.WorkManagerNutritionPlanWorkQueue
import com.example.nutritrack.data.FirebaseAiGateway
import com.example.nutritrack.data.FirebaseAgentModelGateway
import com.example.nutritrack.data.FruitNutritionNetwork
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutrackRepository
import com.example.nutritrack.data.SessionManager
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NutrackApplication : Application() {

    // Initialize the Room database and repository using lazy initialization
    private val database by lazy { NutrackDatabase.getDatabase(this) }
    val sessionManager by lazy { SessionManager.create(this) }
    val fruitNutritionGateway by lazy { FruitNutritionNetwork.createGateway() }
    private var isAppCheckInitialized = false
    // Configuration readiness only; does not imply network, quota or model availability.
    val isAiConfigured: Boolean get() = isAppCheckInitialized
    private val agentClock: Clock = Clock.systemDefaultZone()
    val aiGateway by lazy {
        FirebaseAiGateway(isAppCheckInitialized = { isAppCheckInitialized })
    }
    internal val agentModelGateway: AgentModelGateway by lazy {
        FirebaseAgentModelGateway(
            isAppCheckInitialized = { isAppCheckInitialized },
            traceLogger = AgentTraceLogging.logger
        )
    }
    val agentRepository by lazy {
        AgentRepository(
            database = database,
            patientDao = database.patientDao(),
            agentRunDao = database.agentRunDao(),
            agentToolCallDao = database.agentToolCallDao(),
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = agentClock,
            idGenerator = UuidIdGenerator
        )
    }
    val nutritionPlanningAgent by lazy {
        val dispatcher = AgentToolDispatcher(
            registry = AgentToolRegistry(),
            repository = agentRepository,
            readDataSource = RoomAgentReadDataSource(
                patientDao = database.patientDao(),
                questionnaireProfileDao = database.questionnaireProfileDao(),
                foodIntakeDao = database.foodIntakeDao(),
                localZoneId = agentClock.zone
            ),
            fruitNutritionGateway = fruitNutritionGateway,
            clock = agentClock
        )
        NutritionPlanningAgent(
            repository = agentRepository,
            dispatcher = dispatcher,
            modelGateway = agentModelGateway,
            planParser = NutritionPlanOutputParser(),
            clock = agentClock,
            traceLogger = AgentTraceLogging.logger
        )
    }

    val nutritionPlanParser by lazy { NutritionPlanOutputParser() }
    val nutritionPlanActionDispatcher by lazy {
        RoomNutritionPlanActionDispatcher(
            database = database,
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = agentClock
        )
    }
    internal val nutritionPlanCompletionProcessor by lazy {
        RoomNutritionPlanCompletionProcessor(
            database = database,
            nutritionPlanDao = database.nutritionPlanDao(),
            patientDao = database.patientDao(),
            currentUserProvider = SessionManagerCurrentNutritionPlanUserProvider(sessionManager),
            clock = agentClock
        )
    }
    val nutritionPlanLifecycleScheduler by lazy {
        WorkManagerNutritionPlanLifecycleScheduler(
            workQueue = WorkManagerNutritionPlanWorkQueue(WorkManager.getInstance(this)),
            clock = agentClock
        )
    }
    val clock: Clock get() = agentClock
    
    val repository by lazy { 
        NutrackRepository(
            context = this,
            database = database,
            patientDao = database.patientDao(),
            foodIntakeDao = database.foodIntakeDao(),
            userAccountDao = database.userAccountDao(),
            nutriCoachTipDao = database.nutriCoachTipDao(),
            seedMetadataDao = database.seedMetadataDao(),
            questionnaireProfileDao = database.questionnaireProfileDao()
        )
    }
    
    override fun onCreate() {
        super.onCreate()
        isAppCheckInitialized = FirebaseAppCheckInitializer.initialize(this)
        
        // Initialize the database when start
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.initializeDatabase()
            } catch (_: Exception) {
                android.util.Log.e(
                    "NutrackApplication",
                    "Seed data initialization failed; it will be retried on the next request"
                )
            }
        }
    }
}
