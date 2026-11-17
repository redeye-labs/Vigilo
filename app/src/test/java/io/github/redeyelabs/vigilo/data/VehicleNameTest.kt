package io.github.redeyelabs.vigilo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleNameTest {
    @Test
    fun customNameWinsOverTesla() {
        assertEquals("Bianchina", VehicleName.display("Bianchina", "Model 3", "Model 3"))
        assertEquals("Bianchina", VehicleName.display("  Bianchina ", null, ""))
    }

    @Test
    fun emptyCustomFallsBackToTesla() {
        assertEquals("Model 3", VehicleName.display("", "Model 3", "Vecchio"))
        assertEquals("Vecchio", VehicleName.display(" ", null, "Vecchio"))
        assertEquals("", VehicleName.display("", "", ""))
    }
}
