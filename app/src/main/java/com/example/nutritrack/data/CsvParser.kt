package com.example.nutritrack.data

import com.opencsv.CSVReader
import java.io.Reader
import java.util.Locale

class CsvValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class CsvParser {

    fun parsePatientData(reader: Reader): List<Patient> {
        try {
            CSVReader(reader).use { csvReader ->
                val headers = csvReader.readNext()
                    ?: throw CsvValidationException("CSV is missing its header row")
                val fieldMap = createFieldMap(headers)
                val missingHeaders = REQUIRED_HEADERS - fieldMap.keys
                if (missingHeaders.isNotEmpty()) {
                    throw CsvValidationException(
                        "CSV is missing required headers: ${missingHeaders.sorted().joinToString()}"
                    )
                }

                val patients = mutableListOf<Patient>()
                val seenUserIds = mutableSetOf<Int>()
                var lineNumber = 1
                while (true) {
                    val row = csvReader.readNext() ?: break
                    lineNumber += 1
                    if (row.size != headers.size) {
                        throw CsvValidationException(
                            "CSV row $lineNumber has ${row.size} columns; expected ${headers.size}"
                        )
                    }

                    val patient = createPatientFromRow(row, fieldMap, lineNumber)
                    if (!seenUserIds.add(patient.userId)) {
                        throw CsvValidationException("CSV row $lineNumber has a duplicate user_id")
                    }
                    patients += patient
                }

                if (patients.isEmpty()) {
                    throw CsvValidationException("CSV contains no patient rows")
                }
                return patients
            }
        } catch (exception: CsvValidationException) {
            throw exception
        } catch (exception: Exception) {
            throw CsvValidationException("Unable to read patient CSV", exception)
        }
    }

    private fun createFieldMap(headers: Array<String>): Map<String, Int> {
        if (headers.isEmpty()) {
            throw CsvValidationException("CSV header row is empty")
        }

        val fieldMap = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, header ->
            val normalizedHeader = normalizeHeader(header)
            if (normalizedHeader.isEmpty()) {
                throw CsvValidationException("CSV header ${index + 1} is blank")
            }
            if (fieldMap.put(normalizedHeader, index) != null) {
                throw CsvValidationException("CSV contains duplicate header: $normalizedHeader")
            }
        }
        return fieldMap
    }
    
    /**
     * Create the Patient object from the data row
     */
    private fun createPatientFromRow(
        row: Array<String>,
        fieldMap: Map<String, Int>,
        lineNumber: Int
    ): Patient {
        fun doubleValue(fieldName: String): Double =
            getDoubleValue(row, fieldMap, fieldName, lineNumber)

        val patient = Patient(
            phoneNumber = getStringValue(row, fieldMap, "phonenumber", lineNumber),
            userId = getIntValue(row, fieldMap, "user_id", lineNumber),
            name = "",
            sex = getStringValue(row, fieldMap, "sex", lineNumber),
            
            // HEIFA total scores
            hEIFAtotalscoreMale = doubleValue("heifatotalscoreMale"),
            hEIFAtotalscoreFemale = doubleValue("heifatotalscoreFemale"),
            
            // Discretionary scores
            discretionaryHEIFAscoreMale = doubleValue("discretionaryheifascoreMale"),
            discretionaryHEIFAscoreFemale = doubleValue("discretionaryheifascoreFemale"),
            discretionaryservesize = doubleValue("discretionaryservesize"),
            
            // Vegetables scores
            vegetablesHEIFAscoreMale = doubleValue("vegetablesheifascoreMale"),
            vegetablesHEIFAscoreFemale = doubleValue("vegetablesheifascoreFemale"),
            vegetableswithlegumesallocatedservesize = doubleValue("vegetableswithlegumesallocatedservesize"),
            legumesallocatedVegetables = doubleValue("legumesallocatedvegetables"),
            vegetablesvariationsscore = doubleValue("vegetablesvariationsscore"),
            vegetablesCruciferous = doubleValue("vegetablescruciferous"),
            vegetablesTuberandbulb = doubleValue("vegetablestuberandbulb"),
            vegetablesOther = doubleValue("vegetablesother"),
            legumes = doubleValue("legumes"),
            vegetablesGreen = doubleValue("vegetablesgreen"),
            vegetablesRedandorange = doubleValue("vegetablesredandorange"),
            
            // Fruit scores
            fruitHEIFAscoreMale = doubleValue("fruitheifascoreMale"),
            fruitHEIFAscoreFemale = doubleValue("fruitheifascoreFemale"),
            fruitservesize = doubleValue("fruitservesize"),
            fruitvariationsscore = doubleValue("fruitvariationsscore"),
            fruitPome = doubleValue("fruitpome"),
            fruitTropicalandsubtropical = doubleValue("fruittropicalandsubtropical"),
            fruitBerry = doubleValue("fruitberry"),
            fruitStone = doubleValue("fruitstone"),
            fruitCitrus = doubleValue("fruitcitrus"),
            fruitOther = doubleValue("fruitother"),
            
            // Grains and cereals scores
            grainsandcerealsHEIFAscoreMale = doubleValue("grainsandcerealsheifascoreMale"),
            grainsandcerealsHEIFAscoreFemale = doubleValue("grainsandcerealsheifascoreFemale"),
            grainsandcerealsservesize = doubleValue("grainsandcerealsservesize"),
            grainsandcerealsNonwholegrains = doubleValue("grainsandcerealsnonwholegrains"),
            wholegrainsHEIFAscoreMale = doubleValue("wholegrainsheifascoreMale"),
            wholegrainsHEIFAscoreFemale = doubleValue("wholegrainsheifascoreFemale"),
            wholegrainsservesize = doubleValue("wholegrainsservesize"),
            
            // Meat and alternatives scores
            meatandalternativesHEIFAscoreMale = doubleValue("meatandalternativesheifascoreMale"),
            meatandalternativesHEIFAscoreFemale = doubleValue("meatandalternativesheifascoreFemale"),
            meatandalternativeswithlegumesallocatedservesize = doubleValue("meatandalternativeswithlegumesallocatedservesize"),
            legumesallocatedMeatandalternatives = doubleValue("legumesallocatedmeatandalternatives"),
            
            // Dairy and alternatives scores
            dairyandalternativesHEIFAscoreMale = doubleValue("dairyandalternativesheifascoreMale"),
            dairyandalternativesHEIFAscoreFemale = doubleValue("dairyandalternativesheifascoreFemale"),
            dairyandalternativesservesize = doubleValue("dairyandalternativesservesize"),
            
            // Sodium scores
            sodiumHEIFAscoreMale = doubleValue("sodiumheifascoreMale"),
            sodiumHEIFAscoreFemale = doubleValue("sodiumheifascoreFemale"),
            sodiummgmilligrams = doubleValue("sodiummgmilligrams"),
            
            // Alcohol scores
            alcoholHEIFAscoreMale = doubleValue("alcoholheifascoreMale"),
            alcoholHEIFAscoreFemale = doubleValue("alcoholheifascoreFemale"),
            alcoholstandarddrinks = doubleValue("alcoholstandarddrinks"),
            
            // Water scores
            waterHEIFAscoreMale = doubleValue("waterheifascoreMale"),
            waterHEIFAscoreFemale = doubleValue("waterheifascoreFemale"),
            water = doubleValue("water"),
            waterTotalmL = doubleValue("watertotalml"),
            beverageTotalmL = doubleValue("beveragetotalml"),
            
            // Sugar scores
            sugarHEIFAscoreMale = doubleValue("sugarheifascoreMale"),
            sugarHEIFAscoreFemale = doubleValue("sugarheifascoreFemale"),
            sugar = doubleValue("sugar"),
            
            // Saturated Fat scores
            saturatedFatHEIFAscoreMale = doubleValue("saturatedfatheifascoreMale"),
            saturatedFatHEIFAscoreFemale = doubleValue("saturatedfatheifascoreFemale"),
            saturatedFat = doubleValue("saturatedfat"),
            
            // Unsaturated Fat scores
            unsaturatedFatHEIFAscoreMale = doubleValue("unsaturatedfatheifascoreMale"),
            unsaturatedFatHEIFAscoreFemale = doubleValue("unsaturatedfatheifascoreFemale"),
            unsaturatedFatservesize = doubleValue("unsaturatedfatservesize")
        )

        if (patient.userId <= 0) {
            throw CsvValidationException("CSV row $lineNumber has a non-positive user_id")
        }
        if (patient.phoneNumber.length !in 8..15 || !patient.phoneNumber.all(Char::isDigit)) {
            throw CsvValidationException("CSV row $lineNumber has an invalid phone number")
        }
        if (patient.sex !in VALID_SEX_VALUES) {
            throw CsvValidationException("CSV row $lineNumber has an unsupported sex value")
        }
        return patient
    }

    private fun getStringValue(
        row: Array<String>,
        fieldMap: Map<String, Int>,
        fieldName: String,
        lineNumber: Int
    ): String {
        val normalizedName = normalizeHeader(fieldName)
        val index = fieldMap[normalizedName]
            ?: throw CsvValidationException("CSV is missing required header: $normalizedName")
        val value = row[index].trim()
        if (value.isEmpty()) {
            throw CsvValidationException("CSV row $lineNumber has an empty $normalizedName value")
        }
        return value
    }

    private fun getIntValue(
        row: Array<String>,
        fieldMap: Map<String, Int>,
        fieldName: String,
        lineNumber: Int
    ): Int {
        return getStringValue(row, fieldMap, fieldName, lineNumber).toIntOrNull()
            ?: throw CsvValidationException("CSV row $lineNumber has an invalid $fieldName value")
    }

    private fun getDoubleValue(
        row: Array<String>,
        fieldMap: Map<String, Int>,
        fieldName: String,
        lineNumber: Int
    ): Double {
        val value = getStringValue(row, fieldMap, fieldName, lineNumber).toDoubleOrNull()
            ?: throw CsvValidationException("CSV row $lineNumber has an invalid $fieldName value")
        if (!value.isFinite() || value < 0.0) {
            throw CsvValidationException("CSV row $lineNumber has an out-of-range $fieldName value")
        }
        return value
    }

    private fun normalizeHeader(header: String): String = header.trim().lowercase(Locale.ROOT)

    private companion object {
        val VALID_SEX_VALUES = setOf("Male", "Female")

        val REQUIRED_HEADERS = setOf(
            "phonenumber", "user_id", "sex",
            "heifatotalscoremale", "heifatotalscorefemale",
            "discretionaryheifascoremale", "discretionaryheifascorefemale", "discretionaryservesize",
            "vegetablesheifascoremale", "vegetablesheifascorefemale",
            "vegetableswithlegumesallocatedservesize", "legumesallocatedvegetables",
            "vegetablesvariationsscore", "vegetablescruciferous", "vegetablestuberandbulb",
            "vegetablesother", "legumes", "vegetablesgreen", "vegetablesredandorange",
            "fruitheifascoremale", "fruitheifascorefemale", "fruitservesize",
            "fruitvariationsscore", "fruitpome", "fruittropicalandsubtropical",
            "fruitberry", "fruitstone", "fruitcitrus", "fruitother",
            "grainsandcerealsheifascoremale", "grainsandcerealsheifascorefemale",
            "grainsandcerealsservesize", "grainsandcerealsnonwholegrains",
            "wholegrainsheifascoremale", "wholegrainsheifascorefemale", "wholegrainsservesize",
            "meatandalternativesheifascoremale", "meatandalternativesheifascorefemale",
            "meatandalternativeswithlegumesallocatedservesize",
            "legumesallocatedmeatandalternatives", "dairyandalternativesheifascoremale",
            "dairyandalternativesheifascorefemale", "dairyandalternativesservesize",
            "sodiumheifascoremale", "sodiumheifascorefemale", "sodiummgmilligrams",
            "alcoholheifascoremale", "alcoholheifascorefemale", "alcoholstandarddrinks",
            "waterheifascoremale", "waterheifascorefemale", "water", "watertotalml",
            "beveragetotalml", "sugarheifascoremale", "sugarheifascorefemale", "sugar",
            "saturatedfatheifascoremale", "saturatedfatheifascorefemale", "saturatedfat",
            "unsaturatedfatheifascoremale", "unsaturatedfatheifascorefemale",
            "unsaturatedfatservesize"
        )
    }
}
