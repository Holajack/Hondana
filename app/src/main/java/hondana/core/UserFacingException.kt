package hondana.core

/** A failure whose [message] is written for the reader and can be shown as-is. */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
