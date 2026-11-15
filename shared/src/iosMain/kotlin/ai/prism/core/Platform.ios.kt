package ai.prism.core

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSUUID

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
actual fun newId(): String = NSUUID().UUIDString
actual fun todayText(): String = NSDateFormatter().apply {
    locale = NSLocale("en_US_POSIX")
    dateFormat = "yyyy-MM-dd"
}.stringFromDate(NSDate())
