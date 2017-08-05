package co.postscriptum.service

import spock.lang.Specification

class UserServiceSpec extends Specification {

    def "should guess screen name by email"() {
        expect:
        UserService.guessScreenName(email) == screenName

        where:
        email                     | screenName
        "wito123@aol.com"         | "Wito123"
        "wito.ran.dan.pa@aol.com" | "Wito Ran Dan Pa"
        "wito+ran+dan+pa@aol.com" | "Wito Ran Dan Pa"
    }

}
