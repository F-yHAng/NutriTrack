package com.example.nutritrack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class CsvParserTest {
    private val parser = CsvParser()

    @Test
    fun bundledCsv_parsesAllPatientsWithoutFallbackValues() {
        val patients = parser.parsePatientData(StringReader(loadBundledCsv()))

        assertEquals(9, patients.size)
        assertEquals(9, patients.map(Patient::userId).distinct().size)
        assertTrue(patients.all { it.userId > 0 })
    }

    @Test
    fun missingRequiredHeader_isRejected() {
        val invalidCsv = loadBundledCsv().replaceFirst("PhoneNumber,", "")

        val error = assertThrows(CsvValidationException::class.java) {
            parser.parsePatientData(StringReader(invalidCsv))
        }

        assertTrue(error.message.orEmpty().contains("phonenumber"))
    }

    @Test
    fun invalidNumericValue_isRejectedInsteadOfBecomingZero() {
        val lines = loadBundledCsv().lines().filter(String::isNotEmpty).toMutableList()
        val row = lines[1].split(',').toMutableList()
        row[1] = "not-a-number"
        lines[1] = row.joinToString(",")

        assertThrows(CsvValidationException::class.java) {
            parser.parsePatientData(StringReader(lines.joinToString("\n")))
        }
    }

    @Test
    fun duplicateUserId_isRejected() {
        val lines = loadBundledCsv().lines().filter(String::isNotEmpty).toMutableList()
        lines += lines[1]

        val error = assertThrows(CsvValidationException::class.java) {
            parser.parsePatientData(StringReader(lines.joinToString("\n")))
        }

        assertTrue(error.message.orEmpty().contains("duplicate user_id"))
    }

    @Test
    fun incompleteRow_isRejected() {
        val lines = loadBundledCsv().lines().filter(String::isNotEmpty).toMutableList()
        lines[1] = lines[1].substringBeforeLast(',')

        assertThrows(CsvValidationException::class.java) {
            parser.parsePatientData(StringReader(lines.joinToString("\n")))
        }
    }

    private fun loadBundledCsv(): String {
        return requireNotNull(javaClass.classLoader?.getResourceAsStream("data.csv")) {
            "data.csv must be available as a unit-test resource"
        }.bufferedReader().use { it.readText() }
    }
}
