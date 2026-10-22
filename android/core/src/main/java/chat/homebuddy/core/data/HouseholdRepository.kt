package chat.homebuddy.core.data

import chat.homebuddy.core.auth.Household
import chat.homebuddy.core.auth.SessionRepository
import chat.homebuddy.core.auth.toHousehold
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.HouseholdCreateRequest

class HouseholdRepository(private val api: HomeBuddyApi, private val session: SessionRepository) {
    suspend fun create(name: String, zipCode: String, homeType: String): Household {
        val household = api.createHousehold(HouseholdCreateRequest(name = name, zipCode = zipCode, homeType = homeType)).toHousehold()
        session.onHouseholdCreated(household)
        return household
    }
}
