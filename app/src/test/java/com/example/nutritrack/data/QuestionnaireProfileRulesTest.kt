package com.example.nutritrack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionnaireProfileRulesTest {
    @Test
    fun completeAnswers_areValid() {
        assertNull(QuestionnaireProfileRules.validate(validAnswers()))
    }

    @Test
    fun noFoodSelection_isRejected() {
        assertEquals(
            QuestionnaireValidationError.NO_FOOD_SELECTED,
            QuestionnaireProfileRules.validate(validAnswers().copy(canEatFruits = false))
        )
    }

    @Test
    fun unknownPersona_isRejected() {
        assertEquals(
            QuestionnaireValidationError.INVALID_PERSONA,
            QuestionnaireProfileRules.validate(validAnswers().copy(selectedPersona = "Unknown"))
        )
    }

    @Test
    fun malformedOrOutOfRangeTime_isRejected() {
        assertEquals(
            QuestionnaireValidationError.INVALID_TIME,
            QuestionnaireProfileRules.validate(validAnswers().copy(sleepTime = "24:00"))
        )
    }

    @Test
    fun timesLessThanThirtyMinutesApart_areRejected() {
        assertEquals(
            QuestionnaireValidationError.TIME_CONFLICT,
            QuestionnaireProfileRules.validate(validAnswers().copy(sleepTime = "12:29"))
        )
    }

    private fun validAnswers() = QuestionnaireAnswers(
        userId = 1,
        canEatFruits = true,
        canEatVegetables = false,
        canEatGrains = false,
        canEatRedMeat = false,
        canEatSeafood = false,
        canEatPoultry = false,
        canEatFish = false,
        canEatEggs = false,
        canEatNutsAndSeeds = false,
        selectedPersona = "Mindful Eater",
        biggestMealTime = "12:00",
        sleepTime = "22:00",
        wakeUpTime = "07:00"
    )
}
