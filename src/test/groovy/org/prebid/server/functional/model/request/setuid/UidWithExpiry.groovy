package org.prebid.server.functional.model.request.setuid

import com.fasterxml.jackson.annotation.JsonFormat
import groovy.transform.ToString

import java.time.ZonedDateTime

@ToString(includeNames = true, ignoreNulls = true)
class UidWithExpiry {

    String uid
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", timezone = "UTC")
    ZonedDateTime expires
}
