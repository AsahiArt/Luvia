package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardAccessTest {
    @Test
    fun eachCardUsesItsOwnHostAndSelectedIsNotAnInput() {
        val observer = CardHostState(connected = true, controller = false, pairingRevoked = false)
        val controller = CardHostState(connected = true, controller = true, pairingRevoked = false)
        assertFalse(cardCanOperate(observer))
        assertTrue(cardCanOperate(controller))

        val offline = controller.copy(connected = false)
        assertFalse(cardCanOperate(offline))
        assertFalse(cardCanOperate(observer))
        assertTrue(cardCanOperate(controller))

        val revoked = controller.copy(pairingRevoked = true)
        assertFalse(cardCanOperate(revoked))
        assertTrue(cardCanOperate(controller))
        assertEquals(false, cardCanOperate(null))
    }
}