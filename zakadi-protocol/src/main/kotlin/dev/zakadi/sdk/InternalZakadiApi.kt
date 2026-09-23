package dev.zakadi.sdk

/**
 * Marks the SDK's internal building blocks, the protocol codec among them, and the conformance and
 * debug injection points of spec 07 section 7.15. They are public so that the SDK's own modules and
 * tests can reach them; they are not a host-app API and may change in any release.
 */
@RequiresOptIn(
    message = "Internal Zakadi SDK API; it is not for host apps and may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
annotation class InternalZakadiApi
