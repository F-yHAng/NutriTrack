package com.example.nutritrack.data

data class PasswordValidationResult(
    val isValid: Boolean,
    val errorMessage: String = ""
)

object PasswordValidator {
    
    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 50
    
    /**
     * 验证密码强度
     * 要求：
     * - 至少8个字符
     * - 至少包含一个大写字母
     * - 至少包含一个小写字母
     * - 至少包含一个数字
     * - 至少包含一个特殊字符
     */
    fun validatePassword(password: String): PasswordValidationResult {
        if (password.length < MIN_LENGTH) {
            return PasswordValidationResult(
                false, 
                "密码至少需要 $MIN_LENGTH 个字符"
            )
        }
        
        if (password.length > MAX_LENGTH) {
            return PasswordValidationResult(
                false, 
                "密码不能超过 $MAX_LENGTH 个字符"
            )
        }
        
        if (!password.any { it.isUpperCase() }) {
            return PasswordValidationResult(
                false, 
                "密码必须包含至少一个大写字母"
            )
        }
        
        if (!password.any { it.isLowerCase() }) {
            return PasswordValidationResult(
                false, 
                "密码必须包含至少一个小写字母"
            )
        }
        
        if (!password.any { it.isDigit() }) {
            return PasswordValidationResult(
                false, 
                "密码必须包含至少一个数字"
            )
        }
        
        val specialChars = "!@#$%^&*()_+-=[]{}|;:,.<>?"
        if (!password.any { it in specialChars }) {
            return PasswordValidationResult(
                false, 
                "密码必须包含至少一个特殊字符 (!@#$%^&*等)"
            )
        }
        
        return PasswordValidationResult(true)
    }
    
    /**
     * 验证密码确认
     */
    fun validatePasswordConfirmation(password: String, confirmPassword: String): PasswordValidationResult {
        if (password != confirmPassword) {
            return PasswordValidationResult(
                false,
                "两次输入的密码不一致"
            )
        }
        return PasswordValidationResult(true)
    }
    
    /**
     * 获取密码强度等级
     */
    fun getPasswordStrength(password: String): PasswordStrength {
        var score = 0
        
        if (password.length >= MIN_LENGTH) score++
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { "!@#$%^&*()_+-=[]{}|;:,.<>?".contains(it) }) score++
        if (password.length >= 12) score++
        
        return when (score) {
            0, 1, 2 -> PasswordStrength.WEAK
            3, 4 -> PasswordStrength.MEDIUM
            5, 6 -> PasswordStrength.STRONG
            else -> PasswordStrength.STRONG
        }
    }
}

enum class PasswordStrength(val label: String, val color: Long) {
    WEAK("弱", 0xFFFF5722),
    MEDIUM("中等", 0xFFFF9800),
    STRONG("强", 0xFF4CAF50)
} 