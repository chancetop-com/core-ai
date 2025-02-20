import java.io.File

/**
 * @author neo
 */
object App {
    fun replaceText(file: File, oldString: String, newString: String) {
        val content = file.readText()
        val updatedContent = content.replace(oldString, newString)
        file.writeText(updatedContent)
    }

    fun replaceTextEx(file: File, regexPattern: Regex, newString: String) {
        val content = file.readText()
        val updatedContent = regexPattern.replace(content, newString)
        file.writeText(updatedContent)
    }
}
